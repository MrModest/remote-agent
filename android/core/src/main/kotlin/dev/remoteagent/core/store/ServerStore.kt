@file:UseSerializers(InstantSerializer::class)

package dev.remoteagent.core.store

import dev.remoteagent.core.net.Http
import dev.remoteagent.core.net.Pairing
import dev.remoteagent.core.net.PairingLink
import dev.remoteagent.core.protocol.InstantSerializer
import dev.remoteagent.core.protocol.WireCoding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import java.time.Instant

@Serializable
data class SavedServer(
    /** The server's id. */
    val id: String,
    val name: String,
    /** Preference order; the last one that worked comes first. */
    val urls: List<String>,
    val deviceId: String,
    val addedAt: Instant,
)

/**
 * The list of paired servers. Tokens are kept in `tokens`.
 * Mirrors apple/RAKit/Sources/RAKit/Store/ServerStore.swift.
 */
class ServerStore(
    private val storage: Storage,
    private val tokens: TokenStore,
    private val imageCache: ImageCache,
    private val scope: CoroutineScope,
    private val http: OkHttpClient = Http.client,
) {
    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<SavedServer>> = _servers.asStateFlow()
    private val connections = HashMap<String, ServerConnection>()

    private fun load(): List<SavedServer> = storage[KEY]?.let {
        runCatching { WireCoding.json.decodeFromString(ListSerializer(SavedServer.serializer()), it) }.getOrNull()
    } ?: emptyList()

    private fun save(list: List<SavedServer>) {
        _servers.value = list
        storage[KEY] = WireCoding.json.encodeToString(ListSerializer(SavedServer.serializer()), list)
    }

    fun server(id: String): SavedServer? = _servers.value.firstOrNull { it.id == id }

    fun connection(server: SavedServer): ServerConnection = connections.getOrPut(server.id) {
        ServerConnection(server, tokens.get(server.id) ?: "", scope, imageCache, { url -> promote(url, server.id) }, http, storage)
    }

    /** Pairs with a server from a pairing link and saves it. */
    suspend fun pair(link: PairingLink, deviceName: String): SavedServer {
        val (res, worked) = Pairing.pair(link, deviceName, http)
        val urls = listOf(worked) + link.urls.filter { it != worked }
        val s = SavedServer(res.serverId, res.name, urls, res.deviceId, Instant.now())
        tokens.set(s.id, res.token)
        connections.remove(s.id)?.stop()
        save(_servers.value.filter { it.id != s.id } + s)
        return s
    }

    /**
     * Forgets a server and the drafts written for it. Also asks the server to
     * revoke this device's token (best effort, in the background) so it can't
     * be reused.
     */
    fun remove(server: SavedServer) {
        val c = connection(server)
        c.unpairAndStop()
        c.drafts.removeAll()
        connections.remove(server.id)
        tokens.delete(server.id)
        save(_servers.value.filter { it.id != server.id })
    }

    fun rename(server: SavedServer, name: String) {
        val list = _servers.value.map { if (it.id == server.id) it.copy(name = name) else it }
        save(list)
        list.firstOrNull { it.id == server.id }?.let { connections[it.id]?.update(it) }
    }

    private fun promote(url: String, id: String) {
        val s = _servers.value.firstOrNull { it.id == id } ?: return
        if (s.urls.firstOrNull() == url) return
        val updated = s.copy(urls = listOf(url) + s.urls.filter { it != url })
        save(_servers.value.map { if (it.id == id) updated else it })
        connections[id]?.update(updated)
    }

    /** Pause all connections (app in the background) or resume the ones in use. */
    fun setActive(active: Boolean) {
        for (c in connections.values) if (active) c.resumeIfWanted() else c.pause()
    }

    private companion object {
        const val KEY = "servers.v1"
    }
}
