package dev.remoteagent.core.net

import dev.remoteagent.core.protocol.WireCoding
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * A parsed `remoteagent://pair?...` link from `rad pair`. Mirrors
 * apple/RAKit/Sources/RAKit/Net/Pairing.swift, with web/src/protocol/pairing.ts's
 * stricter addresses: `http(s)://host[:port]` without a path.
 */
data class PairingLink(val name: String, val code: String, val urls: List<String>) {
    /** The link again, as `rad pair` writes it. */
    fun toLinkString(): String {
        fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
        val query = listOf("name" to name, "code" to code) + urls.map { "url" to it }
        return "remoteagent://pair?" + query.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
    }

    companion object {
        private val address = Regex("^https?://[^/]+$")

        fun parse(text: String): PairingLink? {
            val s = text.trim()
            if (!s.startsWith("remoteagent://pair")) return null
            val q = s.indexOf('?')
            if (q < 0) return null
            val params = s.substring(q + 1).split('&').filter { it.isNotEmpty() }.map {
                val eq = it.indexOf('=')
                val key = if (eq < 0) it else it.substring(0, eq)
                val value = if (eq < 0) "" else it.substring(eq + 1)
                decode(key) to decode(value)
            }
            val code = params.firstOrNull { it.first == "code" }?.second?.ifEmpty { null } ?: return null
            val urls = params.filter { it.first == "url" }.map { it.second.trimEnd('/') }.filter { address.matches(it) }
            if (urls.isEmpty()) return null
            val name = params.firstOrNull { it.first == "name" }?.second?.ifEmpty { null } ?: "Server"
            return PairingLink(name, code, urls)
        }

        private fun decode(s: String) = runCatching { URLDecoder.decode(s, Charsets.UTF_8) }.getOrDefault(s)
    }
}

@Serializable
data class PairResult(
    val serverId: String,
    val name: String,
    val protocolVersion: Int,
    val version: String,
    val token: String,
    val deviceId: String,
)

object Pairing {
    /**
     * Redeems the pairing code against each URL in order. Returns the result
     * and the URL that worked.
     */
    suspend fun pair(link: PairingLink, deviceName: String, http: OkHttpClient = Http.client): Pair<PairResult, String> {
        val client = http.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
        val body = buildJsonObject {
            put("code", link.code)
            put("deviceName", deviceName)
        }.toString().toRequestBody("application/json".toMediaType())
        var lastError = RpcError("unreachable", "No server address was reachable")
        for (base in link.urls) {
            val response = try {
                client.newCall(Request.Builder().url("$base/v1/pair").post(body).build()).await()
            } catch (_: IOException) {
                lastError = RpcError("unreachable", "Could not reach ${link.urls.joinToString(" or ")}. Check that rad is running.")
                continue
            } catch (_: IllegalArgumentException) {
                continue
            }
            try {
                val bytes = response.bodyOrThrow()
                return WireCoding.json.decodeFromString(PairResult.serializer(), bytes.decodeToString()) to base
            } catch (e: RpcError) {
                // The server answered; trying other URLs won't help.
                if (e.code == "unauthorized") throw RpcError(e.code, "The pairing code is wrong or has expired. Run `rad pair` again.")
                if (e.code != "http") throw e
                lastError = e
            }
        }
        throw lastError
    }
}
