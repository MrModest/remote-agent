package dev.remoteagent.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.remoteagent.core.store.SavedServer
import dev.remoteagent.core.store.ServerConnection
import java.net.URI

/** The paired servers. Mirrors apple/iOS/ServersView.swift. */
@Composable
fun ServersView(onOpen: (SavedServer) -> Unit, onAdd: () -> Unit, onSettings: () -> Unit) {
    val store = LocalStore.current
    val servers by store.servers.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<SavedServer?>(null) }

    Screen(
        title = { Text("Servers", style = Type.title) },
        actions = {
            IconButton(onSettings) { Icon(painterResource(Icons.settings), "Settings") }
            IconButton(onAdd, Modifier.testTag("add-server")) { Icon(painterResource(Icons.add), "Add") }
        },
    ) { padding ->
        if (servers.isEmpty()) {
            EmptyState(
                Icons.server, "No servers", description = "Run `rad serve` on your computer, then `rad pair` and scan the QR code.",
                action = "Pair a server" to onAdd, modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                items(servers, key = { it.id }) { server ->
                    SwipeAction("Remove", Icons.delete, palette.red, onSwipe = { removing = server }) {
                        ServerRow(store.connection(server), onClick = { onOpen(server) }, onRemove = { removing = server })
                    }
                }
            }
        }
    }
    RemoveServerDialog(removing, onDismiss = { removing = null })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(connection: ServerConnection, onClick: () -> Unit, onRemove: () -> Unit) {
    val server by connection.server.collectAsStateWithLifecycle()
    val state by connection.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().background(palette.base).combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(Metrics.rowInsets).testTag("server-row"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(Icons.server), null, Modifier.size(28.dp), tint = palette.accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(server.name, style = Type.headline, color = palette.text)
                Text(server.urls.firstOrNull()?.let { runCatching { URI(it).host }.getOrNull() } ?: "", style = Type.caption, color = palette.subtext)
            }
            ConnectionDot(state)
        }
        DropdownMenu(menu, { menu = false }, containerColor = palette.mantle) {
            DropdownMenuItem(
                text = { Text("Remove", color = palette.red) },
                leadingIcon = { Icon(painterResource(Icons.delete), null, tint = palette.red) },
                onClick = { menu = false; onRemove() },
            )
        }
    }
}
