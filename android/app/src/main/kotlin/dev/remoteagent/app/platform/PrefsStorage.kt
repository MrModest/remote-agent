package dev.remoteagent.app.platform

import android.content.Context
import androidx.core.content.edit
import dev.remoteagent.core.store.Storage

/** The server list and other small values the stores need synchronously, like UserDefaults. */
class PrefsStorage(context: Context) : Storage {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String?) {
        prefs.edit { if (value == null) remove(key) else putString(key, value) }
    }
}
