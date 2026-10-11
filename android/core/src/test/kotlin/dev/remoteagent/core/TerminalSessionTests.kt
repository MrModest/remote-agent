package dev.remoteagent.core

import dev.remoteagent.core.store.TerminalHost
import dev.remoteagent.core.store.TerminalSession
import dev.remoteagent.core.store.TerminalSocket
import dev.remoteagent.core.store.TerminalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** As web/src/store/terminalSession.test.ts. */
class TerminalSessionTests {
    /** Stands in for an OkHttp WebSocket. */
    private class FakeSocket(val query: String, val listener: TerminalSocket.Listener) : TerminalSocket {
        val sent = mutableListOf<Any>()
        var closed = false
        override fun send(text: String) {
            sent += text
        }
        override fun send(bytes: ByteArray) {
            sent += bytes
        }
        override fun close() {
            closed = true
        }
        fun text(json: String) = listener.onText(json)
        fun bytes(s: String) = listener.onBinary(s.encodeToByteArray())
        fun drop(code: Int = 1006) = listener.onClosed(code)
    }

    private class Setup(scope: CoroutineScope) {
        val sockets = mutableListOf<FakeSocket>()
        var connected = true
        val out = mutableListOf<String>()
        var resets = 0
        val t = TerminalSession(object : TerminalHost {
            override fun connected() = connected
            override fun open(query: String, listener: TerminalSocket.Listener): TerminalSocket =
                FakeSocket(query, listener).also { sockets += it }
        }, "s1", scope).apply {
            onOutput = { out += it.decodeToString() }
            onReset = { resets++ }
        }
    }

    private val attached = """{"type":"attached","id":"sh","offset":0,"reset":true,"cols":80,"rows":24}"""

    @Test
    fun buildsItsQuery() {
        assertEquals("session=s1&cols=80&rows=24&create=1", TerminalSession.query("s1", 80, 24, true, null, 0))
        assertEquals("session=s1&cols=100&rows=30&id=ab&offset=4096", TerminalSession.query("s1", 100, 30, false, "ab", 4096))
    }

    @Test
    fun clearsOnFreshAttachAndResumesAfterDrop() = runTest {
        val s = Setup(this)
        s.t.start(80, 24, true)
        assertTrue(s.sockets[0].query.contains("create=1"))
        s.sockets[0].text(attached)
        assertEquals(1, s.resets)
        assertEquals(TerminalState.Open, s.t.state.value)
        s.sockets[0].bytes("hello")
        assertEquals(listOf("hello"), s.out)

        s.sockets[0].drop()
        assertEquals(TerminalState.Reconnecting, s.t.state.value)
        advanceTimeBy(1001)
        // A reconnect resumes where the view left off, and never asks for a new shell.
        assertEquals("session=s1&cols=80&rows=24&id=sh&offset=5", s.sockets[1].query)
        s.sockets[1].text("""{"type":"attached","id":"sh","offset":5,"cols":80,"rows":24}""")
        assertEquals(1, s.resets)
        assertEquals(TerminalState.Open, s.t.state.value)
    }

    @Test
    fun stopsAfterExitErrorOrRevokedDevice() = runTest {
        val ends = listOf<(FakeSocket) -> Unit>(
            { it.text("""{"type":"exit","exitCode":3}""") },
            { it.text("""{"type":"error","code":"gone","message":"the shell has ended"}""") },
            { it.drop(4001) },
        )
        for (end in ends) {
            val s = Setup(this)
            s.t.start(80, 24, true)
            s.sockets[0].text(attached)
            end(s.sockets[0])
            advanceTimeBy(30_000)
            assertEquals(1, s.sockets.size)
            assertFalse(s.t.live)
        }
    }

    @Test
    fun chunksInputAndSendsSizeOnceSettled() = runTest {
        val s = Setup(this)
        s.t.start(80, 24, true)
        s.sockets[0].text(attached)
        s.t.send(ByteArray(40 shl 10))
        assertEquals(listOf(16 shl 10, 16 shl 10, 8 shl 10), s.sockets[0].sent.map { (it as ByteArray).size })
        s.t.resize(100, 30)
        s.t.resize(120, 40)
        advanceTimeBy(101)
        runCurrent()
        assertEquals("""{"type":"resize","cols":120,"rows":40}""", s.sockets[0].sent.last())
    }

    @Test
    fun waitsForTheServerConnection() = runTest {
        val s = Setup(this)
        s.connected = false
        s.t.start(80, 24, true)
        assertEquals(0, s.sockets.size)
        s.connected = true
        s.t.connectionChanged(true)
        assertEquals(1, s.sockets.size)
    }

    @Test
    fun reportsGoneAndExitStates() = runTest {
        val s = Setup(this)
        s.t.start(80, 24, true)
        s.sockets[0].text(attached)
        s.sockets[0].text("""{"type":"exit","exitCode":129}""")
        assertEquals(TerminalState.Exited(129), s.t.state.value)
        s.t.restart()
        assertTrue(s.sockets[1].query.contains("create=1"))
        s.sockets[1].text("""{"type":"error","code":"no_dir","message":"The folder is gone"}""")
        assertIs<TerminalState.Failed>(s.t.state.value)
    }
}
