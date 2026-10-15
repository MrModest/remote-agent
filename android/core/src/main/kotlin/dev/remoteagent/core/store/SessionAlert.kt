package dev.remoteagent.core.store

import dev.remoteagent.core.protocol.Session
import dev.remoteagent.core.protocol.SessionStatus

/**
 * A change in a session that the user wants to hear about while they look
 * elsewhere, such as in a notification: the agent waits for them, finished
 * its turn, or failed.
 * Mirrors apple/RAKit/Sources/RAKit/Store/SessionAlert.swift.
 */
sealed interface SessionAlert {
    /** It waits for an approval or an answer. */
    data object NeedsYou : SessionAlert
    /** Its turn ended. */
    data object Finished : SessionAlert
    /** The agent didn't start, or exited with an error. */
    data class Failed(val error: String?) : SessionAlert

    companion object {
        /** The alert for a session that changed from `old` to `new`, if any. An archived session has none. */
        fun between(old: Session, new: Session): SessionAlert? {
            if (new.archived || old.status == new.status) return null
            return when (new.status) {
                SessionStatus.AWAITING_APPROVAL -> NeedsYou
                SessionStatus.IDLE -> if (old.status == SessionStatus.RUNNING) Finished else null
                SessionStatus.ERROR -> Failed(new.error)
                else -> null
            }
        }
    }
}
