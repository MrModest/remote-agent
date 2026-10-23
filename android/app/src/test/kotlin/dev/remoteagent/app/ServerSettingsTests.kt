package dev.remoteagent.app

import dev.remoteagent.app.ui.count
import dev.remoteagent.app.ui.radFooter
import dev.remoteagent.app.ui.webURL
import dev.remoteagent.core.protocol.UpdateInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The server settings' wording, which the Apple and web clients share. */
class ServerSettingsTests {
    @Test
    fun radFooters() {
        val app = UpdateInfo(current = "0.11.0", latest = "0.12.0", available = true, via = UpdateInfo.VIA_APP, busy = listOf("Fix the tests", "Docs"))
        assertEquals(
            "The Remote Agent Server app on the Mac installs the update and restarts, with rad. " +
                "Updating now interrupts 2 busy sessions: Fix the tests, Docs.",
            radFooter(app)?.text,
        )
        val rad = app.copy(via = UpdateInfo.VIA_RAD, busy = listOf("Fix the tests"))
        assertEquals(
            "rad installs the update and restarts. Sessions reconnect by themselves. Updating now interrupts 1 busy session: Fix the tests.",
            radFooter(rad)?.text,
        )
        // Up to date: nothing to interrupt.
        assertEquals("rad installs the update and restarts. Sessions reconnect by themselves.", radFooter(rad.copy(available = false))?.text)
        // It can't install: the reason says why, and there's no footer.
        assertNull(radFooter(app.copy(via = null, reason = "rad dev is a development build, which doesn't update itself.")))
        assertNull(radFooter(null))
        assertEquals("1 session", count(1, "session"))
        assertEquals("3 sessions", count(3, "session"))
    }

    @Test
    fun onlyWebLinksOpen() {
        assertEquals("https://claude.com/cai/oauth/authorize?code=true", webURL("https://claude.com/cai/oauth/authorize?code=true"))
        assertEquals("HTTP://example.com", webURL("HTTP://example.com"))
        assertNull(webURL("javascript:alert(1)"))
        assertNull(webURL("file:///etc/passwd"))
        assertNull(webURL("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(webURL("example.com/a:b"))
        assertNull(webURL("https:"))
        assertNull(webURL(null))
    }
}
