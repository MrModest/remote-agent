package dev.remoteagent.core.store

import dev.remoteagent.core.net.Http
import dev.remoteagent.core.net.REVOKED_CODE
import dev.remoteagent.core.net.RpcClient
import dev.remoteagent.core.net.RpcError
import dev.remoteagent.core.net.ServerMessage
import dev.remoteagent.core.net.params
import dev.remoteagent.core.protocol.Event
import dev.remoteagent.core.protocol.FSListing
import dev.remoteagent.core.protocol.HarnessInfo
import dev.remoteagent.core.protocol.ImageRef
import dev.remoteagent.core.protocol.Project
import dev.remoteagent.core.protocol.ServerInfo
import dev.remoteagent.core.protocol.Session
import dev.remoteagent.core.protocol.WireCoding
import dev.remoteagent.core.protocol.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.File
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

/**
 * A live connection to one server: keeps the index (projects + sessions)
 * in sync, owns per-session stores, and reconnects with backoff. It stays
 * `Connecting` while it tries, and gives up after `MAX_ATTEMPTS` failures in
 * a row: `Failed` until `start()` or `retry()`.
 * Mirrors apple/RAKit/Sources/RAKit/Store/ServerConnection.swift. Its state
 * changes on `scope` (the app: the main thread).
 */
class ServerConnection(
    server: SavedServer,
    token: String,
    override val scope: CoroutineScope,
    private val imageCache: ImageCache,
    private val onURLWorked: (String) -> Unit,
    http: OkHttpClient = Http.client,
    storage: Storage? = null,
) : SessionHost {
    private val _server = MutableStateFlow(server)
    val server: StateFlow<SavedServer> = _server.asStateFlow()
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()
    private val _info = MutableStateFlow<ServerInfo?>(null)
    val info: StateFlow<ServerInfo?> = _info.asStateFlow()
    private val _projects = MutableStateFlow<Map<String, Project>>(emptyMap())
    val projects: StateFlow<Map<String, Project>> = _projects.asStateFlow()
    private val _sessions = MutableStateFlow<Map<String, Session>>(emptyMap())
    val sessions: StateFlow<Map<String, Session>> = _sessions.asStateFlow()
    /** The agents installed on the server; null until first loaded. */
    private val _harnesses = MutableStateFlow<List<HarnessInfo>?>(null)
    val harnesses: StateFlow<List<HarnessInfo>?> = _harnesses.asStateFlow()
    private val _indexSynced = MutableStateFlow(false)
    val indexSynced: StateFlow<Boolean> = _indexSynced.asStateFlow()
    override val drafts = Drafts(storage, server.id)

    private val client = RpcClient(token, http)
    private var indexSeq = 0L
    private var indexLive = false
    /** As SessionStore's: index subscriptions not yet synchronized. */
    private var indexOutstanding = 0
    private var indexReplay = mutableListOf<Event>()
    private val sessionStores = HashMap<String, SessionStore>()
    private var wanted = false
    private var runJob: Job? = null
    /** The wait after the first failed attempt, doubling after each one. */
    internal var firstRetryDelay: Duration = 1.seconds
    /** Cuts the run loop's wait short: the socket closed, so reconnect now. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { for (msg in client.messages) handle(msg) }
    }

    internal fun update(server: SavedServer) {
        _server.value = server
    }

    override val isConnected: Boolean get() = _state.value == ConnectionState.Connected

    // Lifecycle

    fun start() {
        wanted = true
        if (runJob?.isActive == true) return
        runJob = scope.launch { runLoop() }
    }

    fun stop() {
        wanted = false
        pause()
    }

    /** Stops for good, revoking this device on the server first if reachable. */
    internal fun unpairAndStop() {
        wanted = false
        runJob?.cancel()
        runJob = null
        _state.value = ConnectionState.Idle
        scope.launch {
            attempt {
                if (!client.isConnected) client.connect(_server.value.urls)
                client.callRaw("device.unpair", timeout = 5.seconds)
            }
            client.disconnect()
        }
    }

    internal fun pause() {
        runJob?.cancel()
        runJob = null
        client.disconnect()
        _state.value = ConnectionState.Idle
    }

    internal fun resumeIfWanted() {
        if (wanted) start()
    }

    /** Reconnect now (e.g. pull-to-refresh after a failure). */
    fun retry() {
        runJob?.cancel()
        runJob = null
        start()
    }

    private suspend fun runLoop() {
        var delay = firstRetryDelay
        var failures = 0
        while (currentCoroutineContext().isActive && wanted) {
            if (client.isConnected) {
                sleep(2.seconds)
                continue
            }
            _state.value = ConnectionState.Connecting
            try {
                _info.value = client.connect(_server.value.urls)
                client.connectedURL?.let(onURLWorked)
                _state.value = ConnectionState.Connected
                delay = firstRetryDelay
                failures = 0
                resubscribeAll()
                scope.launch { refreshHarnesses() }
            } catch (e: RpcError) {
                failures += 1
                // Trying again won't bring back a revoked token.
                if (e.code == "unauthorized" || failures >= MAX_ATTEMPTS) {
                    _state.value = ConnectionState.Failed(e.message)
                    runJob = null
                    return
                }
                sleep(delay)
                delay *= 2
            }
        }
    }

    /** Waits, or until the socket closes. */
    private suspend fun sleep(d: Duration) {
        withTimeoutOrNull(d) { wake.receive() }
    }

    private suspend fun resubscribeAll() {
        subscribeIndex()
        for (store in sessionStores.values.toList()) {
            if (store.isActive) subscribe(store)
        }
    }

    // Messages

    private fun handle(msg: ServerMessage) {
        when (msg) {
            is ServerMessage.Events ->
                if (msg.stream == "index") applyIndex(msg.events) else store(msg.stream)?.apply(msg.events)
            is ServerMessage.Synchronized ->
                if (msg.stream == "index") {
                    val projects = if (msg.reset) HashMap() else HashMap(_projects.value)
                    val sessions = if (msg.reset) HashMap() else HashMap(_sessions.value)
                    val buffered = indexReplay
                    indexReplay = mutableListOf()
                    for (ev in buffered) upsertIndex(ev, projects, sessions)
                    _projects.value = projects
                    _sessions.value = sessions
                    indexSeq = msg.seq
                    indexOutstanding = maxOf(0, indexOutstanding - 1)
                    indexLive = indexOutstanding == 0
                    _indexSynced.value = true
                    for ((id, s) in sessions) {
                        val store = sessionStores[id] ?: continue
                        if (store.session.value == null) store.setSession(s)
                    }
                    drafts.keep { it in sessions }
                } else {
                    store(msg.stream)?.synchronized(msg.seq, msg.reset)
                }
            is ServerMessage.Resync -> {
                Log.d { "resync ${msg.stream}" }
                scope.launch { resubscribe(msg.stream) }
            }
            is ServerMessage.Closed -> {
                Log.d { "closed: ${msg.reason}" }
                indexLive = false
                indexOutstanding = 0
                for (s in sessionStores.values) s.closed()
                // Not after pause(): the socket closed because we closed it.
                if (wanted && runJob != null) {
                    _state.value = ConnectionState.Connecting
                    wake.trySend(Unit)
                }
            }
        }
    }

    private fun store(stream: String): SessionStore? {
        if (!stream.startsWith("session:")) return null
        return sessionStores[stream.removePrefix("session:")]
    }

    private fun applyIndex(events: List<Event>) {
        if (!indexLive) {
            indexReplay.addAll(events)
            return
        }
        val projects = HashMap(_projects.value)
        val sessions = HashMap(_sessions.value)
        for (ev in events) {
            if (ev.seq <= indexSeq) continue
            if (ev.seq != indexSeq + 1) {
                indexLive = false
                scope.launch { subscribeIndex() }
                break
            }
            indexSeq = ev.seq
            upsertIndex(ev, projects, sessions)
        }
        _projects.value = projects
        _sessions.value = sessions
    }

    private fun upsertIndex(ev: Event, projects: MutableMap<String, Project>, sessions: MutableMap<String, Session>) {
        when (ev.type) {
            "project.upserted" -> ev.project?.let { projects[it.id] = it }
            "project.removed" -> ev.id?.let { projects.remove(it) }
            "session.upserted" -> ev.session?.let { sessions[it.id] = it }
            "session.removed" -> ev.id?.let {
                sessions.remove(it)
                drafts[it] = ""
            }
        }
    }

    private suspend fun subscribeIndex() {
        indexOutstanding += 1
        indexLive = false
        indexReplay = mutableListOf()
        attempt { client.callRaw("subscribe", params("stream" to "index", "afterSeq" to indexSeq)) }
            ?: run { indexOutstanding = maxOf(0, indexOutstanding - 1) }
    }

    override suspend fun subscribe(store: SessionStore) {
        Log.d { "session ${store.sessionID} subscribe afterSeq=${store.lastSeq}" }
        store.beginReplay()
        attempt { client.callRaw("subscribe", params("stream" to "session:${store.sessionID}", "afterSeq" to store.lastSeq)) }
            ?: store.subscriptionFailed()
    }

    private suspend fun resubscribe(stream: String) {
        if (stream == "index") subscribeIndex() else store(stream)?.let { subscribe(it) }
    }

    // Session stores

    /** Returns the store for a session, subscribing while it's in use. */
    fun sessionStore(id: String): SessionStore = sessionStores.getOrPut(id) {
        SessionStore(id, this).also { it.setSession(_sessions.value[id]) }
    }

    override fun activate(store: SessionStore) {
        if (!isConnected) return
        scope.launch { subscribe(store) }
    }

    override fun deactivate(store: SessionStore) {
        scope.launch { attempt { client.callRaw("unsubscribe", params("stream" to "session:${store.sessionID}")) } }
    }

    // API

    override suspend fun callRaw(method: String, params: JsonElement, timeout: Duration): JsonElement =
        client.callRaw(method, params, timeout)

    suspend fun refreshHarnesses(force: Boolean = false) {
        attempt { client.call<HarnessList>("harness.list", params("refresh" to force), 45.seconds) }?.let {
            _harnesses.value = it.harnesses
        }
    }

    fun harness(id: String): HarnessInfo? = _harnesses.value?.firstOrNull { it.id == id }

    /**
     * Uploads an image to attach to a prompt. Opening a picker stops the app
     * (and so the socket) for a moment, so this waits for the connection.
     */
    suspend fun uploadImage(data: ByteArray): ImageRef {
        withTimeoutOrNull(10.seconds) { _state.first { it == ConnectionState.Connected } }
        val ref = client.uploadImage(data)
        imageCache.store(data, ref.id)
        return ref
    }

    /** An image's bytes, from the cache or the server. */
    suspend fun imageData(id: String): ByteArray = imageCache.data(id) { client.imageData(id) }

    /** An image as a file, for sharing. */
    suspend fun imageFile(id: String): File = imageCache.namedFile(id) { client.imageData(id) }

    /** The session's shell on the server, for one view to show. */
    fun terminal(sessionID: String): TerminalSession = TerminalSession(TerminalSocketHost(), sessionID, scope)

    /** Terminal sockets over OkHttp, their callbacks moved onto `scope`. */
    private inner class TerminalSocketHost : TerminalHost {
        override fun connected() = isConnected

        override fun open(query: String, listener: TerminalSocket.Listener): TerminalSocket {
            val ws = client.openTerminal(query, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    scope.launch { listener.onText(text) }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val data = bytes.toByteArray()
                    scope.launch { listener.onBinary(data) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    scope.launch { listener.onClosed(code) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = if (response?.code == 401) REVOKED_CODE else 1006
                    scope.launch { listener.onClosed(code) }
                }
            })
            return object : TerminalSocket {
                override fun send(text: String) {
                    ws.send(text)
                }

                override fun send(bytes: ByteArray) {
                    ws.send(bytes.toByteString())
                }

                override fun close() {
                    ws.close(1000, null)
                }
            }
        }
    }

    suspend fun listDirectory(path: String?): FSListing = client.call("fs.list", params("path" to (path ?: "")))

    suspend fun addProject(path: String): Project {
        val p: Project = client.call("project.add", params("path" to path))
        _projects.value += p.id to p
        return p
    }

    suspend fun removeProject(id: String) {
        client.callRaw("project.remove", params("id" to id))
    }

    suspend fun branches(projectID: String): Branches = client.call("git.branches", params("projectId" to projectID))

    suspend fun createSession(p: NewSession): Session {
        val s: Session = client.call("session.create", WireCoding.json.encodeToJsonElement(p), 90.seconds)
        _sessions.value += s.id to s
        return s
    }

    suspend fun archive(sessionID: String, removeWorktree: Boolean) {
        client.callRaw("session.archive", params("sessionId" to sessionID, "removeWorktree" to removeWorktree))
    }

    companion object {
        /** Failed attempts in a row before the connection gives up. */
        const val MAX_ATTEMPTS = 5
    }
}

@Serializable
private data class HarnessList(val harnesses: List<HarnessInfo> = emptyList())

@Serializable
data class Branches(val branches: List<String> = emptyList(), val current: String = "")

@Serializable
data class NewSession(
    val commandId: String = UUID.randomUUID().toString(),
    val projectId: String,
    val harness: String,
    val model: String? = null,
    val effort: String? = null,
    val mode: String? = null,
    val workspace: WorkspaceParams,
    val prompt: String,
    /** Ids of uploaded images (`uploadImage`) that go with the prompt. */
    val images: List<String>? = null,
) {
    @Serializable
    data class WorkspaceParams(val kind: Workspace.Kind, val branch: String? = null, val baseRef: String? = null)
}

// Derived

val Map<String, Session>.activeSessions: List<Session>
    get() = values.filter { !it.archived }.sortedByDescending { it.updatedAt }

val Map<String, Session>.archivedSessions: List<Session>
    get() = values.filter { it.archived }.sortedByDescending { it.updatedAt }

val Map<String, Project>.sortedProjects: List<Project>
    get() = values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

