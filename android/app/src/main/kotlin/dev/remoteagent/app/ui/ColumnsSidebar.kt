package dev.remoteagent.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remoteagent.core.protocol.Project
import dev.remoteagent.core.store.sortedProjects

/**
 * The Columns layout's list pane, as Mail's first two columns: the
 * collections a session can be in, and beside them the sessions in the one
 * chosen. The session itself is the detail pane beside it all. Mirrors
 * apple/Shared/Views/ColumnsSidebar.swift.
 */
@Composable
fun ColumnsSidebar(
    servers: List<ServerState>,
    collection: SessionCollection,
    onChoose: (SessionCollection) -> Unit,
    selection: SessionKey?,
    onOpenSession: (SessionKey) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sidebar = maxWidth * 0.42f
        Row(Modifier.fillMaxSize()) {
            CollectionsSidebar(servers, collection, onChoose, onAdd, onSettings, compact = sidebar < 240.dp, modifier = Modifier.width(sidebar).fillMaxHeight())
            SessionListColumn(servers, collection, selection, onOpenSession, Modifier.weight(1f).fillMaxHeight())
            // The list and the session share a colour; a line keeps them apart.
            Box(Modifier.width(1.dp).fillMaxHeight().background(palette.surface0))
        }
    }
}

/**
 * The Columns layout's sidebar: what needs you, what is working and every
 * session, then each server's projects that have sessions, then the archive.
 * The sessions are a column of their own beside it, which keeps the sidebar
 * to two levels.
 */
@Composable
private fun CollectionsSidebar(
    servers: List<ServerState>,
    collection: SessionCollection,
    onChoose: (SessionCollection) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    compact: Boolean,
    modifier: Modifier,
) {
    val active = servers.sessions(archived = false)
    val archived = servers.sessions(archived = true)
    val collapsed = rememberCollapsedServers(servers)

    @Composable
    fun Link(
        c: SessionCollection, title: String, @DrawableRes icon: Int, count: Int, tint: Color = palette.accent,
        modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null,
    ) = CollectionRow(title, icon, count, tint, selected = c == collection, onClick = { onChoose(c) }, modifier = modifier, onLongClick = onLongClick)

    Box(modifier) {
        Screen(
            title = { Text("Sessions", style = Type.title) },
            background = palette.mantle,
            actions = { SidebarToolbar(servers, onAdd, onSettings, newSession = false) },
        ) { padding ->
            SidebarRefresh(servers, Modifier.padding(padding)) {
                LazyColumn(Modifier.fillMaxSize().testTag("sidebar")) {
                    // A fixed colour where it means something, as Mail's VIP.
                    item(key = "needsYou") {
                        Link(SessionCollection.NeedsYou, "Needs You", Icons.approval, active.count { it.session.needsYou }, palette.yellow)
                    }
                    item(key = "running") { Link(SessionCollection.Running, "Running", Icons.running, active.count { it.session.isBusy }) }
                    item(key = "all") { Link(SessionCollection.All, "All Sessions", Icons.allSessions, active.size) }
                    for (s in servers) {
                        val expanded = collapsed.expanded(s.id)
                        item(key = "${s.id}/header") {
                            ServerHeader(s, expanded, { collapsed.setExpanded(s.id, it) }, Modifier.animateItem().padding(top = 8.dp), compact)
                        }
                        if (!expanded) continue
                        connectionStatus(s)
                        items(collectionProjects(s), key = { "${s.id}/project/${it.first.id}" }) { (p, count) ->
                            var menu by remember { mutableStateOf(false) }
                            Box(Modifier.animateItem()) {
                                Link(SessionCollection.Project(s.id, p.id), p.name, Icons.folder, count, onLongClick = { menu = true })
                                ProjectMenu(menu, { menu = false }, s, p.id)
                            }
                        }
                    }
                    if (archived.isNotEmpty()) {
                        item(key = "archived") {
                            Link(SessionCollection.Archived, "Archived", Icons.archive, archived.size, modifier = Modifier.animateItem().padding(top = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * A collection in the sidebar: its icon, its name and how many sessions it
 * has. A project's opens its menu on a long press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionRow(
    title: String,
    @DrawableRes icon: Int,
    count: Int,
    tint: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxWidth().background(palette.mantle).padding(horizontal = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Metrics.Corner.selection))
                .background(if (selected) palette.surface0 else Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = onLongClick?.let { "Actions" })
                .semantics { this.selected = selected }
                .padding(horizontal = 8.dp, vertical = 11.dp)
                .testTag("collection"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), null, Modifier.size(20.dp), tint = tint)
            Text(title, style = Type.body, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            // As a list's badge, which shows nothing for none.
            if (count > 0) Text("$count", style = Type.body.copy(fontFeatureSettings = "tnum"), color = palette.subtext)
        }
    }
}

/** The server's projects that have sessions, by name, with how many. */
private fun collectionProjects(s: ServerState): List<Pair<Project, Int>> {
    val counts = s.active.groupingBy { it.projectId }.eachCount()
    return s.projects.sortedProjects.mapNotNull { p -> counts[p.id]?.let { p to it } }
}

/**
 * The Columns layout's middle column: the sessions in the collection chosen
 * in the sidebar, three lines each.
 */
@Composable
private fun SessionListColumn(
    servers: List<ServerState>,
    collection: SessionCollection,
    selection: SessionKey?,
    onOpenSession: (SessionKey) -> Unit,
    modifier: Modifier,
) {
    val items = collection.sessions(servers)
    val showsServer = servers.size > 1
    Box(modifier) {
        Screen(
            title = { Text(collection.title(servers), style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            background = palette.base,
            actions = { NewSessionButton(servers, collection) },
        ) { padding ->
            SidebarRefresh(servers, Modifier.padding(padding)) {
                // Each collection's list starts at its top.
                val state = key(collection) { rememberLazyListState() }
                LazyColumn(Modifier.fillMaxSize().testTag("session-list"), state) {
                    items(items, key = { it.id }) { item ->
                        SessionLink(
                            item, selected = item.key == selection, onOpen = onOpenSession, modifier = Modifier.animateItem(),
                            background = palette.base, padding = PaddingValues(8.dp),
                        ) {
                            SessionListRow(item, showsServer)
                        }
                    }
                }
                if (items.isEmpty()) EmptyCollection(collection)
            }
        }
    }
}

@Composable
private fun EmptyCollection(collection: SessionCollection) {
    when (collection) {
        SessionCollection.NeedsYou ->
            EmptyState(Icons.completed, "Nothing needs you", description = "Sessions that wait for your answer, or failed, show here.")
        SessionCollection.Running ->
            EmptyState(Icons.running, "Nothing is running", description = "Sessions show here while their agent works.")
        SessionCollection.Archived -> EmptyState(Icons.archive, "Nothing archived")
        SessionCollection.All, is SessionCollection.Project ->
            EmptyState(Icons.question, "No sessions", description = "Start one with the New Session button.")
    }
}
