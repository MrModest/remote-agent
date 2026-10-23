import Foundation
import Observation

/// Where a `ServerTaskProgress` finds its task: the connection.
@MainActor public protocol TaskSource: AnyObject {
    func task(_ id: String) async throws -> ServerTask
    func taskInput(_ id: String, text: String) async throws
    func cancelTask(_ id: String) async throws
    /// The version of rad the connection last connected to.
    var serverVersion: String? { get }
    /// Called once an agent's update or sign-in ended.
    func taskEnded(_ task: ServerTask) async
    /// Connects again if the connection gave up, as it may while rad restarts.
    func reviveConnection()
}

/// Follows a task on the server until it ends, polling it each second. A
/// dropped connection doesn't end it: it waits for the server to come back.
/// An update of rad then waits for rad to come back as the new version.
@MainActor @Observable
public final class ServerTaskProgress: Identifiable {
    public enum Phase: Equatable, Sendable {
        case running
        /// rad installed its update and restarts.
        case restarting
        case done
        case failed(String)
        case cancelled
    }

    public nonisolated let id: String
    public private(set) var task: ServerTask
    public private(set) var phase: Phase = .running

    @ObservationIgnored private let source: TaskSource
    /// The version rad had when the task started.
    @ObservationIgnored private let startVersion: String?
    @ObservationIgnored private var polling: Task<Void, Never>?
    @ObservationIgnored private var restartDeadline: ContinuousClock.Instant?
    @ObservationIgnored var interval: Duration = .seconds(1)
    @ObservationIgnored var restartTimeout: Duration = .seconds(120)

    public init(task: ServerTask, source: TaskSource) {
        id = task.id
        self.task = task
        self.source = source
        startVersion = source.serverVersion
    }

    public var isActive: Bool { phase == .running || phase == .restarting }

    /// Polls until the task ends, unless it already has.
    public func start() {
        guard polling == nil, isActive else { return }
        polling = Task { [weak self] in
            if self?.task.isRunning == false { await self?.ended() }
            await self?.run()
        }
    }

    /// Stops polling; the task goes on on the server.
    public func stop() {
        polling?.cancel()
        polling = nil
    }

    /// Types a line into the task's command, such as a code to paste.
    public func send(_ text: String) async throws {
        try await source.taskInput(id, text: text)
    }

    public func cancel() async throws {
        try await source.cancelTask(id)
    }

    private func run() async {
        while !Task.isCancelled {
            switch phase {
            case .running:
                await poll()
            case .restarting:
                source.reviveConnection()
                if let v = task.version, source.serverVersion == v {
                    phase = .done
                } else if let deadline = restartDeadline, .now >= deadline {
                    phase = .failed("rad hasn't come back as \(task.version ?? "the new version")")
                }
            default:
                return
            }
            if !isActive { return }
            try? await Task.sleep(for: interval)
        }
    }

    private func poll() async {
        do {
            task = try await source.task(id)
            if !task.isRunning { await ended() }
        } catch let e as RPCError where e.code == "not_found" || e.code == "method_not_found" {
            // rad forgot it: it restarted, maybe as a release from before tasks.
            if task.kind == .updateServer, let v = source.serverVersion, v != startVersion {
                task.status = .succeeded
                task.version = task.version ?? v
                await ended()
            } else {
                phase = .failed("rad restarted before the task finished")
                if task.kind == .updateAgent || task.kind == .login { await source.taskEnded(task) }
            }
        } catch {
            // Disconnected or slow: ask again.
        }
    }

    private func ended() async {
        switch task.status {
        case .succeeded where task.kind == .updateServer:
            restartDeadline = .now + restartTimeout
            phase = .restarting
        case .succeeded:
            phase = .done
        case .cancelled:
            phase = .cancelled
        default:
            phase = .failed(task.error ?? "The task failed")
        }
        if task.kind == .updateAgent || task.kind == .login {
            await source.taskEnded(task)
        }
    }
}
