package dev.remoteagent.core

import dev.remoteagent.core.net.PairingLink
import dev.remoteagent.core.protocol.ItemKind
import dev.remoteagent.core.protocol.ItemStatus
import dev.remoteagent.core.protocol.OptionKind
import dev.remoteagent.core.protocol.TurnStatus
import dev.remoteagent.core.protocol.Workspace
import dev.remoteagent.core.store.ConnectionState
import dev.remoteagent.core.store.ImageCache
import dev.remoteagent.core.store.NewSession
import dev.remoteagent.core.store.ServerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Runs against a scratch rad with the fake harness, never the developer's:
 *
 *     RA_PAIR_LINK="$(RAD_HOME=$SCRATCH/radhome server/bin/rad pair --print-url)" RA_PROJECT=$SCRATCH/root/proj \
 *       ./gradlew :core:test --tests '*LiveSmokeTest*' --rerun
 *
 * Skipped without RA_PAIR_LINK.
 */
class LiveSmokeTest {
    @Test
    fun pairsSyncsAndRunsATurn() = runBlocking {
        val linkText = System.getenv("RA_PAIR_LINK")
        assumeTrue(!linkText.isNullOrEmpty(), "set RA_PAIR_LINK to run against a scratch rad")
        val projectPath = assertNotNull(System.getenv("RA_PROJECT"), "set RA_PROJECT to a git repo under the scratch rad's roots")
        val link = assertNotNull(PairingLink.parse(linkText))
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val tokens = MemoryTokenStore()
        val store = ServerStore(MemoryStorage(), tokens, ImageCache(null), scope)
        try {
            val saved = store.pair(link, "LiveSmokeTest")
            assertTrue(tokens.get(saved.id)!!.isNotEmpty())
            val c = withContext(dispatcher) { store.connection(saved).also { it.start() } }
            withTimeout(10.seconds) { c.state.first { it == ConnectionState.Connected } }
            withTimeout(10.seconds) { c.indexSynced.first { it } }
            assertEquals(true, c.info.value?.terminal)
            val fake = withTimeout(30.seconds) { c.harnesses.first { it != null } }!!.first { it.id == "fake" }
            assertTrue(fake.usable)

            val project = withContext(dispatcher) {
                c.projects.value.values.firstOrNull { File(it.path).canonicalPath == File(projectPath).canonicalPath }
                    ?: c.addProject(projectPath)
            }
            val session = withContext(dispatcher) {
                c.createSession(
                    NewSession(
                        projectId = project.id, harness = "fake", mode = fake.defaultMode,
                        workspace = NewSession.WorkspaceParams(Workspace.Kind.ROOT), prompt = "write live-smoke.txt",
                    ),
                )
            }
            val s = withContext(dispatcher) { c.sessionStore(session.id).also { it.activate() } }
            val approval = withTimeout(20.seconds) {
                s.transcript.first { items -> items.any { it.kind == ItemKind.APPROVAL && it.status == ItemStatus.PENDING } }
            }.first { it.kind == ItemKind.APPROVAL }
            val allow = approval.approval!!.options.first { it.kind == OptionKind.ALLOW_ONCE }
            withContext(dispatcher) { s.respond(approval, allow) }
            withTimeout(20.seconds) { s.turns.first { t -> t.values.any { it.status == TurnStatus.COMPLETED } } }
            assertTrue(File(projectPath, "live-smoke.txt").exists())

            // A follow-up, and its echo.
            withContext(dispatcher) { s.send("hello from the JVM") }
            withTimeout(20.seconds) { s.transcript.first { items -> items.any { it.text == "Echo: hello from the JVM" } } }
            assertTrue(s.outbox.value.isEmpty())

            withContext(dispatcher) {
                s.deactivate()
                c.archive(session.id, removeWorktree = false)
                store.remove(saved)
            }
            // Give the unpair call a moment to reach rad.
            Thread.sleep(1500)
        } finally {
            scope.cancel()
            dispatcher.close()
        }
    }
}
