package dev.remoteagent.core.store

/** Sync and connection events, for debugging; the app sends them to logcat (tag RemoteAgent). */
object Log {
    @Volatile var sink: ((String) -> Unit)? = null

    internal fun d(message: () -> String) {
        sink?.invoke(message())
    }
}
