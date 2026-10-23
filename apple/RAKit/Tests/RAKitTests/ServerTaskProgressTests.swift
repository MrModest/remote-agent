import Foundation
import Testing
@testable import RAKit

/// A server whose task answers come from a script.
@MainActor final class FakeTaskSource: TaskSource {
    /// Each poll takes the next answer; the last one repeats.
    var answers: [Result<ServerTask, RPCError>] = []
    var serverVersion: String? = "0.1.0"
    var polls = 0
    var inputs: [String] = []
    var cancelled = false
    var ended: [ServerTask] = []

    func task(_ id: String) async throws -> ServerTask {
        polls += 1
        let answer = answers.count > 1 ? answers.removeFirst() : answers[0]
        return try answer.get()
    }

    func taskInput(_ id: String, text: String) async throws { inputs.append(text) }
    func cancelTask(_ id: String) async throws { cancelled = true }
    func taskEnded(_ task: ServerTask) async { ended.append(task) }
    var revived = 0
    func reviveConnection() { revived += 1 }
}

@MainActor struct ServerTaskProgressTests {
    func task(_ kind: ServerTask.Kind, _ status: ServerTask.Status, output: String = "", version: String? = nil, error: String? = nil) -> ServerTask {
        ServerTask(id: "k1", kind: kind, harness: kind == .updateServer ? nil : "claude", status: status, output: output,
                   url: nil, input: nil, error: error, version: version, startedAt: Date(), endedAt: nil)
    }

    func progress(_ start: ServerTask, _ source: FakeTaskSource) -> ServerTaskProgress {
        let p = ServerTaskProgress(task: start, source: source)
        p.interval = .milliseconds(5)
        p.restartTimeout = .milliseconds(200)
        return p
    }

    func waitUntil(_ cond: @MainActor () -> Bool) async throws {
        for _ in 0..<400 where !cond() { try await Task.sleep(for: .milliseconds(5)) }
        #expect(cond())
    }

    @Test func pollsUntilItEnds() async throws {
        let source = FakeTaskSource()
        source.answers = [.success(task(.login, .running, output: "Paste code")), .failure(.disconnected),
                          .success(task(.login, .running, output: "Paste code > abc")), .success(task(.login, .succeeded, output: "Signed in"))]
        let p = progress(task(.login, .running), source)
        p.start()
        try await waitUntil { p.phase == .done }
        #expect(p.task.output == "Signed in")
        #expect(source.polls == 4)
        #expect(source.ended.map(\.status) == [.succeeded])
        try await p.send("abc")
        #expect(source.inputs == ["abc"])
    }

    @Test func failureAndCancel() async throws {
        let source = FakeTaskSource()
        source.answers = [.success(task(.updateAgent, .failed, error: "codex: exit status 1"))]
        let p = progress(task(.updateAgent, .running), source)
        p.start()
        try await waitUntil { !p.isActive }
        #expect(p.phase == .failed("codex: exit status 1"))

        source.answers = [.success(task(.login, .cancelled))]
        let c = progress(task(.login, .running), source)
        c.start()
        try await c.cancel()
        try await waitUntil { !c.isActive }
        #expect(c.phase == .cancelled && source.cancelled)
    }

    @Test func updateWaitsForTheNewVersion() async throws {
        let source = FakeTaskSource()
        source.answers = [.success(task(.updateServer, .succeeded, version: "0.2.0"))]
        let p = progress(task(.updateServer, .running), source)
        p.start()
        try await waitUntil { p.phase == .restarting }
        try await Task.sleep(for: .milliseconds(30))
        #expect(p.phase == .restarting)  // still the old rad
        source.serverVersion = "0.2.0"
        try await waitUntil { p.phase == .done }
        #expect(source.ended.isEmpty)  // rad's own update doesn't refresh the agents
        #expect(source.revived > 0)  // in case the connection gave up meanwhile
    }

    @Test func updateToARadWithoutTasks() async throws {
        // A release from before tasks doesn't know task.get.
        let source = FakeTaskSource()
        source.answers = [.failure(.disconnected), .failure(RPCError(code: "method_not_found", message: "unknown method task.get"))]
        let p = progress(task(.updateServer, .running), source)
        p.start()
        source.serverVersion = "0.11.0"
        try await waitUntil { p.phase == .done }
        #expect(p.task.version == "0.11.0")
    }

    @Test func updateThatNeverComesBack() async throws {
        let source = FakeTaskSource()
        source.answers = [.success(task(.updateServer, .succeeded, version: "0.2.0"))]
        let p = progress(task(.updateServer, .running), source)
        p.start()
        try await waitUntil { !p.isActive }
        #expect(p.phase == .failed("rad hasn't come back as 0.2.0"))
    }

    @Test func forgottenTask() async throws {
        // rad restarted as a new version before the poll saw the update end.
        let source = FakeTaskSource()
        source.answers = [.failure(.disconnected), .failure(RPCError(code: "not_found", message: "no such task"))]
        let p = progress(task(.updateServer, .running), source)
        p.start()
        source.serverVersion = "0.2.0"
        try await waitUntil { p.phase == .done }
        #expect(p.task.version == "0.2.0")

        // Another task that rad forgot failed.
        let other = FakeTaskSource()
        other.answers = [.failure(RPCError(code: "not_found", message: "no such task"))]
        let q = progress(task(.login, .running), other)
        q.start()
        try await waitUntil { !q.isActive }
        #expect(q.phase == .failed("rad restarted before the task finished"))
    }

    @Test func stopsPolling() async throws {
        let source = FakeTaskSource()
        source.answers = [.success(task(.login, .running))]
        let p = progress(task(.login, .running), source)
        p.start()
        try await waitUntil { source.polls >= 2 }
        p.stop()
        let polls = source.polls
        try await Task.sleep(for: .milliseconds(40))
        #expect(source.polls <= polls + 1)
        #expect(p.phase == .running)
    }
}
