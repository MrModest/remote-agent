package dev.remoteagent.core.store

/** Small settings that must be there synchronously, like UserDefaults (the app: SharedPreferences). */
interface Storage {
    operator fun get(key: String): String?
    operator fun set(key: String, value: String?)
}

/**
 * Device tokens, keyed by server id. Mirrors apple/RAKit/Sources/RAKit/Store/Keychain.swift;
 * the app keeps them encrypted with a key in the Android Keystore.
 */
interface TokenStore {
    fun get(serverId: String): String?
    fun set(serverId: String, token: String)
    fun delete(serverId: String)
}

/** Runs `block`, giving null for a failure (but not swallowing cancellation), like Swift's `try?`. */
internal suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
