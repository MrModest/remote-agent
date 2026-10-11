package dev.remoteagent.app.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.remoteagent.app.RemoteAgentApp
import dev.remoteagent.app.platform.AppSettings
import dev.remoteagent.app.platform.state
import dev.remoteagent.core.net.PairingLink
import dev.remoteagent.core.store.ServerStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

val LocalStore = staticCompositionLocalOf<ServerStore> { error("no ServerStore") }
val LocalSettings = staticCompositionLocalOf<AppSettings> { error("no AppSettings") }

// Destinations, registered once at the root with values that carry the
// server's id (AGENTS.md), so a screen never opens on the wrong server.
@Serializable
data object ServersKey : NavKey

@Serializable
data class ServerHomeKey(val serverId: String) : NavKey

@Serializable
data class SessionKey(val serverId: String, val sessionId: String) : NavKey

@Serializable
data object SettingsKey : NavKey

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun App(app: RemoteAgentApp, pendingLink: MutableStateFlow<PairingLink?>) {
    CompositionLocalProvider(LocalStore provides app.store, LocalSettings provides app.settings) {
        val settings = app.settings
        val follows by settings.state(AppSettings.Keys.interfaceFollowsSystem, true)
        val step by settings.state(AppSettings.Keys.interfaceTextSize, TextSize.DEFAULT_INTERFACE)
        val messages by settings.state(AppSettings.Keys.messageTextSize, TextSize.DEFAULT_MESSAGES)
        TextSettings(follows, step, messages) {
            RemoteAgentTheme {
                // Test tags become resource ids, so `uiautomator dump` (android/e2e) can find views.
                Box(Modifier.fillMaxSize().background(palette.base).semantics { testTagsAsResourceId = true }) {
                    AppNavigation(pendingLink)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun AppNavigation(pendingLink: MutableStateFlow<PairingLink?>) {
    val store = LocalStore.current
    val backStack = rememberNavBackStack(ServersKey)
    val link by pendingLink.collectAsStateWithLifecycle()
    var showAdd by rememberSaveable { mutableStateOf(false) }

    // With a single server, open it once at launch; not on every return to
    // the list, which would bounce the user back in.
    var autoOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (autoOpened) return@LaunchedEffect
        autoOpened = true
        val servers = store.servers.value
        if (backStack.size == 1 && servers.size == 1) backStack.add(ServerHomeKey(servers[0].id))
    }

    val listDetail = rememberListDetailSceneStrategy<NavKey>()
    val activity = LocalActivity.current
    NavDisplay(
        backStack = backStack,
        // Never pops the last entry (NavDisplay needs one): back there leaves the app.
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() else activity?.finish() },
        sceneStrategies = listOf(listDetail),
        entryProvider = entryProvider {
            entry<ServersKey> {
                ServersView(
                    onOpen = { backStack.add(ServerHomeKey(it.id)) },
                    onAdd = { showAdd = true },
                    onSettings = { backStack.add(SettingsKey) },
                )
            }
            entry<ServerHomeKey>(metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { NoSessionSelected() })) { key ->
                val server = store.server(key.serverId)
                if (server == null) {
                    LaunchedEffect(key) { backStack.remove(key) }
                } else {
                    val selected = (backStack.lastOrNull() as? SessionKey)?.takeIf { it.serverId == key.serverId }?.sessionId
                    ServerHomeView(
                        connection = store.connection(server),
                        selectedSession = selected,
                        onOpenSession = { id -> backStack.openSession(SessionKey(key.serverId, id)) },
                        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                        onSettings = { backStack.add(SettingsKey) },
                        onRemoved = { backStack.removeAll { it == key || (it as? SessionKey)?.serverId == key.serverId } },
                    )
                }
            }
            entry<SessionKey>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
                val server = store.server(key.serverId)
                if (server == null) {
                    LaunchedEffect(key) { backStack.remove(key) }
                } else {
                    val connection = store.connection(server)
                    SessionView(connection, connection.sessionStore(key.sessionId), onBack = { if (backStack.size > 1) backStack.removeLastOrNull() })
                }
            }
            entry<SettingsKey> { SettingsView(onBack = { if (backStack.size > 1) backStack.removeLastOrNull() }) }
        },
    )

    if (showAdd || link != null) {
        AddServerDialog(
            initialLink = link,
            onDismiss = {
                showAdd = false
                pendingLink.value = null
            },
            onPaired = { server ->
                showAdd = false
                pendingLink.value = null
                backStack.removeAll { it != ServersKey }
                backStack.add(ServerHomeKey(server.id))
            },
        )
    }
}

/** Opens a session: in place of the one shown beside the list, or pushed on a phone. */
private fun NavBackStack<NavKey>.openSession(key: SessionKey) {
    if (lastOrNull() == key) return
    if (lastOrNull() is SessionKey) removeAt(lastIndex)
    add(key)
}

@Composable
private fun NoSessionSelected() {
    EmptyState(Icons.question, "No session selected", description = "Choose a session in the list, or start a new one.")
}
