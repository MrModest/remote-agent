package dev.remoteagent.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remoteagent.app.platform.AppSettings.Keys
import dev.remoteagent.app.platform.state

/**
 * The Projects layout: each server a section, its projects folding open to
 * their sessions, a line each, what needs you first. The projects come in
 * order of their latest session; a server's archived sessions fold up last.
 * Mirrors apple/Shared/Views/ProjectsSidebar.swift.
 */
@Composable
fun ProjectsSidebar(servers: List<ServerState>, selection: SessionKey?, onOpenSession: (SessionKey) -> Unit) {
    val settings = LocalSettings.current
    val collapsed = rememberCollapsedServers(servers)
    // The projects folded shut, as "<server id>/<project id>", separated by spaces.
    val collapsedProjects by settings.state(Keys.collapsedProjects, "")
    val shut = collapsedProjects.split(' ').toSet()
    // The servers shown with their archived sessions.
    var showArchived by rememberSaveable { mutableStateOf(listOf<String>()) }

    fun setProjectExpanded(server: String, project: String, shown: Boolean) =
        settings.update(Keys.collapsedProjects, "") { toggled(it, "$server/$project", present = !shown) }

    // Landing on a session, such as one opened before the window widened or
    // a new one, unfolds its server, its project or the archive to show its
    // row. Again once the index arrives: before it, the session can't be found.
    val open = selection?.let { k -> servers.firstOrNull { it.id == k.serverId }?.sessions?.get(k.sessionId) }
    LaunchedEffect(selection, open?.projectId, open?.archived) {
        if (selection == null || open == null) return@LaunchedEffect
        collapsed.setExpanded(selection.serverId, true)
        if (open.archived) {
            if (selection.serverId !in showArchived) showArchived = showArchived + selection.serverId
        } else {
            setProjectExpanded(selection.serverId, open.projectId, true)
        }
    }

    LazyColumn(Modifier.fillMaxSize().testTag("sidebar")) {
        fun rows(list: List<ServerSession>) = items(list, key = { it.id }) { item ->
            // Under the project's name, as in a tree.
            SessionLink(
                item, selected = item.key == selection, onOpen = onOpenSession, modifier = Modifier.animateItem(),
                padding = PaddingValues(start = 32.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            ) {
                TreeRow(item)
            }
        }
        servers.forEachIndexed { i, s ->
            val expanded = collapsed.expanded(s.id)
            item(key = "${s.id}/header") {
                ServerHeader(s, expanded, { collapsed.setExpanded(s.id, it) }, Modifier.animateItem().padding(top = if (i == 0) 0.dp else 8.dp))
            }
            if (!expanded) return@forEachIndexed
            connectionStatus(s)
            for (group in projectGroups(s)) {
                val shown = "${s.id}/${group.projectId}" !in shut
                item(key = "${s.id}/project/${group.projectId}") {
                    var menu by remember { mutableStateOf(false) }
                    Box(Modifier.animateItem()) {
                        ProjectLabel(
                            group.name, group.items.size, waiting = !shown && group.items.any { it.session.needsYou }, shown = shown,
                            onToggle = { setProjectExpanded(s.id, group.projectId, it) }, onLongClick = { menu = true },
                        )
                        ProjectMenu(menu, { menu = false }, s, group.projectId)
                    }
                }
                if (shown) rows(group.items)
            }
            val archived = s.archived.map { ServerSession(s, it) }
            if (archived.isNotEmpty()) {
                val shown = s.id in showArchived
                item(key = "${s.id}/archived") {
                    ArchivedToggle(
                        archived.size, shown, { showArchived = if (it) showArchived + s.id else showArchived - s.id },
                        Modifier.animateItem(),
                    )
                }
                if (shown) rows(archived)
            }
        }
    }
}

/**
 * A project in the Projects layout: its name and how many sessions it has;
 * while folded shut, whether one needs you. A tap folds it open or shut, and
 * a long press opens its menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectLabel(
    name: String, count: Int, waiting: Boolean, shown: Boolean, onToggle: (Boolean) -> Unit, onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val angle by animateFloatAsState(if (shown) 90f else 0f, label = "chevron")
    SidebarRow(
        modifier.combinedClickable(onClick = { onToggle(!shown) }, onLongClick = onLongClick, onLongClickLabel = "Actions")
            .semantics { stateDescription = if (shown) "Expanded" else "Collapsed" },
    ) {
        Icon(painterResource(Icons.folder), null, Modifier.size(16.dp), tint = palette.accent)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                name, style = Type.body.copy(fontWeight = FontWeight.Medium), color = palette.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            if (waiting) Icon(painterResource(Icons.approval), "Needs you", Modifier.size(13.dp), tint = palette.yellow)
        }
        Text("$count", style = Type.caption.copy(fontFeatureSettings = "tnum"), color = palette.overlay)
        Icon(painterResource(Icons.chevronRight), null, Modifier.size(14.dp).rotate(angle), tint = palette.overlay)
    }
}
