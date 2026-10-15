package dev.remoteagent.core

import dev.remoteagent.core.net.ServerMessage
import dev.remoteagent.core.protocol.Event
import dev.remoteagent.core.protocol.Session
import dev.remoteagent.core.protocol.SessionStatus
import dev.remoteagent.core.protocol.Workspace
import dev.remoteagent.core.store.ImageCache
import dev.remoteagent.core.store.SavedServer
import dev.remoteagent.core.store.ServerConnection
import dev.remoteagent.core.store.SessionAlert
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun session(id: String, status: SessionStatus, archived: Boolean = false, error: String? = null, seq: Long = 1) = Session(
    id = id, projectId = "p", harness = "fake", workspace = Workspace(Workspace.Kind.ROOT, "/tmp/p"), status = status, title = id,
    error = error, archived = archived, createdAt = Instant.parse("2026-10-09T12:00:00Z"),
    updatedAt = Instant.parse("2026-10-09T12:00:%02dZ".format(seq % 60)),
)

private fun event(seq: Long, id: String, status: SessionStatus, error: String? = null) =
    Event(stream = "index", seq = seq, type = "session.upserted", session = session(id, status, error = error, seq = seq))

/** As SessionAlertTests in apple/RAKit/Tests/RAKitTests/StoreTests.swift and web/src/store/sessionAlert.test.ts. */
class SessionAlertTests {
    private fun alert(from: SessionStatus, to: SessionStatus, archived: Boolean = false) = SessionAlert.between(
        session("s", from),
        session("s", to, archived, if (to == SessionStatus.ERROR) "boom" else null, seq = 2),
    )

    @Test
    fun fromStatusChanges() {
        assertNull(alert(SessionStatus.IDLE, SessionStatus.RUNNING))
        assertEquals(SessionAlert.NeedsYou, alert(SessionStatus.RUNNING, SessionStatus.AWAITING_APPROVAL))
        assertEquals(SessionAlert.NeedsYou, alert(SessionStatus.IDLE, SessionStatus.AWAITING_APPROVAL))
        assertNull(alert(SessionStatus.AWAITING_APPROVAL, SessionStatus.RUNNING))
        assertEquals(SessionAlert.Finished, alert(SessionStatus.RUNNING, SessionStatus.IDLE))
        // Answered elsewhere and done, or interrupted: nothing to tell.
        assertNull(alert(SessionStatus.AWAITING_APPROVAL, SessionStatus.IDLE))
        assertEquals(SessionAlert.Failed("boom"), alert(SessionStatus.RUNNING, SessionStatus.ERROR))
        assertEquals(SessionAlert.Failed("boom"), alert(SessionStatus.IDLE, SessionStatus.ERROR))
        assertNull(alert(SessionStatus.RUNNING, SessionStatus.RUNNING))
        assertNull(alert(SessionStatus.RUNNING, SessionStatus.IDLE, archived = true))
        assertNull(alert(SessionStatus.RUNNING, SessionStatus.STOPPED))
    }
}

/** As IndexChangeTests in apple/RAKit/Tests/RAKitTests/StoreTests.swift and web/src/store/sessionAlert.test.ts. */
class IndexChangeTests {
    private val server = SavedServer("srv", "Test", listOf("http://127.0.0.1:1"), "d", Instant.now())

    private fun TestScope.connection(): Pair<ServerConnection, List<String>> {
        val c = ServerConnection(server, "", backgroundScope, ImageCache(null), {}, storage = MemoryStorage())
        val seen = mutableListOf<String>()
        c.onSessionChange = { old, new ->
            // Reported once the batch is applied.
            assertEquals(new, c.sessions.value[new.id])
            SessionAlert.between(old, new)?.let { seen += "${new.id} $it" }
        }
        return c to seen
    }

    @Test
    fun notForTheFirstSync() = runTest {
        val (c, seen) = connection()
        c.handle(ServerMessage.Events("index", listOf(event(3, "a", SessionStatus.RUNNING))))
        c.handle(ServerMessage.Synchronized("index", 3, reset = true))
        assertEquals(emptyList(), seen)
        c.handle(ServerMessage.Events("index", listOf(event(4, "a", SessionStatus.AWAITING_APPROVAL), event(5, "b", SessionStatus.RUNNING))))
        assertEquals(listOf("a NeedsYou"), seen)
    }

    @Test
    fun whileAway() = runTest {
        val (c, seen) = connection()
        c.handle(ServerMessage.Events("index", listOf(event(2, "a", SessionStatus.RUNNING), event(3, "b", SessionStatus.RUNNING))))
        c.handle(ServerMessage.Synchronized("index", 3, reset = true))
        // Back after a sleep: the replay holds what changed meanwhile.
        c.handle(ServerMessage.Closed(null))
        c.handle(ServerMessage.Events("index", listOf(event(7, "a", SessionStatus.IDLE))))
        c.handle(ServerMessage.Synchronized("index", 7, reset = false))
        assertEquals(listOf("a Finished"), seen)
        // The server lost its place: a reset, compared with what was cached.
        c.handle(ServerMessage.Closed(null))
        c.handle(ServerMessage.Events("index", listOf(event(1, "a", SessionStatus.IDLE), event(2, "b", SessionStatus.ERROR, "boom"))))
        c.handle(ServerMessage.Synchronized("index", 2, reset = true))
        assertEquals(listOf("a Finished", "b Failed(error=boom)"), seen)
    }

    @Test
    fun onceForARepeatedReplay() = runTest {
        val (c, seen) = connection()
        c.handle(ServerMessage.Events("index", listOf(event(2, "a", SessionStatus.RUNNING))))
        c.handle(ServerMessage.Synchronized("index", 2, reset = true))
        c.handle(ServerMessage.Closed(null))
        val replay = event(5, "a", SessionStatus.AWAITING_APPROVAL)
        c.handle(ServerMessage.Events("index", listOf(replay, replay)))
        c.handle(ServerMessage.Synchronized("index", 5, reset = false))
        assertEquals(listOf("a NeedsYou"), seen)
    }
}
