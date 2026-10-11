package dev.remoteagent.core

import dev.remoteagent.core.FakeRad.Companion.events
import dev.remoteagent.core.FakeRad.Companion.project
import dev.remoteagent.core.FakeRad.Companion.session
import dev.remoteagent.core.FakeRad.Companion.upserted
import dev.remoteagent.core.net.RpcError
import dev.remoteagent.core.store.ConnectionState
import dev.remoteagent.core.store.ImageCache
import dev.remoteagent.core.store.SavedServer
import dev.remoteagent.core.store.ServerConnection
import dev.remoteagent.core.store.activeSessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/** The index stream's sync rules (protocol/PROTOCOL.md, "Streams and sync") against a fake rad. */
class ServerConnectionTests {
    private val server = MockWebServer().apply { start() }
    private val rad = FakeRad()
    // The app changes a connection's state on the main thread; here, on this one.
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @AfterTest
    fun tearDown() {
        rad.shutDown()
        scope.cancel()
        dispatcher.close()
        server.close()
    }

    private suspend fun connect(): ServerConnection {
        val saved = SavedServer("srv", "Test", listOf(server.base()), "dev1", Instant.now())
        val c = ServerConnection(saved, "tok", scope, ImageCache(null), {})
        withContext(dispatcher) { c.start() }
        return c
    }

    private fun synchronized(seq: Long, reset: Boolean) = buildJsonObject {
        put("stream", "index")
        put("seq", seq)
        put("reset", reset)
    }

    private fun subscribed(seq: Long) = buildJsonObject {
        put("stream", "index")
        put("seq", seq)
    }

    @Test
    fun replaysThenAppliesLiveEventsAndResubscribesOnGaps() = runBlocking {
        rad.answer = { r, method, params ->
            when (method) {
                "server.info" -> FakeRad.serverInfo
                "subscribe" -> {
                    val after = params.getValue("afterSeq").jsonPrimitive.long
                    if (after == 0L) {
                        // The replay isn't contiguous: older versions were compacted away.
                        r.notify("events", events("index", 3L to upserted("project", project("p1")), 5L to upserted("session", session("s1"))))
                        r.notify("synchronized", synchronized(5, reset = true))
                        subscribed(5)
                    } else {
                        r.notify("synchronized", synchronized(after, reset = false))
                        subscribed(after)
                    }
                }
                else -> JsonObject(emptyMap())
            }
        }
        server.upgrade(rad)
        val c = connect()
        withTimeout(5.seconds) { c.indexSynced.first { it } }
        assertEquals(ConnectionState.Connected, c.state.value)
        assertEquals(setOf("p1"), c.projects.value.keys)
        assertEquals(setOf("s1"), c.sessions.value.keys)
        rad.received.clear()

        // Live events are contiguous.
        rad.notify("events", events("index", 6L to upserted("session", session("s2", updatedAt = "2026-10-09T13:00:00Z"))))
        withTimeout(5.seconds) { c.sessions.first { "s2" in it } }
        assertEquals(listOf("s2", "s1"), c.sessions.value.activeSessions.map { it.id })

        // A gap: resubscribe from the last applied seq, and skip what came after it.
        rad.notify("events", events("index", 9L to upserted("session", session("s3"))))
        assertEquals(6, rad.nextCall("subscribe")["params"]!!.let { (it as JsonObject).getValue("afterSeq").jsonPrimitive.long })
        assertFalse("s3" in c.sessions.value)

        // The server says this client fell behind.
        rad.notify("resync", buildJsonObject { put("stream", "index") })
        assertEquals(6, (rad.nextCall("subscribe")["params"] as JsonObject).getValue("afterSeq").jsonPrimitive.long)

        // The socket drops: reconnect, and ask only for what changed since.
        server.upgrade(rad)
        rad.socket!!.close(1001, "going away")
        rad.nextCall("server.info")
        assertEquals(6, (rad.nextCall("subscribe")["params"] as JsonObject).getValue("afterSeq").jsonPrimitive.long)
        withTimeout(5.seconds) { c.state.first { it == ConnectionState.Connected } }
        withContext(dispatcher) { c.stop() }
    }

    @Test
    fun revokedDeviceFails() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("""{"code":"unauthorized","message":"bad token"}""").build())
        val c = connect()
        val failed = withTimeout(5.seconds) { c.state.first { it is ConnectionState.Failed } }
        assertEquals(ConnectionState.Failed(RpcError.unauthorized().message), failed)
        withContext(dispatcher) { c.stop() }
    }
}
