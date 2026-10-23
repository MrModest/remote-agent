package dev.remoteagent.core.store

import dev.remoteagent.core.net.RpcError
import dev.remoteagent.core.protocol.ServerInfo
import dev.remoteagent.core.protocol.ServerTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * One task on the server as it goes, from the task its start call returned:
 * it polls `task.get` while the task runs, and once an update of rad is in,
 * waits for rad to come back as the new version. When an agent's update or
 * sign-in ends, it refreshes the harness list, for their versions and
 * sign-in states. Mirrors apple/RAKit/Sources/RAKit/Store/ServerTaskProgress.swift.
 *
 * It starts at once, on the connection's scope, where its state changes.
 * `stop()` when the view showing it goes: polling must not outlive the screen.
 */
class ServerTaskProgress(
    private val connection: ServerConnection,
    task: ServerTask,
    private val pollInterval: Duration = 1.seconds,
    private val restartTimeout: Duration = 2.minutes,
) {
    enum class Phase {
        RUNNING,
        /** An update of rad is in, and rad hasn't come back as its version yet. */
        RESTARTING,
        DONE,
        FAILED,
        CANCELLED;

        val ended: Boolean get() = this == DONE || this == FAILED || this == CANCELLED
    }

    val id: String = task.id
    /** What the task does, and to which agent: these don't change as it goes. */
    val kind: String = task.kind
    val harness: String? = task.harness

    /** The version rad ran when this began, to tell that it restarted as another. */
    private val startVersion = connection.info.value?.version
    private val _task = MutableStateFlow(task)
    /** The latest the server said, or what this made of it going missing. */
    val task: StateFlow<ServerTask> = _task.asStateFlow()
    private val _phase = MutableStateFlow(phaseOf(task, connection.info.value))
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Cuts the wait between polls short, after input or a cancel. */
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = connection.scope.launch { run(watched = task.running) }

    /** Stops polling and waiting; the task goes on on the server. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Types a line into the task's command, such as the code a sign-in page gave. */
    suspend fun send(text: String) {
        connection.taskInput(id, text)
        wake.trySend(Unit)
    }

    /** Cancels the task on the server; the next poll shows it cancelled. */
    suspend fun cancel() {
        connection.cancelTask(id)
        wake.trySend(Unit)
    }

    private suspend fun run(watched: Boolean) {
        poll()
        val t = _task.value
        when (kind) {
            ServerTask.UPDATE_SERVER -> if (t.status == ServerTask.Status.SUCCEEDED && _phase.value == Phase.RESTARTING) awaitRestart()
            // Only once it ended under watch: one that had ended changed nothing since.
            ServerTask.UPDATE_AGENT, ServerTask.LOGIN -> if (watched) connection.refreshHarnesses(force = true)
        }
    }

    private suspend fun poll() {
        while (_task.value.running) {
            withTimeoutOrNull(pollInterval) { wake.receive() }
            // A disconnect doesn't end it: wait for the connection, and go on.
            connection.state.first { it == ConnectionState.Connected }
            try {
                set(connection.task(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: RpcError) {
                // A rad that restarted forgot it; one older than tasks (an update to an older release) never knew it.
                if (e.code == "not_found" || e.code == "method_not_found") {
                    forgotten()
                    return
                }
                // Disconnected or timed out: try again.
            } catch (_: Exception) {
                // An answer this app can't read: try again.
            }
        }
    }

    /**
     * rad forgot the task, as it does when it restarts. An update of rad that
     * restarted as another version got in; anything else ended with it.
     */
    private fun forgotten() {
        val t = _task.value
        val now = connection.info.value?.version
        if (kind == ServerTask.UPDATE_SERVER && now != null && now != startVersion) {
            set(t.copy(status = ServerTask.Status.SUCCEEDED, version = t.version ?: now))
        } else {
            set(t.copy(status = ServerTask.Status.FAILED, error = "rad restarted before the task finished"))
        }
    }

    /**
     * Waits for the connection to find rad as the new version: server.info
     * comes with every reconnect. The connection gives up after a few tries,
     * sooner than the Server app may take to update itself and open again,
     * so this has it try again meanwhile.
     */
    private suspend fun awaitRestart() {
        val came = withTimeoutOrNull(restartTimeout) {
            coroutineScope {
                val retrying = launch {
                    connection.state.collect {
                        if (it is ConnectionState.Failed) {
                            delay(RETRY_DELAY)
                            connection.retry()
                        }
                    }
                }
                connection.info.first { cameBack(_task.value, it) }
                retrying.cancel()
            }
        }
        val t = _task.value
        if (came == null) set(t.copy(status = ServerTask.Status.FAILED, error = "rad hasn't come back as ${t.version}")) else set(t)
    }

    private fun set(t: ServerTask) {
        _task.value = t
        _phase.value = phaseOf(t, connection.info.value)
    }

    private fun phaseOf(t: ServerTask, info: ServerInfo?): Phase = when (t.status) {
        ServerTask.Status.RUNNING -> Phase.RUNNING
        ServerTask.Status.SUCCEEDED -> if (t.kind == ServerTask.UPDATE_SERVER && !cameBack(t, info)) Phase.RESTARTING else Phase.DONE
        ServerTask.Status.CANCELLED -> Phase.CANCELLED
        else -> Phase.FAILED
    }

    /** rad runs as the version the update installed (or, not knowing it, another than before). */
    private fun cameBack(t: ServerTask, info: ServerInfo?): Boolean {
        val now = info?.version ?: return false
        return t.version?.let { now == it } ?: (now != startVersion)
    }

    private companion object {
        val RETRY_DELAY = 2.seconds
    }
}
