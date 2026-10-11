package dev.remoteagent.core.store

import dev.remoteagent.core.net.REVOKED_CODE
import dev.remoteagent.core.protocol.TerminalMessage
import dev.remoteagent.core.protocol.WireCoding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URLEncoder
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Mirrors apple/RAKit/Sources/RAKit/Store/TerminalSession.swift. */
sealed interface TerminalState {
    data object Connecting : TerminalState
    data object Open : TerminalState
    data object Reconnecting : TerminalState
    /** The shell exited, with its exit code. */
    data class Exited(val code: Int) : TerminalState
    /** The shell ended while this view was away. */
    data object Gone : TerminalState
    data class Failed(val message: String) : TerminalState

    val isLive: Boolean get() = this == Open || this == Connecting || this == Reconnecting
}

/** A socket to a shell, as TerminalSession sees it. */
interface TerminalSocket {
    fun send(text: String)
    fun send(bytes: ByteArray)
    fun close()

    /** Called on the session's scope, in order. */
    interface Listener {
        fun onText(text: String)
        fun onBinary(bytes: ByteArray)
        fun onClosed(code: Int)
    }
}

/** What a TerminalSession needs from the server connection (web/src/store/terminalSession.ts's TerminalHost). */
interface TerminalHost {
    fun connected(): Boolean
    /** Opens `GET /v1/terminal?<query>`; throws while disconnected. */
    fun open(query: String, listener: TerminalSocket.Listener): TerminalSocket
}

/**
 * A session's shell on the server, as one view shows it: the socket to it,
 * and the view's place in its output, so a dropped connection resumes
 * rather than redraws. The shell outlives the view; `detach()` only closes
 * the socket. See "Terminal" in protocol/PROTOCOL.md.
 */
class TerminalSession(private val host: TerminalHost, val sessionID: String, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<TerminalState>(TerminalState.Connecting)
    val state: StateFlow<TerminalState> = _state.asStateFlow()
    /** This view closed the shell, so its exit is no surprise. */
    private val _closedHere = MutableStateFlow(false)
    val closedHere: StateFlow<Boolean> = _closedHere.asStateFlow()

    /** Output to show, in order. */
    var onOutput: (ByteArray) -> Unit = {}
    /** Clear the screen: the output that follows starts afresh. */
    var onReset: () -> Unit = {}

    private data class Size(val cols: Int, val rows: Int)

    private var socket: TerminalSocket? = null
    private var shellID: String? = null
    private var offset = 0L
    private var size: Size? = null
    private var sentSize: Size? = null
    private var create = false
    private var retryDelay = 1
    private var detached = false
    private var retryJob: Job? = null
    private var resizeJob: Job? = null

    val live: Boolean get() = _state.value.isLive

    /**
     * Connects once the view knows its size. `create` starts a shell if the
     * session has none; it's for the user's own action, never a reconnect.
     */
    fun start(cols: Int, rows: Int, create: Boolean) {
        size = Size(cols, rows)
        this.create = create
        connect()
    }

    /** Starts a new shell after the last one ended. */
    fun restart() {
        _closedHere.value = false
        shellID = null
        offset = 0
        create = true
        onReset()
        connect()
    }

    /** Closes the socket. The shell keeps running. */
    fun detach() {
        detached = true
        retryJob?.cancel()
        resizeJob?.cancel()
        drop()
    }

    /** Ends the shell, for every client. */
    fun closeShell() {
        _closedHere.value = true
        sendControl(TerminalMessage(type = "close"))
    }

    fun send(data: ByteArray) {
        val s = socket
        if (_state.value != TerminalState.Open || s == null) return
        var i = 0
        while (i < data.size) {
            s.send(data.copyOfRange(i, minOf(i + CHUNK, data.size)))
            i += CHUNK
        }
    }

    /**
     * The view's size changed. Sent after it settles: panels resize the view
     * while they're dragged.
     */
    fun resize(cols: Int, rows: Int) {
        size = Size(cols, rows)
        resizeJob?.cancel()
        resizeJob = scope.launch {
            delay(100.milliseconds)
            sendSize()
        }
    }

    /**
     * Follows the server connection: the terminal's socket goes with it, and
     * comes back when it does.
     */
    fun connectionChanged(connected: Boolean) {
        if (connected) {
            if (socket == null && live && size != null) connect()
        } else if (socket != null) {
            drop()
            if (live) _state.value = TerminalState.Reconnecting
        }
    }

    private fun connect() {
        retryJob?.cancel()
        drop()
        val size = size
        if (detached || size == null) return
        if (shellID != null) _state.value = TerminalState.Reconnecting
        else if (_state.value != TerminalState.Reconnecting) _state.value = TerminalState.Connecting
        if (!host.connected()) return // connectionChanged resumes
        lateinit var s: TerminalSocket
        val listener = object : TerminalSocket.Listener {
            override fun onText(text: String) {
                if (socket !== s) return
                val m = runCatching { WireCoding.json.decodeFromString(TerminalMessage.serializer(), text) }.getOrNull() ?: return
                if (!handle(m)) drop()
            }

            override fun onBinary(bytes: ByteArray) {
                if (socket !== s) return
                offset += bytes.size
                onOutput(bytes)
            }

            override fun onClosed(code: Int) {
                if (socket !== s) return
                if (code == REVOKED_CODE) {
                    drop()
                    _state.value = TerminalState.Failed("This device is no longer authorized; pair again.")
                    return
                }
                dropped()
            }
        }
        s = try {
            host.open(query(sessionID, size.cols, size.rows, create && shellID == null, shellID, offset), listener)
        } catch (_: Exception) {
            dropped()
            return
        }
        socket = s
    }

    /** Returns false when the socket is done. */
    private fun handle(m: TerminalMessage): Boolean {
        when (m.type) {
            "attached" -> {
                if (m.reset == true || m.id != shellID) onReset()
                shellID = m.id
                offset = m.offset ?: 0
                sentSize = Size(m.cols ?: 0, m.rows ?: 0)
                retryDelay = 1
                _state.value = TerminalState.Open
                sendSize()
            }
            "reset" -> {
                onReset()
                offset = m.offset ?: 0
            }
            "exit" -> {
                shellID = null
                _state.value = TerminalState.Exited(m.exitCode ?: 0)
                return false
            }
            "error" -> {
                shellID = null
                _state.value = if (m.code == "gone") TerminalState.Gone
                else TerminalState.Failed(m.message ?: "The terminal isn't available")
                return false
            }
        }
        return true
    }

    /** The socket dropped: try again, resuming, while the server connection is up. */
    private fun dropped() {
        drop()
        if (detached || !live) return
        _state.value = TerminalState.Reconnecting
        val wait = retryDelay
        retryJob = scope.launch {
            delay(wait.seconds)
            retryDelay = minOf(wait * 2, 10)
            connect()
        }
    }

    private fun drop() {
        val s = socket ?: return
        socket = null
        runCatching { s.close() }
    }

    private fun sendSize() {
        val s = size
        if (_state.value != TerminalState.Open || s == null || sentSize == s) return
        sentSize = s
        sendControl(TerminalMessage(type = "resize", cols = s.cols, rows = s.rows))
    }

    private fun sendControl(m: TerminalMessage) {
        socket?.send(WireCoding.json.encodeToString(TerminalMessage.serializer(), m))
    }

    companion object {
        /** Input goes in frames of at most this many bytes. */
        const val CHUNK = 16 shl 10

        fun query(sessionID: String, cols: Int, rows: Int, create: Boolean, shellID: String?, offset: Long): String {
            fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
            val q = StringBuilder("session=${enc(sessionID)}&cols=$cols&rows=$rows")
            if (create) q.append("&create=1")
            if (shellID != null) q.append("&id=${enc(shellID)}&offset=$offset")
            return q.toString()
        }
    }
}
