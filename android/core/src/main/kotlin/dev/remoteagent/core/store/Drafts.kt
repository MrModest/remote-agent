package dev.remoteagent.core.store

import dev.remoteagent.core.protocol.WireCoding
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Prompts written and not sent yet, by session, for one server. They stay on
 * this device, in `storage` (with none, as long as this object); the server
 * never sees them. Mirrors apple/RAKit/Sources/RAKit/Store/Drafts.swift.
 */
class Drafts(private val storage: Storage?, serverID: String) {
    private val key = "drafts.$serverID"
    private val texts: MutableMap<String, String> = storage?.get(key)
        ?.let { runCatching { WireCoding.json.decodeFromString(serializer, it) }.getOrNull() }
        ?.toMutableMap() ?: mutableMapOf()

    /** The session's draft, or "" for none. */
    operator fun get(sessionID: String): String = texts[sessionID] ?: ""

    /** Sets the session's draft; "" forgets it. */
    operator fun set(sessionID: String, text: String) {
        if (get(sessionID) == text) return
        if (text.isEmpty()) texts.remove(sessionID) else texts[sessionID] = text
        save()
    }

    /** Forgets the drafts of sessions that are gone from the server. */
    fun keep(isKept: (String) -> Boolean) {
        if (texts.keys.removeAll { !isKept(it) }) save()
    }

    fun removeAll() {
        texts.clear()
        save()
    }

    private fun save() {
        storage?.set(key, if (texts.isEmpty()) null else WireCoding.json.encodeToString(serializer, texts))
    }

    private companion object {
        val serializer = MapSerializer(String.serializer(), String.serializer())
    }
}
