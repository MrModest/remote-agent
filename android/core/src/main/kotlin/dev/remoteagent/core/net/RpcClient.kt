package dev.remoteagent.core.net

import dev.remoteagent.core.protocol.Event
import dev.remoteagent.core.protocol.ImageRef
import dev.remoteagent.core.protocol.ServerInfo
import dev.remoteagent.core.protocol.WireCoding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RpcError(val code: String, override val message: String) : Exception(message) {
    companion object {
        fun disconnected() = RpcError("disconnected", "Not connected to the server")
        fun timeout() = RpcError("timeout", "The server did not respond in time")
        fun unauthorized() = RpcError("unauthorized", "This device is no longer authorized; pair again.")
    }
}

/** Server-initiated messages, delivered on `RpcClient.messages`. */
sealed interface ServerMessage {
    data class Events(val stream: String, val events: List<Event>) : ServerMessage
    data class Synchronized(val stream: String, val seq: Long, val reset: Boolean) : ServerMessage
    data class Resync(val stream: String) : ServerMessage
    data class Closed(val reason: String?) : ServerMessage
}

/** Close code rad uses for a revoked device. */
const val REVOKED_CODE = 4001

/** `{}`, the params of a call that takes none. */
val NoParams = JsonObject(emptyMap())

/**
 * One WebSocket connection to rad, speaking the JSON-RPC-shaped protocol.
 * Reconnection is the owner's job: when the socket drops, `messages` gets
 * `Closed` and pending calls fail with `RpcError.disconnected()`.
 * Mirrors apple/RAKit/Sources/RAKit/Net/RPCClient.swift.
 */
class RpcClient(private val token: String, http: OkHttpClient = Http.client) {
    private val channel = Channel<ServerMessage>(Channel.UNLIMITED)
    val messages: ReceiveChannel<ServerMessage> get() = channel

    // The server pings every 25 s; the socket itself never times out a read.
    private val sockets = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).pingInterval(30, TimeUnit.SECONDS).build()
    private val images = http.newBuilder().callTimeout(60, TimeUnit.SECONDS).build()

    private val lock = Any()
    private var ws: WebSocket? = null
    @Volatile var connectedURL: String? = null
        private set
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val nextID = AtomicInteger()

    val isConnected: Boolean get() = synchronized(lock) { ws != null }

    /** Connects to the first base URL that accepts our token. Returns server info. */
    suspend fun connect(baseURLs: List<String>): ServerInfo {
        var lastError = RpcError.disconnected()
        for (base in baseURLs) {
            try {
                return open(base)
            } catch (e: RpcError) {
                lastError = e
                // Every address is the same server: none will take the token.
                if (e.code == "unauthorized") break
            }
        }
        throw lastError
    }

    private suspend fun open(base: String): ServerInfo {
        close(null, notify = false)
        val request = request(base, "/v1/ws").build()
        val opened = CompletableDeferred<Unit>()
        val socket = sockets.newWebSocket(request, Listener(base, opened))
        synchronized(lock) { ws = socket }
        try {
            withTimeoutOrNull(10.seconds) { opened.await() } ?: throw RpcError.timeout()
        } catch (e: Throwable) {
            synchronized(lock) { if (ws === socket) ws = null }
            socket.cancel()
            throw e
        }
        if (!isCurrent(socket)) throw RpcError.disconnected()
        try {
            val info: ServerInfo = call("server.info", timeout = 8.seconds)
            connectedURL = base
            return info
        } catch (e: Throwable) {
            close(null, notify = false)
            throw e
        }
    }

    private inner class Listener(val base: String, val opened: CompletableDeferred<Unit>) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (isCurrent(webSocket)) dispatch(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (isCurrent(webSocket)) dispatch(bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            ended(webSocket, if (code == REVOKED_CODE) RpcError.unauthorized() else null, reason.ifEmpty { null })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Unlike a browser, OkHttp shows why an upgrade failed.
            ended(webSocket, if (response?.code == 401) RpcError.unauthorized() else null, t.message)
        }

        private fun ended(webSocket: WebSocket, error: RpcError?, reason: String?) {
            if (!opened.isCompleted) {
                opened.completeExceptionally(error ?: RpcError("unreachable", "Could not connect to $base"))
                return
            }
            if (isCurrent(webSocket)) close(error?.message ?: reason, notify = true)
        }
    }

    private fun isCurrent(socket: WebSocket) = synchronized(lock) { ws === socket }

    fun disconnect() = close("closed by client", notify = true)

    private fun close(reason: String?, notify: Boolean) {
        val socket = synchronized(lock) {
            val s = ws ?: return
            ws = null
            connectedURL = null
            s
        }
        socket.close(1000, null)
        val waiting = pending.values.toList()
        pending.clear()
        for (p in waiting) p.completeExceptionally(RpcError.disconnected())
        if (notify) channel.trySend(ServerMessage.Closed(reason))
    }

    /** Handles one frame from rad. Internal for the fixture tests. */
    internal fun dispatch(text: String) {
        val o = runCatching { WireCoding.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = (o["id"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        val method = (o["method"] as? JsonPrimitive)?.contentOrNull
        if (id != null && method == null) {
            pending.remove(id)?.complete(o)
            return
        }
        val params = o["params"] as? JsonObject ?: return
        val msg = runCatching {
            val stream = params.getValue("stream").jsonPrimitive.content
            when (method) {
                "events" -> ServerMessage.Events(
                    stream,
                    WireCoding.json.decodeFromJsonElement(ListSerializer(Event.serializer()), params["events"] ?: JsonArray(emptyList())),
                )
                "synchronized" -> ServerMessage.Synchronized(
                    stream,
                    params.getValue("seq").jsonPrimitive.long,
                    (params["reset"] as? JsonPrimitive)?.booleanOrNull ?: false,
                )
                "resync" -> ServerMessage.Resync(stream)
                else -> null
            }
        }.getOrNull() ?: return
        channel.trySend(msg)
    }

    /** Calls a method; returns its `result` (`{}` when it has none). */
    suspend fun callRaw(method: String, params: JsonElement = NoParams, timeout: Duration = 30.seconds): JsonElement {
        val socket = synchronized(lock) { ws } ?: throw RpcError.disconnected()
        val id = nextID.incrementAndGet()
        val response = CompletableDeferred<JsonObject>()
        pending[id] = response
        try {
            val body = buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
            }
            if (!socket.send(body.toString())) throw RpcError.disconnected()
            val o = withTimeoutOrNull(timeout) { response.await() } ?: throw RpcError.timeout()
            o["error"]?.takeUnless { it is JsonNull }?.let {
                val e = WireCoding.json.decodeFromJsonElement<ErrorBody>(it)
                throw RpcError(e.code, e.message ?: e.code)
            }
            return o["result"]?.takeUnless { it is JsonNull } ?: NoParams
        } finally {
            pending.remove(id)
        }
    }

    suspend inline fun <reified R> call(method: String, params: JsonElement = NoParams, timeout: Duration = 30.seconds): R =
        WireCoding.json.decodeFromJsonElement(callRaw(method, params, timeout))

    // HTTP, beside the socket.

    /** Uploads an image to attach to a prompt (`POST /v1/images`). */
    suspend fun uploadImage(data: ByteArray): ImageRef {
        val req = request("/v1/images").post(data.toRequestBody("application/octet-stream".toMediaType())).build()
        val body = fetch(req)
        return WireCoding.json.decodeFromString(ImageRef.serializer(), body.decodeToString())
    }

    /** An image's bytes (`GET /v1/images/<id>`). */
    suspend fun imageData(id: String): ByteArray = fetch(request("/v1/images/" + URLEncoder.encode(id, Charsets.UTF_8)).build())

    private suspend fun fetch(req: Request): ByteArray = try {
        images.newCall(req).await().bodyOrThrow()
    } catch (e: IOException) {
        throw RpcError("http", e.message ?: "The request failed")
    }

    /**
     * A socket to a session's shell (`GET /v1/terminal?<query>`) on the server
     * the main socket is connected to.
     */
    fun openTerminal(query: String, listener: WebSocketListener): WebSocket {
        val req = request("/v1/terminal?$query").header("Sec-WebSocket-Protocol", "rad.terminal.v1").build()
        return sockets.newWebSocket(req, listener)
    }

    /** A request to the server the socket is connected to, with our token. */
    private fun request(path: String): Request.Builder = request(connectedURL ?: throw RpcError.disconnected(), path)

    private fun request(base: String, path: String): Request.Builder = try {
        // OkHttp takes http(s) URLs for WebSockets too.
        Request.Builder().url(base + path).header("Authorization", "Bearer $token")
    } catch (_: IllegalArgumentException) {
        throw RpcError("invalid", "$base isn't a server address")
    }
}

/** Params for a call, from pairs of a name and a string, number, boolean, JsonElement or null. */
fun params(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) {
        when (v) {
            null -> {}
            is String -> put(k, v)
            is Number -> put(k, v)
            is Boolean -> put(k, v)
            is JsonElement -> put(k, v)
            else -> throw IllegalArgumentException("unsupported param $k")
        }
    }
}
