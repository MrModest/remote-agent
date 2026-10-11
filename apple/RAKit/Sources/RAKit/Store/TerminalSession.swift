import Foundation
import Observation

/// A session's shell on the server, as one view shows it: the socket to it,
/// and the view's place in its output, so a dropped connection resumes
/// rather than redraws. The shell outlives the view; `detach()` only closes
/// the socket. See "Terminal" in protocol/PROTOCOL.md.
@MainActor @Observable
public final class TerminalSession {
    public enum State: Equatable, Sendable {
        case connecting
        case open
        case reconnecting
        /// The shell exited, with its exit code.
        case exited(Int)
        /// The shell ended while this view was away.
        case gone
        case failed(String)

        public var isLive: Bool { self == .open || self == .connecting || self == .reconnecting }
    }

    public let sessionID: String
    public private(set) var state: State = .connecting
    /// This view closed the shell, so its exit is no surprise.
    public private(set) var closedHere = false

    /// Output to show, in order.
    @ObservationIgnored public var onOutput: ((Data) -> Void)?
    /// Clear the screen: the output that follows starts afresh.
    @ObservationIgnored public var onReset: (() -> Void)?

    @ObservationIgnored private weak var connection: ServerConnection?
    @ObservationIgnored private var task: URLSessionWebSocketTask?
    @ObservationIgnored private var loop: Task<Void, Never>?
    @ObservationIgnored private var sender: AsyncStream<URLSessionWebSocketTask.Message>.Continuation?
    @ObservationIgnored private var resizing: Task<Void, Never>?
    @ObservationIgnored private var retry: Task<Void, Never>?
    @ObservationIgnored private var shellID: String?
    @ObservationIgnored private var offset: UInt64 = 0
    @ObservationIgnored private var size: (cols: Int, rows: Int)?
    @ObservationIgnored private var sentSize: (cols: Int, rows: Int)?
    @ObservationIgnored private var create = false
    @ObservationIgnored private var delay: Double = 1
    @ObservationIgnored private var detached = false

    init(connection: ServerConnection, sessionID: String) {
        self.connection = connection
        self.sessionID = sessionID
    }

    /// Connects once the view knows its size. `create` starts a shell if the
    /// session has none; it's for the user's own action, never a reconnect.
    public func start(cols: Int, rows: Int, create: Bool) {
        size = (cols, rows)
        self.create = create
        connect()
    }

    /// Starts a new shell after the last one ended.
    public func restart() {
        closedHere = false
        shellID = nil
        offset = 0
        create = true
        onReset?()
        connect()
    }

    /// Closes the socket. The shell keeps running.
    public func detach() {
        detached = true
        retry?.cancel()
        drop()
    }

    /// Ends the shell, for every client.
    public func closeShell() {
        closedHere = true
        sendControl(TerminalMessage(type: "close"))
    }

    public func send(_ data: Data) {
        guard state == .open else { return }
        var rest = data[...]
        while !rest.isEmpty {  // the server takes frames up to 1 MiB; keep them small
            sender?.yield(.data(Data(rest.prefix(16 << 10))))
            rest = rest.dropFirst(16 << 10)
        }
    }

    /// The view's size changed. Sent after it settles, since sheets,
    /// sidebars and the keyboard resize the view while they animate.
    public func resize(cols: Int, rows: Int) {
        size = (cols, rows)
        resizing?.cancel()
        resizing = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(100))
            guard !Task.isCancelled else { return }
            self?.sendSize()
        }
    }

    /// Follows the server connection: the terminal's socket goes with it
    /// (the app in the background), and comes back when it does.
    public func connectionChanged(_ state: ConnectionState) {
        if state == .connected {
            if task == nil && self.state.isLive && size != nil { connect() }
        } else if task != nil {
            drop()
            if self.state.isLive { self.state = .reconnecting }
        }
    }

    // MARK: Socket

    nonisolated static func query(sessionID: String, cols: Int, rows: Int, create: Bool, shellID: String?, offset: UInt64) -> [URLQueryItem] {
        var q = [URLQueryItem(name: "session", value: sessionID),
                 URLQueryItem(name: "cols", value: String(cols)),
                 URLQueryItem(name: "rows", value: String(rows))]
        if create { q.append(URLQueryItem(name: "create", value: "1")) }
        if let shellID {
            q.append(URLQueryItem(name: "id", value: shellID))
            q.append(URLQueryItem(name: "offset", value: String(offset)))
        }
        return q
    }

    private func connect() {
        retry?.cancel()
        drop()
        guard !detached, let size else { return }
        if shellID != nil { state = .reconnecting } else if state != .reconnecting { state = .connecting }
        guard let connection, connection.state == .connected else { return }  // connectionChanged resumes
        let query = Self.query(sessionID: sessionID, cols: size.cols, rows: size.rows, create: create && shellID == nil,
                               shellID: shellID, offset: offset)
        loop = Task { [weak self] in
            guard let t = try? await connection.terminalTask(query) else {
                self?.dropped()
                return
            }
            await self?.run(t)
        }
    }

    private func run(_ t: URLSessionWebSocketTask) async {
        task = t
        t.resume()
        let (outgoing, cont) = AsyncStream.makeStream(of: URLSessionWebSocketTask.Message.self)
        sender = cont
        let sending = Task {
            for await m in outgoing { try? await t.send(m) }  // one at a time, in order
        }
        defer {
            sending.cancel()
            cont.finish()
        }
        while !Task.isCancelled {
            let msg: URLSessionWebSocketTask.Message
            do {
                msg = try await t.receive()
            } catch {
                if task === t { dropped() }
                return
            }
            switch msg {
            case .data(let d):
                offset += UInt64(d.count)
                onOutput?(d)
            case .string(let s):
                guard let m = try? WireCoding.decoder().decode(TerminalMessage.self, from: Data(s.utf8)) else { continue }
                if !handle(m) {
                    if task === t { drop() }
                    return
                }
            @unknown default:
                continue
            }
        }
    }

    /// Returns false when the socket is done.
    private func handle(_ m: TerminalMessage) -> Bool {
        switch m.type {
        case "attached":
            if m.reset == true || m.id != shellID { onReset?() }
            shellID = m.id
            offset = m.offset ?? 0
            sentSize = (m.cols ?? 0, m.rows ?? 0)
            state = .open
            delay = 1
            sendSize()
        case "reset":
            onReset?()
            offset = m.offset ?? 0
        case "exit":
            shellID = nil
            state = .exited(m.exitCode ?? 0)
            return false
        case "error":
            shellID = nil
            state = m.code == "gone" ? .gone : .failed(m.message ?? "The terminal isn't available")
            return false
        default:
            break
        }
        return true
    }

    /// The socket dropped: try again, resuming, while the server connection
    /// is up.
    private func dropped() {
        drop()
        guard !detached, state.isLive else { return }
        state = .reconnecting
        retry = Task { [weak self, delay] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled, let self else { return }
            self.delay = min(delay * 2, 10)
            self.connect()
        }
    }

    private func drop() {
        loop?.cancel()
        loop = nil
        sender?.finish()
        sender = nil
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
    }

    private func sendSize() {
        guard state == .open, let size, sentSize.map({ $0 != size }) ?? true else { return }
        sentSize = size
        sendControl(TerminalMessage(type: "resize", cols: size.cols, rows: size.rows))
    }

    private func sendControl(_ m: TerminalMessage) {
        guard let data = try? WireCoding.encoder().encode(m) else { return }
        sender?.yield(.string(String(decoding: data, as: UTF8.self)))
    }
}
