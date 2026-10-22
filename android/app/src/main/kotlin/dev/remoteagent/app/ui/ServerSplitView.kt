package dev.remoteagent.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import dev.remoteagent.app.platform.AppSettings.Keys
import dev.remoteagent.core.store.ConnectionState
import dev.remoteagent.core.store.SavedServer

/**
 * The tablet's list pane, for every layout: every server's sessions in the
 * sidebar, or in the Columns layout, the collections and a list of sessions
 * beside them. The open session is the detail pane beside it (AppNavigation).
 * It shows the dialogs the sidebars ask for. Mirrors apple/Shared/Views/ServerSplitView.swift.
 */
@Composable
fun ServerSplitView(
    layout: SidebarLayout,
    selection: SessionKey?,
    onOpenSession: (SessionKey) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onRemoved: (SavedServer) -> Unit,
) {
    val settings = LocalSettings.current
    val servers = serverStates()
    var newSessionOn by rememberSaveable { mutableStateOf<String?>(null) }
    var newSessionIn by rememberSaveable { mutableStateOf<String?>(null) }
    var archiving by remember { mutableStateOf<SessionRequest?>(null) }
    var deleting by remember { mutableStateOf<SessionRequest?>(null) }
    var removing by remember { mutableStateOf<SavedServer?>(null) }
    val actions = remember {
        SessionActions(
            startSession = { c, projectId ->
                newSessionOn = c.server.value.id
                newSessionIn = projectId
            },
            archive = { archiving = it },
            delete = { deleting = it },
            remove = { removing = it },
        )
    }

    // Every server connects; one that was given up on waits for a tap. Keyed
    // on the connections, since pairing a server again replaces its own.
    val connections = servers.map { it.connection }
    LaunchedEffect(connections) {
        for (c in connections) if (c.state.value == ConnectionState.Idle) c.start()
    }

    // The Columns layout's collection, as `SessionCollection.stored`; null until it has loaded.
    val stored by remember { settings.flow(Keys.sessionCollection, "") }.collectAsStateWithLifecycle<String?>(null)
    val collection = stored?.let { SessionCollection.parse(it) } ?: SessionCollection.All

    // In three columns, a session opened from elsewhere, such as a new one or
    // one open before the window widened, shows in the list: when the
    // collection doesn't have it, All Sessions or Archived does.
    val open = selection?.let { k -> servers.firstOrNull { it.id == k.serverId }?.sessions?.get(k.sessionId) }
    val reveal = if (layout == SidebarLayout.COLUMNS && stored != null && open != null) "$selection ${open.archived}" else null
    LaunchedEffect(reveal) {
        if (reveal == null || open == null) return@LaunchedEffect
        if (collection.sessions(servers).none { it.key == selection }) {
            val shown = if (open.archived) SessionCollection.Archived else SessionCollection.All
            settings.put(Keys.sessionCollection, shown.stored)
        }
    }

    CompositionLocalProvider(LocalSessionActions provides actions) {
        when (layout) {
            SidebarLayout.COLUMNS -> ColumnsSidebar(
                servers, collection, onChoose = { settings.put(Keys.sessionCollection, it.stored) },
                selection = selection, onOpenSession = onOpenSession, onAdd = onAdd, onSettings = onSettings,
            )
            else -> SessionsSidebar(layout, servers, selection, onOpenSession, onAdd, onSettings)
        }
    }

    ArchiveSessionDialog(archiving, onDismiss = { archiving = null })
    DeleteSessionDialog(deleting, onDismiss = { deleting = null })
    val removed = removing
    RemoveServerDialog(removed, onDismiss = { removing = null }, onRemove = { removed?.let(onRemoved) })
    servers.firstOrNull { it.id == newSessionOn }?.let { s ->
        NewSessionDialog(s.connection, newSessionIn, onDismiss = { newSessionOn = null }, onCreated = { session ->
            newSessionOn = null
            onOpenSession(SessionKey(s.id, session.id))
        })
    }
}

/**
 * The list pane's width, as the directive's default pane width: the default
 * for a sidebar, wider for the Columns layout's two columns. The session
 * beside it asks for as much, so the list takes up to half the window.
 */
fun listPaneWidth(layout: SidebarLayout, window: WindowSizeClass): Dp? = when {
    layout != SidebarLayout.COLUMNS -> null
    window.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_LARGE_LOWER_BOUND) -> 640.dp
    else -> 540.dp
}
