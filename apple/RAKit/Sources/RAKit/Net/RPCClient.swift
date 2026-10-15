import Foundation

public struct RPCError: Error, LocalizedError, Decodable, Sendable {
    public var code: String
    public var message: String
    public var errorDescription: String? { message }

    public init(code: String, message: String) {
        self.code = code
        self.message = message
    }

    public static let disconnected = RPCError(code: "disconnected", message: "Not connected to the server")
    public static let timeout = RPCError(code: "timeout", message: "The server did not respond in time")
}

/// Server-initiated messages delivered on `RPCClient.messages`.
public enum ServerMessage: Sendable {
    case events(stream: String, events: [Event])
    case synchronized(stream: String, seq: Int64, reset: Bool)
    case resync(stream: String)
    case closed(reason: String?)
}

struct EmptyParams: Encodable {}
public struct Empty: Decodable, Sendable {}

/// One WebSocket connection to rad, speaking the JSON-RPC-shaped protocol.
/// Reconnection is the owner's job: when the socket drops, `messages` yields
/// `.closed` and pending calls fail with `RPCError.disconnected`.
///
/// A socket can die without a word, as when the Mac sleeps and rad gives up
/// on it: nothing arrives, and sends vanish. `ping` finds out, and so does a
/// call that goes unanswered; either drops a socket that doesn't answer.
public actor RPCClient {
    public nonisolated let messages: AsyncStream<ServerMessage>
    private let continuation: AsyncStream<ServerMessage>.Continuation

    private let token: String
    private let session: URLSession
    private var task: URLSessionWebSocketTask?
    private var pending: [Int: CheckedContinuation<Data, Error>] = [:]
    private var pings: [Int: CheckedContinuation<Bool, Never>] = [:]
    private var nextID = 0
    public private(set) var connectedURL: URL?
    /// When the socket last brought something: a message or a pong.
    private var lastHeard = ContinuousClock.now

    public init(token: String) {
        self.token = token
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 15
        cfg.waitsForConnectivity = false
        cfg.urlCache = nil  // images have their own cache (ImageCache)
        session = URLSession(configuration: cfg)
        (messages, continuation) = AsyncStream.makeStream(of: ServerMessage.self, bufferingPolicy: .unbounded)
    }

    /// Connects to the first base URL that accepts our token. Returns server info.
    @discardableResult
    public func connect(baseURLs: [URL]) async throws -> ServerInfo {
        var lastError: Error = RPCError.disconnected
        for base in baseURLs {
            do {
                return try await open(base)
            } catch {
                lastError = error
            }
        }
        throw lastError
    }

    private func open(_ base: URL) async throws -> ServerInfo {
        disconnect(reason: nil, notify: false)
        var comps = URLComponents(url: base.appending(path: "v1/ws"), resolvingAgainstBaseURL: false)!
        comps.scheme = base.scheme == "https" ? "wss" : "ws"
        var req = URLRequest(url: comps.url!)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.timeoutInterval = 10
        let t = session.webSocketTask(with: req)
        t.maximumMessageSize = 64 << 20
        task = t
        lastHeard = .now
        t.resume()
        receive(t)
        do {
            let info: ServerInfo = try await call("server.info", timeout: 8)
            connectedURL = base
            return info
        } catch {
            disconnect(reason: nil, notify: false)
            throw error
        }
    }

    public func disconnect() {
        disconnect(reason: "closed by client", notify: true)
    }

    private func disconnect(reason: String?, notify: Bool) {
        guard let t = task else { return }
        task = nil
        connectedURL = nil
        t.cancel(with: .normalClosure, reason: nil)
        let waiting = pending
        pending = [:]
        for (_, c) in waiting { c.resume(throwing: RPCError.disconnected) }
        let pinging = pings
        pings = [:]
        for (_, c) in pinging { c.resume(returning: false) }
        if notify { continuation.yield(.closed(reason: reason)) }
    }

    public var isConnected: Bool { task != nil }

    /// How long the socket has been silent. The continuous clock counts time
    /// asleep, so after the Mac wakes this is at least as long as the sleep.
    public var silence: Duration { ContinuousClock.now - lastHeard }

    /// Pings the server and waits for the pong. A socket that doesn't answer
    /// within `timeout` seconds is dropped, so the owner reconnects.
    @discardableResult
    public func ping(timeout: Double = 5) async -> Bool {
        guard let t = task else { return false }
        nextID += 1
        let id = nextID
        let alive = await withCheckedContinuation { c in
            pings[id] = c
            t.sendPing { [weak self] error in
                Task { await self?.pong(id, alive: error == nil) }
            }
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(timeout))
                await self?.pong(id, alive: false)
            }
        }
        if alive { lastHeard = .now }
        else if t === task { disconnect(reason: "The server stopped answering", notify: true) }
        return alive
    }

    private func pong(_ id: Int, alive: Bool) {
        pings.removeValue(forKey: id)?.resume(returning: alive)
    }

    private nonisolated func receive(_ t: URLSessionWebSocketTask) {
        t.receive { [weak self] result in
            guard let self else { return }
            Task { await self.handle(result, from: t) }
        }
    }

    private func handle(_ result: Result<URLSessionWebSocketTask.Message, Error>, from t: URLSessionWebSocketTask) {
        guard t === task else { return }
        switch result {
        case .failure(let error):
            disconnect(reason: error.localizedDescription, notify: true)
        case .success(let msg):
            lastHeard = .now
            let data: Data
            switch msg {
            case .string(let s): data = Data(s.utf8)
            case .data(let d): data = d
            @unknown default: data = Data()
            }
            dispatch(data)
            receive(t)
        }
    }

    private struct Envelope: Decodable {
        var id: Int?
        var method: String?
    }

    private struct Notification<P: Decodable>: Decodable { var params: P }
    private struct EventsParams: Decodable { var stream: String; var events: [Event] }
    private struct SyncParams: Decodable { var stream: String; var seq: Int64; var reset: Bool? }
    private struct StreamParams: Decodable { var stream: String }

    private func dispatch(_ data: Data) {
        let dec = WireCoding.decoder()
        guard let env = try? dec.decode(Envelope.self, from: data) else { return }
        if let id = env.id, env.method == nil {
            pending.removeValue(forKey: id)?.resume(returning: data)
            return
        }
        switch env.method {
        case "events":
            if let n = try? dec.decode(Notification<EventsParams>.self, from: data) {
                continuation.yield(.events(stream: n.params.stream, events: n.params.events))
            }
        case "synchronized":
            if let n = try? dec.decode(Notification<SyncParams>.self, from: data) {
                continuation.yield(.synchronized(stream: n.params.stream, seq: n.params.seq, reset: n.params.reset ?? false))
            }
        case "resync":
            if let n = try? dec.decode(Notification<StreamParams>.self, from: data) {
                continuation.yield(.resync(stream: n.params.stream))
            }
        default:
            break
        }
    }

    private struct Request<P: Encodable>: Encodable {
        var id: Int
        var method: String
        var params: P
    }

    private struct Response<R: Decodable>: Decodable {
        var result: R?
        var error: RPCError?
    }

    public func call<R: Decodable & Sendable>(_ method: String, timeout: Double = 30) async throws -> R {
        try await call(method, EmptyParams(), timeout: timeout)
    }

    public func call<P: Encodable & Sendable, R: Decodable & Sendable>(_ method: String, _ params: P, timeout: Double = 30) async throws -> R {
        guard let t = task else { throw RPCError.disconnected }
        nextID += 1
        let id = nextID
        let body = try WireCoding.encoder().encode(Request(id: id, method: method, params: params))
        let data: Data = try await withCheckedThrowingContinuation { c in
            pending[id] = c
            t.send(.string(String(decoding: body, as: UTF8.self))) { [weak self] error in
                if let error, let self { Task { await self.sendFailed(error, on: t) } }
            }
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(timeout))
                await self?.unanswered(id, on: t)
            }
        }
        let resp = try WireCoding.decoder().decode(Response<R>.self, from: data)
        if let e = resp.error { throw e }
        if let r = resp.result { return r }
        if let empty = Empty() as? R { return empty }
        throw RPCError(code: "invalid", message: "empty result for \(method)")
    }

    /// A socket that can't send is broken: drop it, failing its calls.
    private func sendFailed(_ error: Error, on t: URLSessionWebSocketTask) {
        guard t === task else { return }
        disconnect(reason: error.localizedDescription, notify: true)
    }

    /// No answer in time. If the socket doesn't answer a ping either, it's
    /// dropped, which fails the call as disconnected; otherwise the server is
    /// just slow, and the call times out.
    private func unanswered(_ id: Int, on t: URLSessionWebSocketTask) async {
        guard pending[id] != nil else { return }
        if t === task, await !ping() { return }
        pending.removeValue(forKey: id)?.resume(throwing: RPCError.timeout)
    }

    // MARK: HTTP, beside the socket

    /// Uploads an image to attach to a prompt (`POST /v1/images`).
    public func uploadImage(_ data: Data) async throws -> ImageRef {
        var req = try httpRequest("v1/images")
        req.httpMethod = "POST"
        req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 60
        let (body, resp) = try await session.upload(for: req, from: data)
        try check(body, resp)
        return try WireCoding.decoder().decode(ImageRef.self, from: body)
    }

    /// An image's bytes (`GET /v1/images/<id>`).
    public func imageData(_ id: String) async throws -> Data {
        var req = try httpRequest("v1/images/\(id)")
        req.timeoutInterval = 60
        let (body, resp) = try await session.data(for: req)
        try check(body, resp)
        return body
    }

    /// A socket to a session's shell (`GET /v1/terminal`) on the server the
    /// main socket is connected to.
    public func terminalTask(query: [URLQueryItem]) throws -> URLSessionWebSocketTask {
        guard let base = connectedURL else { throw RPCError.disconnected }
        var comps = URLComponents(url: base.appending(path: "v1/terminal"), resolvingAgainstBaseURL: false)!
        comps.scheme = base.scheme == "https" ? "wss" : "ws"
        comps.queryItems = query
        var req = URLRequest(url: comps.url!)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("rad.terminal.v1", forHTTPHeaderField: "Sec-WebSocket-Protocol")
        req.timeoutInterval = 10
        let t = session.webSocketTask(with: req)
        t.maximumMessageSize = 4 << 20
        return t
    }

    /// A request to the server the socket is connected to, with our token.
    private func httpRequest(_ path: String) throws -> URLRequest {
        guard let base = connectedURL else { throw RPCError.disconnected }
        var req = URLRequest(url: base.appending(path: path))
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return req
    }

    private func check(_ body: Data, _ resp: URLResponse) throws {
        guard let http = resp as? HTTPURLResponse, http.statusCode != 200 else { return }
        throw (try? WireCoding.decoder().decode(RPCError.self, from: body))
            ?? RPCError(code: "http", message: "The server answered \(http.statusCode)")
    }
}
