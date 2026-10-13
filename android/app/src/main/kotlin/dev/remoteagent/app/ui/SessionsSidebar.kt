package dev.remoteagent.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.remoteagent.app.platform.AppSettings
import dev.remoteagent.app.platform.state
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
 * The tablet's list pane: every paired server, each with its sessions. A tap
 * on a server's name shows or hides them, or, once the connection has given
 * up, tries again. Mirrors apple/Shared/Views/SessionsSidebar.swift.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsSidebar(
    selection: SessionKey?,
    onOpenSession: (SessionKey) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onRemoved: (SavedServer) -> Unit,
) {
    val store = LocalStore.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val servers by store.servers.collectAsStateWithLifecycle()
    // The servers whose sessions are hidden, by id, separated by spaces.
    val collapsedIDs by settings.state(AppSettings.Keys.collapsedServers, "")
    val collapsed = remember(collapsedIDs) { collapsedIDs.split(' ').filter { it.isNotEmpty() } }
    // The servers shown with their archived sessions.
    var showArchived by rememberSaveable { mutableStateOf(listOf<String>()) }
    var newSessionOn by rememberSaveable { mutableStateOf<String?>(null) }
    var newMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var archiving by remember { mutableStateOf<ArchiveRequest?>(null) }
    var removing by remember { mutableStateOf<SavedServer?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    val sections = servers.map { server -> key(server.id) { sectionState(store.connection(server)) } }
    val connected = sections.filter { it.state == ConnectionState.Connected }

    fun setExpanded(id: String, shown: Boolean) {
        val ids = collapsed.filter { it != id } + if (shown) emptyList() else listOf(id)
        // Forgets servers that were removed.
        settings.put(AppSettings.Keys.collapsedServers, ids.filter { c -> servers.any { it.id == c } }.joinToString(" "))
    }

    // Every server connects; one that was given up on waits for a tap. Keyed
    // on the connections, since pairing a server again replaces its own.
    val connections = sections.map { it.connection }
    LaunchedEffect(connections) {
        for (c in connections) if (c.state.value == ConnectionState.Idle) c.start()
    }
    // Landing on an archived session, such as one opened before the window
    // widened, shows its row.
    val selectionArchived = selection != null &&
        sections.firstOrNull { it.id == selection.serverId }?.sessions?.get(selection.sessionId)?.archived == true
    LaunchedEffect(selectionArchived, selection?.serverId) {
        val id = selection?.serverId
        if (selectionArchived && id != null && id !in showArchived) showArchived = showArchived + id
    }

    Screen(
        title = { Text("Sessions", style = Type.title) },
        background = palette.mantle,
        actions = {
            Box {
                IconButton(
                    { if (connected.size == 1) newSessionOn = connected[0].id else newMenu = true },
                    enabled = connected.isNotEmpty(),
                    modifier = Modifier.testTag("new-session"),
                ) {
                    Icon(painterResource(Icons.newSession), "New session")
                }
                DropdownMenu(newMenu, { newMenu = false }, containerColor = palette.mantle) {
                    for (s in connected) MenuItem("On ${s.name}", Icons.server) { newMenu = false; newSessionOn = s.id }
                }
            }
            // In a menu, as on iPad: a + here would read as a new session, as each server's does.
            Box {
                IconButton({ moreMenu = true }) { Icon(painterResource(Icons.more), "More") }
                DropdownMenu(moreMenu, { moreMenu = false }, containerColor = palette.mantle) {
                    MenuItem("Pair a server", Icons.add) { moreMenu = false; onAdd() }
                    MenuItem("Settings", Icons.settings) { moreMenu = false; onSettings() }
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    coroutineScope {
                        for (s in sections) launch {
                            if (s.state == ConnectionState.Connected) s.connection.refreshHarnesses(force = true) else s.connection.retry()
                        }
                    }
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                sections.forEachIndexed { i, s ->
                    serverSection(
                        s,
                        first = i == 0,
                        expanded = s.id !in collapsed,
                        showArchived = s.id in showArchived,
                        selection = selection?.takeIf { it.serverId == s.id }?.sessionId,
                        onExpand = { setExpanded(s.id, it) },
                        onShowArchived = { shown -> showArchived = if (shown) showArchived + s.id else showArchived - s.id },
                        onOpen = { id -> onOpenSession(SessionKey(s.id, id)) },
                        onNewSession = { newSessionOn = s.id },
                        onArchive = { session -> archiving = ArchiveRequest(session, s.connection) },
                        onRemove = { removing = s.server },
                    )
                }
            }
        }
    }

    ArchiveSessionDialog(archiving, onDismiss = { archiving = null })
    val removed = removing
    RemoveServerDialog(removed, onDismiss = { removing = null }, onRemove = { removed?.let(onRemoved) })
    sections.firstOrNull { it.id == newSessionOn }?.let { s ->
        NewSessionDialog(s.connection, onDismiss = { newSessionOn = null }, onCreated = { session ->
            newSessionOn = null
            onOpenSession(SessionKey(s.id, session.id))
        })
    }
}

/** A server's connection and what the sidebar shows of it, as state. */
private class SectionState(
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
}

@Composable
private fun sectionState(connection: ServerConnection): SectionState {
    val server by connection.server.collectAsStateWithLifecycle()
    val state by connection.state.collectAsStateWithLifecycle()
    val indexSynced by connection.indexSynced.collectAsStateWithLifecycle()
    val sessions by connection.sessions.collectAsStateWithLifecycle()
    val projects by connection.projects.collectAsStateWithLifecycle()
    val harnesses by connection.harnesses.collectAsStateWithLifecycle()
    return SectionState(connection, server, state, indexSynced, sessions, projects, harnesses)
}

/**
 * One server in the sidebar: its header, how its connection is doing, and
 * its sessions, those waiting for the user first. [selection]: the open
 * session's id, when it is on this server.
 */
private fun LazyListScope.serverSection(
    s: SectionState,
    first: Boolean,
    expanded: Boolean,
    showArchived: Boolean,
    selection: String?,
    onExpand: (Boolean) -> Unit,
    onShowArchived: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onNewSession: () -> Unit,
    onArchive: (Session) -> Unit,
    onRemove: () -> Unit,
) {
    item(key = "${s.id}/header") {
        ServerHeader(
            s, expanded, Modifier.animateItem().padding(top = if (first) 0.dp else 8.dp),
            onActivate = {
                // Once the connection has given up, a tap tries again.
                if (s.state is ConnectionState.Failed) {
                    s.connection.retry()
                    onExpand(true)
                } else {
                    onExpand(!expanded)
                }
            },
            onNewSession = onNewSession,
            onRemove = onRemove,
        )
    }
    if (!expanded) return

    status(s, onNewSession)
    fun rows(list: List<Session>) = items(list, key = { "${s.id}/${it.id}" }) { session ->
        SessionRowItem(
            session, s.projects[session.projectId], s.harnesses?.firstOrNull { it.id == session.harness },
            background = palette.mantle,
            selected = session.id == selection,
            onClick = { onOpen(session.id) },
            onArchive = { onArchive(session) },
            modifier = Modifier.animateItem(),
        )
    }
    val active = s.sessions.activeSessions
    rows(active.filter { it.status == SessionStatus.AWAITING_APPROVAL } + active.filter { it.status != SessionStatus.AWAITING_APPROVAL })

    val archived = s.sessions.archivedSessions
    if (archived.isEmpty()) return
    item(key = "${s.id}/archived") {
        val angle by animateFloatAsState(if (showArchived) 90f else 0f, label = "chevron")
        SidebarRow(
            Modifier.animateItem().clickable { onShowArchived(!showArchived) }
                .semantics { stateDescription = if (showArchived) "Shown" else "Hidden" },
        ) {
            Icon(painterResource(Icons.archive), null, Modifier.size(18.dp), tint = palette.overlay)
            Text("Archived", style = Type.body, color = palette.subtext)
            Text("${archived.size}", style = Type.body, color = palette.overlay)
            Spacer(Modifier.weight(1f))
            Icon(painterResource(Icons.chevronRight), null, Modifier.size(14.dp).rotate(angle), tint = palette.overlay)
        }
    }
    if (showArchived) rows(archived)
}

/** While it isn't connected, why; and once it has given up, a way to try again. */
private fun LazyListScope.status(s: SectionState, onNewSession: () -> Unit) {
    val key = "${s.id}/status"
    when {
        s.state is ConnectionState.Failed -> item(key = key) {
            SidebarRow(Modifier.animateItem().clickable { s.connection.retry() }, Alignment.Top) {
                Icon(painterResource(Icons.offline), null, Modifier.size(18.dp), tint = palette.red)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        s.state.message, style = Type.body, color = palette.subtext, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("connection-error"),
                    )
                    Text("Try again", style = Type.body, color = palette.accent)
                }
            }
        }
        s.state == ConnectionState.Connecting -> item(key = key) {
            SidebarRow(Modifier.animateItem()) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = palette.subtext, strokeWidth = 2.dp)
                }
                Text(if (s.indexSynced) "Reconnecting…" else "Connecting…", style = Type.body, color = palette.subtext)
            }
        }
        s.state == ConnectionState.Connected && s.indexSynced && s.sessions.isEmpty() -> item(key = key) {
            SidebarRow(Modifier.animateItem().clickable(onClick = onNewSession)) {
                Icon(painterResource(Icons.newSession), null, Modifier.size(18.dp), tint = palette.overlay)
                Text("Start a session", style = Type.body, color = palette.subtext)
            }
        }
    }
}

/** A row of the sidebar that isn't a session, its icon in line with the sessions' status icons. */
@Composable
private fun SidebarRow(
    modifier: Modifier = Modifier,
    alignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().background(palette.mantle).padding(Metrics.rowInsets),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = alignment,
        content = content,
    )
}

/**
 * A server's section header: its name and connection, and while its sessions
 * are hidden, how many there are and whether one is waiting for the user. A
 * tap shows or hides them, or tries again once the connection has given up.
 * A + starts a session on the server; its other actions are in a ⋯ menu,
 * which a long press on the name opens too.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerHeader(
    s: SectionState,
    expanded: Boolean,
    modifier: Modifier,
    onActivate: () -> Unit,
    onNewSession: () -> Unit,
    onRemove: () -> Unit,
) {
    val name = s.name
    val active = s.sessions.activeSessions
    val waiting = active.any { it.status == SessionStatus.AWAITING_APPROVAL }
    val offline = s.state is ConnectionState.Failed
    val connected = s.state == ConnectionState.Connected
    var menu by remember { mutableStateOf(false) }
    val description = buildList {
        add(connectionLabel(s.state))
        add(if (expanded) "Expanded" else "Collapsed, ${active.size} sessions")
        if (!expanded && waiting) add("Needs you")
    }.joinToString(", ")

    Row(modifier.fillMaxWidth().background(palette.mantle).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).clip(RoundedCornerShape(Metrics.Corner.selection))
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = when {
                        offline -> "Try to connect again"
                        expanded -> "Hide sessions"
                        else -> "Show sessions"
                    },
                    onLongClickLabel = "Actions",
                    onLongClick = { menu = true },
                    onClick = onActivate,
                )
                .semantics {
                    contentDescription = name
                    stateDescription = description
                }
                .testTag("server-header"),
        ) {
            // One element to TalkBack, which reads the name and the state above.
            Row(
                Modifier.clearAndSetSemantics {}.padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(Icons.server), null, Modifier.size(18.dp), tint = palette.overlay)
                Text(
                    name, style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = palette.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                ConnectionDot(s.state, size = 8.dp)
                if (!expanded && active.isNotEmpty()) {
                    Text("${active.size}", style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = palette.overlay)
                }
                if (!expanded && waiting) {
                    Icon(painterResource(Icons.approval), null, Modifier.size(14.dp), tint = palette.yellow)
                }
            }
        }
        if (connected) {
            IconButton(onNewSession) { Icon(painterResource(Icons.add), "New session on $name", tint = palette.subtext) }
        }
        Box {
            IconButton({ menu = true }) { Icon(painterResource(Icons.more), "$name actions", tint = palette.subtext) }
            DropdownMenu(menu, { menu = false }, containerColor = palette.mantle) {
                MenuItem("New session on $name", Icons.add, enabled = connected) { menu = false; onNewSession() }
                MenuItem("Reconnect", Icons.reconnect) { menu = false; s.connection.retry() }
                HorizontalDivider(color = palette.surface0)
                MenuItem("Remove $name…", Icons.delete, color = palette.red) { menu = false; onRemove() }
            }
        }
    }
}
