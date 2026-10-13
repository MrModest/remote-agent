import Foundation
import Testing
@testable import RAKit

@MainActor
struct ConnectionTests {
    // Nothing listens on port 1, so each attempt fails at once.
    let server = SavedServer(id: "srv", name: "Test", urls: [URL(string: "http://127.0.0.1:1")!], deviceId: "dev1", addedAt: .now)

    private var failed: (ServerConnection) -> Bool {
        { if case .failed = $0.state { true } else { false } }
    }

    /// Polls until `condition` holds, or fails the test after `timeout`.
    private func wait(_ timeout: Duration = .seconds(10), until condition: () -> Bool) async throws {
        let deadline = ContinuousClock.now + timeout
        while !condition() {
            guard ContinuousClock.now < deadline else {
                Issue.record("timed out")
                return
            }
            try await Task.sleep(for: .milliseconds(5))
        }
    }

    @Test func givesUpUntilStartedAgain() async throws {
        let c = ServerConnection(server: server, token: "", defaults: UserDefaults(suiteName: "dev.remote-agent.ConnectionTests")!) { _ in }
        c.firstRetryDelay = 0.01
        c.start()
        #expect(!failed(c))
        try await wait { failed(c) }

        // Given up: no more attempts, so it stays failed.
        for _ in 0..<40 {
            try await Task.sleep(for: .milliseconds(5))
            #expect(failed(c))
        }

        // Opening the server, or coming back to the app, tries again.
        c.start()
        try await wait { c.state == .connecting }
        try await wait { failed(c) }
        c.stop()
    }
}
