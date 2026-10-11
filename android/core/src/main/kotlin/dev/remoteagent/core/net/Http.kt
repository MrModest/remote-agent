package dev.remoteagent.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** The HTTP client every connection shares, and so its connection pool. */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

/** A request's response, without blocking a thread. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { cancel() }
}

/** The body of a 200 response, else the server's error. */
internal suspend fun Response.bodyOrThrow(): ByteArray = use {
    val bytes = withContext(Dispatchers.IO) { body.bytes() }
    if (code == 200) return bytes
    throw errorBody(bytes) ?: RpcError("http", "The server answered $code")
}

@Serializable
internal data class ErrorBody(val code: String, val message: String? = null)

/** An error as rad writes it, `{"code": …, "message": …}`. */
internal fun errorBody(bytes: ByteArray): RpcError? = runCatching {
    val e = dev.remoteagent.core.protocol.WireCoding.json.decodeFromString(ErrorBody.serializer(), bytes.decodeToString())
    RpcError(e.code, e.message ?: e.code)
}.getOrNull()
