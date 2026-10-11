package dev.remoteagent.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.remoteagent.app.ui.App
import dev.remoteagent.core.net.PairingLink
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** A pairing link the app was opened with (remoteagent://pair?…), for the pairing sheet. */
    private val pendingLink = MutableStateFlow<PairingLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent { App(application as RemoteAgentApp, pendingLink) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent?.dataString?.let(PairingLink::parse)?.let { pendingLink.value = it }
    }
}
