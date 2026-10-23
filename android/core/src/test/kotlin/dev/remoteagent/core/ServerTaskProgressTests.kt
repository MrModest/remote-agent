package dev.remoteagent.core

import dev.remoteagent.core.protocol.ServerTask
import dev.remoteagent.core.protocol.WireCoding
import dev.remoteagent.core.store.ConnectionState
import dev.remoteagent.core.store.ImageCache
import dev.remoteagent.core.store.SavedServer
import dev.remoteagent.core.store.ServerConnection
import dev.remoteagent.core.store.ServerTaskProgress
import dev.remoteagent.core.store.ServerTaskProgress.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Polling a server task, and waiting for rad to restart after its update (protocol/PROTOCOL.md, "Managing the server"). */
class ServerTaskProgressTests {
    private val server = MockWebServer().apply { start() }
    private val rad = FakeRad()
    /** rad after it restarted, as the new version. */
    private val next = FakeRad()
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @AfterTest
    fun tearDown() {
        rad.shutDown()
        next.shutDown()
        scope.cancel()
        dispatcher.close()
        server.close()
    }

    private suspend fun connect(): ServerConnection {
        val saved = SavedServer("srv", "Test", listOf(server.base()), "dev1", Instant.now())
        val c = ServerConnection(saved, "tok", scope, ImageCache(null), {})
        c.firstRetryDelay = 10.milliseconds
        withContext(dispatcher) { c.start() }
        withTimeout(5.seconds) { c.state.first { it == ConnectionState.Connected } }
        return c
    }

    private fun task(
        kind: String,
        status: String = "running",
        output: String = "",
        version: String? = null,
        harness: String? = "fake",
    ): JsonObject = JsonObject(
        buildMap<String, JsonElement> {
            put("id", JsonPrimitive("k1"))
            put("kind", JsonPrimitive(kind))
            harness?.let { put("harness", JsonPrimitive(it)) }
            put("status", JsonPrimitive(status))
            put("output", JsonPrimitive(output))
            version?.let { put("version", JsonPrimitive(it)) }
            put("startedAt", JsonPrimitive("2026-10-09T12:00:00Z"))
        },
    )

    private fun decode(o: JsonObject) = WireCoding.json.decodeFromJsonElement(ServerTask.serializer(), o)

    private fun info(version: String) = JsonObject(FakeRad.serverInfo + ("version" to JsonPrimitive(version)))

    private suspend fun progress(c: ServerConnection, task: JsonObject, restartTimeout: Duration = 10.seconds) =
        withContext(dispatcher) { ServerTaskProgress(c, decode(task), pollInterval = 20.milliseconds, restartTimeout = restartTimeout) }

    /** Waits for the harness list to be fetched again, past the one every connection makes. */
    private fun FakeRad.refreshedHarnesses() {
        while (nextCall("harness.list")["params"]?.jsonObject?.get("refresh")?.jsonPrimitive?.booleanOrNull != true) continue
    }

    private suspend fun ServerTaskProgress.until(phase: Phase) = withTimeout(5.seconds) { this@until.phase.first { it == phase } }

    /** Answers rad's way; the rest as FakeRad does. */
    private fun answer(r: FakeRad, version: String = "0.1", calls: (String, JsonObject) -> JsonElement?) {
        r.answer = { _, method, params ->
            when (method) {
                "server.info" -> info(version)
                else -> calls(method, params) ?: JsonObject(emptyMap())
            }
        }
    }

    @Test
    fun pollsUntilTheTaskEndsThenRefreshesTheAgents() = runBlocking {
        val polls = AtomicInteger()
        answer(rad) { method, _ ->
            when (method) {
                "task.get" -> when (polls.incrementAndGet()) {
                    1 -> task(ServerTask.UPDATE_AGENT, output = "Checking…")
                    2 -> task(ServerTask.UPDATE_AGENT, output = "Checking…\nDownloading 100%")
                    else -> task(ServerTask.UPDATE_AGENT, "succeeded", "Checking…\nDownloading 100%\nFake 2.0", version = "2.0")
                }
                else -> null
            }
        }
        server.upgrade(rad)
        val c = connect()
        val p = progress(c, task(ServerTask.UPDATE_AGENT))
        assertEquals(Phase.RUNNING, p.phase.value)
        p.until(Phase.DONE)
        assertEquals("2.0", p.task.value.version)
        assertEquals("Checking…\nDownloading 100%\nFake 2.0", p.task.value.output)
        assertEquals("k1", rad.nextCall("task.get")["params"]!!.jsonObject.getValue("id").jsonPrimitive.content)
        // The agent's version changed: the list is fetched again, not from the cache.
        rad.refreshedHarnesses()
        // Ended: no more polling.
        delay(200)
        assertEquals(3, polls.get())
        withContext(dispatcher) { c.stop() }
    }

    @Test
    fun stopEndsPolling() = runBlocking {
        val polls = AtomicInteger()
        answer(rad) { method, _ -> if (method == "task.get") task(ServerTask.LOGIN).also { polls.incrementAndGet() } else null }
        server.upgrade(rad)
        val c = connect()
        val p = progress(c, task(ServerTask.LOGIN))
        withTimeout(5.seconds) { while (polls.get() < 2) delay(5) }
        withContext(dispatcher) { p.stop() }
        val stopped = polls.get()
        delay(200)
        assertEquals(stopped, polls.get())
        assertEquals(Phase.RUNNING, p.phase.value)
        withContext(dispatcher) { c.stop() }
    }

    @Test
    fun inputAndCancelGoToTheTask() = runBlocking {
        val cancelled = AtomicBoolean(false)
        answer(rad) { method, _ ->
            when (method) {
                "task.cancel" -> {
                    cancelled.set(true)
                    null
                }
                "task.get" -> task(ServerTask.LOGIN, if (cancelled.get()) "cancelled" else "running")
                else -> null
            }
        }
        server.upgrade(rad)
        val c = connect()
        val p = progress(c, task(ServerTask.LOGIN))
        withContext(dispatcher) { p.send("abc123") }
        assertEquals(JsonObject(mapOf("id" to JsonPrimitive("k1"), "text" to JsonPrimitive("abc123"))), rad.nextCall("task.input")["params"])
        withContext(dispatcher) { p.cancel() }
        p.until(Phase.CANCELLED)
        withContext(dispatcher) { c.stop() }
    }

    @Test
    fun aForgottenTaskFails() = runBlocking {
        answer(rad) { method, _ -> if (method == "task.get") FakeRad.error("not_found", "no such task") else null }
        server.upgrade(rad)
        val c = connect()
        val p = progress(c, task(ServerTask.LOGIN))
        p.until(Phase.FAILED)
        assertEquals(ServerTask.Status.FAILED, p.task.value.status)
        assertEquals("rad restarted before the task finished", p.task.value.error)
        rad.refreshedHarnesses()
        withContext(dispatcher) { c.stop() }
    }

    @Test
    fun updateOfRadWaitsForTheNewVersion() = runBlocking {
        answer(rad) { method, _ -> if (method == "task.get") task(ServerTask.UPDATE_SERVER, "succeeded", version = "0.2", harness = null) else null }
        answer(next, version = "0.2") { _, _ -> null }
        server.upgrade(rad)
        server.upgrade(next)
        val c = connect()
        val p = progress(c, task(ServerTask.UPDATE_SERVER, harness = null))
        // In, but rad still runs as 0.1.
        p.until(Phase.RESTARTING)
        delay(100)
        assertEquals(Phase.RESTARTING, p.phase.value)
        // It restarts, and the connection finds it as 0.2.
        rad.socket!!.close(1001, "restarting")
        p.until(Phase.DONE)
        assertEquals("0.2", c.info.value?.version)
        withContext(dispatcher) { c.stop() }
    }

    @Test
    fun updateOfRadForgottenByTheNewVersionSucceeded() = runBlocking {
        // rad restarts before a poll sees the update in, and the new one doesn't know the task.
        answer(rad) { method, _ ->
            if (method == "task.get") {
                rad.socket?.close(1001, "restarting")
                null
            } else {
                null
            }
        }
        answer(next, version = "0.2") { method, _ -> if (method == "task.get") FakeRad.error("not_found", "no such task") else null }
        server.upgrade(rad)
        server.upgrade(next)
        val c = connect()
        val p = progress(c, task(ServerTask.UPDATE_SERVER, harness = null))
        p.until(Phase.DONE)
        assertEquals(ServerTask.Status.SUCCEEDED, p.task.value.status)
        assertEquals("0.2", p.task.value.version)
        withContext(dispatcher) { c.stop() }
    }

    @Test
    fun updateToARadWithoutTasksSucceeded() = runBlocking {
        // The release installed is older than task.get.
        answer(rad) { method, _ ->
            if (method == "task.get") {
                rad.socket?.close(1001, "restarting")
                null
            } else {
                null
            }
        }
        answer(next, version = "0.0.9") { method, _ -> if (method == "task.get") FakeRad.error("method_not_found", "unknown method task.get") else null }
        server.upgrade(rad)
        server.upgrade(next)
        val c = connect()
        val p = progress(c, task(ServerTask.UPDATE_SERVER, harness = null))
        p.until(Phase.DONE)
        assertEquals("0.0.9", p.task.value.version)
        withContext(dispatcher) { c.stop() }
    }

    @Test
    fun updateOfRadForgottenByTheSameVersionFailed() = runBlocking {
        answer(rad) { method, _ ->
            if (method == "task.get") {
                rad.socket?.close(1001, "restarting")
                null
            } else {
                null
            }
        }
        answer(next) { method, _ -> if (method == "task.get") FakeRad.error("not_found", "no such task") else null }
        server.upgrade(rad)
        server.upgrade(next)
        val c = connect()
        val p = progress(c, task(ServerTask.UPDATE_SERVER, harness = null))
        p.until(Phase.FAILED)
        assertEquals("rad restarted before the task finished", p.task.value.error)
        withContext(dispatcher) { c.stop() }
    }

    @Test
    fun updateOfRadFailsWhenRadDoesntComeBack() = runBlocking {
        answer(rad) { method, _ -> if (method == "task.get") task(ServerTask.UPDATE_SERVER, "succeeded", version = "0.2", harness = null) else null }
        server.upgrade(rad)
        val c = connect()
        val seen = mutableListOf<Phase>() // only touched on the dispatcher
        val (p, watching) = withContext(dispatcher) {
            val p = ServerTaskProgress(c, decode(task(ServerTask.UPDATE_SERVER, harness = null)), 20.milliseconds, 300.milliseconds)
            p to scope.launch(start = CoroutineStart.UNDISPATCHED) { p.phase.collect { seen += it } }
        }
        p.until(Phase.FAILED)
        assertEquals("rad hasn't come back as 0.2", p.task.value.error)
        watching.cancel()
        assertEquals(listOf(Phase.RUNNING, Phase.RESTARTING, Phase.FAILED), withContext(dispatcher) { seen.toList() })
        withContext(dispatcher) { c.stop() }
    }
}
