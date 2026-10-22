package dev.remoteagent.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.remoteagent.core.protocol.HarnessInfo
import dev.remoteagent.core.protocol.Project
import dev.remoteagent.core.protocol.Session
import dev.remoteagent.core.protocol.SessionStatus
import dev.remoteagent.core.store.ConnectionState
import dev.remoteagent.core.store.SavedServer
import dev.remoteagent.core.store.ServerConnection
import dev.remoteagent.core.store.activeSessions
import dev.remoteagent.core.store.archivedSessions
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * How a tablet lays out sessions beside the open one: chosen in Settings.
 * Mirrors apple/Shared/Views/SidebarLayout.swift.
 */
enum class SidebarLayout(val stored: String, val title: String, val summary: String) {
    /** Every server's sessions by state: what needs you, what runs, the rest. */
    INBOX("inbox", "Inbox", "Sessions from every server, those that need you first."),
    /** Each server's projects, folding open to their sessions. */
    PROJECTS("projects", "Projects", "Each server's projects, with their sessions under them."),
    /** Mail's three columns: collections, their sessions, the transcript. */
    COLUMNS("columns", "Columns", "Needs You, Running and each project in the sidebar, their sessions beside it.");

    companion object {
        const val KEY = "sidebarLayout"

        fun parse(stored: String) = entries.firstOrNull { it.stored == stored } ?: INBOX
    }
}

/**
 * What a sidebar's rows and menus ask of the split view, which shows the
 * dialogs for every layout.
 */
class SessionActions(
    val newSession: (ServerConnection) -> Unit = {},
    val archive: (SessionRequest) -> Unit = {},
    val delete: (SessionRequest) -> Unit = {},
    val remove: (SavedServer) -> Unit = {},
)

val LocalSessionActions = staticCompositionLocalOf { SessionActions() }

/** A server's connection and what the sidebars show of it, as state. */
class ServerState(
    val connection: ServerConnection,
    val server: SavedServer,
    val state: ConnectionState,
    val indexSynced: Boolean,
    val sessions: Map<String, Session>,
    val projects: Map<String, Project>,
    val harnesses: List<HarnessInfo>?,
) {
    val id get() = server.id
    val name get() = server.name
    val active: List<Session> by lazy { sessions.activeSessions }
    val archived: List<Session> by lazy { sessions.archivedSessions }
    /** The connection gave up: it waits for a tap to try again. */
    val offline get() = state is ConnectionState.Failed

    fun harness(id: String): HarnessInfo? = harnesses?.firstOrNull { it.id == id }
}

/** Every paired server, as state that follows its connection. */
@Composable
fun serverStates(): List<ServerState> {
    val store = LocalStore.current
    val servers by store.servers.collectAsStateWithLifecycle()
    return servers.map { server -> key(server.id) { serverState(store.connection(server)) } }
}

@Composable
private fun serverState(connection: ServerConnection): ServerState {
    val server by connection.server.collectAsStateWithLifecycle()
    val state by connection.state.collectAsStateWithLifecycle()
    val indexSynced by connection.indexSynced.collectAsStateWithLifecycle()
    val sessions by connection.sessions.collectAsStateWithLifecycle()
    val projects by connection.projects.collectAsStateWithLifecycle()
    val harnesses by connection.harnesses.collectAsStateWithLifecycle()
    return ServerState(connection, server, state, indexSynced, sessions, projects, harnesses)
}

/** A session and the server it is on, for the lists that mix servers. */
class ServerSession(val server: ServerState, val session: Session) {
    val key = SessionKey(server.id, session.id)
    /** For the lists' keys. */
    val id = "${server.id}/${session.id}"
    val project: Project? get() = server.projects[session.projectId]
}

/** Waiting for an answer, or failed: what a person has to look at. */
val Session.needsYou: Boolean
    get() = status == SessionStatus.AWAITING_APPROVAL || status == SessionStatus.ERROR

/** Working, or done but with tasks left running in the background. */
val Session.isBusy: Boolean
    get() = status == SessionStatus.RUNNING || (status == SessionStatus.IDLE && backgroundTasks.isNotEmpty())

/** Every server's sessions, the latest first. */
fun List<ServerState>.sessions(archived: Boolean): List<ServerSession> =
    flatMap { s -> (if (archived) s.archived else s.active).map { ServerSession(s, it) } }
        .sortedByDescending { it.session.updatedAt }

/** Tries again where a connection gave up, and asks the others for their agents. */
suspend fun refreshConnections(servers: List<ServerState>) = coroutineScope {
    for (s in servers) launch {
        if (s.state == ConnectionState.Connected) s.connection.refreshHarnesses(force = true) else s.connection.retry()
    }
}

/** The Inbox's groups, the latest first in each: what needs you, what is working, the rest. */
class InboxGroups(val needsYou: List<ServerSession>, val running: List<ServerSession>, val recent: List<ServerSession>)

fun inboxGroups(active: List<ServerSession>): InboxGroups {
    val (needsYou, rest) = active.partition { it.session.needsYou }
    val (running, recent) = rest.partition { it.session.isBusy }
    return InboxGroups(needsYou, running, recent)
}

/** A project in the Projects layout, with its sessions. */
class ProjectGroup(val projectId: String, val name: String, val items: List<ServerSession>)

/**
 * The server's projects with sessions, the one worked in last first; in
 * each, what needs you, then the latest.
 */
fun projectGroups(server: ServerState): List<ProjectGroup> =
    // groupBy keeps the order each project first appears in: its latest session's.
    server.active.groupBy { it.projectId }.map { (id, sessions) ->
        val items = sessions.map { ServerSession(server, it) }
        ProjectGroup(id, server.projects[id]?.name ?: "Project", items.filter { it.session.needsYou } + items.filter { !it.session.needsYou })
    }

/** Ids separated by spaces, as the sidebars keep what they hide: [id] added or taken out. */
fun toggled(stored: String, id: String, present: Boolean): String {
    val ids = stored.split(' ').filter { it.isNotEmpty() && it != id }
    return (if (present) ids + id else ids).joinToString(" ")
}

/** What the Columns layout's sidebar shows the sessions of. */
sealed interface SessionCollection {
    data object NeedsYou : SessionCollection
    data object Running : SessionCollection
    data object All : SessionCollection
    data object Archived : SessionCollection
    data class Project(val serverId: String, val projectId: String) : SessionCollection

    /** As stored: "needsYou", or "project <server id> <project id>". */
    val stored: String
        get() = when (this) {
            NeedsYou -> "needsYou"
            Running -> "running"
            All -> "all"
            Archived -> "archived"
            is Project -> "project $serverId $projectId"
        }

    fun sessions(servers: List<ServerState>): List<ServerSession> {
        val active by lazy { servers.sessions(archived = false) }
        return when (this) {
            NeedsYou -> active.filter { it.session.needsYou }
            Running -> active.filter { it.session.isBusy }
            // What needs you first, as in the other layouts.
            All -> active.filter { it.session.needsYou } + active.filter { !it.session.needsYou }
            Archived -> servers.sessions(archived = true)
            is Project -> active.filter { it.server.id == serverId && it.session.projectId == projectId }
        }
    }

    fun title(servers: List<ServerState>): String = when (this) {
        NeedsYou -> "Needs You"
        Running -> "Running"
        All -> "All Sessions"
        Archived -> "Archived"
        is Project -> servers.firstOrNull { it.id == serverId }?.projects?.get(projectId)?.name ?: "Project"
    }

    companion object {
        fun parse(stored: String): SessionCollection? {
            val parts = stored.split(' ').filter { it.isNotEmpty() }
            return when (parts.firstOrNull()) {
                "needsYou" -> NeedsYou
                "running" -> Running
                "all" -> All
                "archived" -> Archived
                "project" -> if (parts.size == 3) Project(parts[1], parts[2]) else null
                else -> null
            }
        }
    }
}
