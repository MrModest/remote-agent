package dev.remoteagent.app.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.provider.Settings

/** Small platform shims, as apple/Shared/Views/Platform.swift. */
object Platform {
    /** The name the server lists this device under. */
    fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    fun clipboardText(context: Context): String? {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
        return clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }

    fun copy(context: Context, text: String, label: String = "Text") {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
