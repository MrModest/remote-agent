package dev.remoteagent.app.platform

import android.app.UiModeManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.remoteagent.app.ui.Appearance
import dev.remoteagent.app.ui.TextSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The settings views keep, like @AppStorage: the appearance, text sizes,
 * where the terminal opens, and the choices a new session starts from.
 */
class AppSettings(private val context: Context, private val scope: CoroutineScope) {
    private val store = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    fun <T> flow(key: Preferences.Key<T>, default: T): Flow<T> = store.data.map { it[key] ?: default }

    suspend fun <T> get(key: Preferences.Key<T>): T? = store.data.first()[key]

    fun <T> put(key: Preferences.Key<T>, value: T?) {
        scope.launch { store.edit { if (value == null) it.remove(key) else it[key] = value } }
    }

    /** Light or dark for the whole app, system UI included, like overrideUserInterfaceStyle. */
    fun applyAppearance(appearance: Appearance) {
        context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(
            when (appearance) {
                Appearance.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                Appearance.DARK -> UiModeManager.MODE_NIGHT_YES
                Appearance.LIGHT -> UiModeManager.MODE_NIGHT_NO
            },
        )
    }

    object Keys {
        val appearance = stringPreferencesKey(Appearance.KEY)
        val interfaceTextSize = intPreferencesKey(TextSize.INTERFACE_KEY)
        val messageTextSize = floatPreferencesKey(TextSize.MESSAGES_KEY)
        val interfaceFollowsSystem = booleanPreferencesKey(TextSize.FOLLOWS_SYSTEM_KEY)
        val terminalPlacement = stringPreferencesKey("terminalPlacement")
        val terminalHeight = floatPreferencesKey("terminalHeight")
        val diffWrap = booleanPreferencesKey("diffWrap")
        val lastHarness = stringPreferencesKey("lastHarness")
        val lastProject = stringPreferencesKey("lastProject")
        fun lastModel(harness: String) = stringPreferencesKey("lastModel.$harness")
        fun lastEffort(harness: String) = stringPreferencesKey("lastEffort.$harness")
        fun lastMode(harness: String) = stringPreferencesKey("lastMode.$harness")
        val dictationLanguage = stringPreferencesKey("dictationLanguage")
    }
}

/** A setting's value, as state that follows it. */
@Composable
fun <T> AppSettings.state(key: Preferences.Key<T>, default: T): State<T> =
    remember(key) { flow(key, default) }.collectAsStateWithLifecycle(default)
