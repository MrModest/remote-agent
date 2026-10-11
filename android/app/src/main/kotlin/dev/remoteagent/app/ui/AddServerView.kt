package dev.remoteagent.app.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.remoteagent.app.platform.Platform
import dev.remoteagent.core.net.PairingLink
import dev.remoteagent.core.store.SavedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** "Pair a server". Mirrors apple/Shared/Views/AddServerView.swift. */
@Composable
fun AddServerDialog(initialLink: PairingLink?, onDismiss: () -> Unit, onPaired: (SavedServer) -> Unit) {
    val store = LocalStore.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var linkText by rememberSaveable(initialLink) { mutableStateOf(initialLink?.toLinkString() ?: "") }
    var deviceName by rememberSaveable { mutableStateOf(Platform.deviceName(context)) }
    var pairing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val link = PairingLink.parse(linkText)

    fun pair() {
        val l = PairingLink.parse(linkText) ?: return
        if (pairing) return
        pairing = true
        error = null
        scope.launch {
            try {
                onPaired(store.pair(l, deviceName))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Pairing failed"
            } finally {
                pairing = false
            }
        }
    }

    // Google Play services shows the scanner, so the app needs no camera permission.
    fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
        GmsBarcodeScanning.getClient(activity ?: context, options).startScan()
            .addOnSuccessListener { code ->
                val payload = code.rawValue ?: ""
                if (PairingLink.parse(payload) != null) {
                    linkText = payload
                    pair()
                } else {
                    error = "That QR code isn't a pairing link from `rad pair`."
                }
            }
            .addOnFailureListener { e ->
                val reason = e.message?.trimEnd('.')?.let { " ($it)" } ?: ""
                error = "The scanner isn't available$reason. Paste the link instead."
            }
            .addOnCanceledListener { }
    }

    FullScreenDialog(onDismiss) {
        Screen(
            title = { Text("Pair a server", style = Type.headline) },
            onBack = onDismiss,
            backIcon = Icons.close,
            backLabel = "Cancel",
            background = palette.mantle,
            actions = {
                if (pairing) {
                    CircularProgressIndicator(Modifier.size(24.dp).padding(end = 4.dp), color = palette.subtext, strokeWidth = 2.dp)
                } else {
                    TextButton(::pair, enabled = link != null, modifier = Modifier.testTag("pair")) {
                        Text("Pair", color = if (link != null) palette.accent else palette.overlay, style = Type.headline)
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).formWidth()) {
                FormSection(
                    "Pairing link",
                    footer = inlineCode("On your computer run `rad pair`. The code is valid for 10 minutes and works once."),
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = ::scan).padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(Icons.scanQR), null, tint = palette.accent)
                        Text("Scan QR code", color = palette.accent, style = Type.body)
                    }
                    FormDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FormTextField(
                            value = linkText,
                            onValueChange = { linkText = it },
                            placeholder = "remoteagent://pair?…",
                            modifier = Modifier.weight(1f).testTag("pairing-link"),
                            style = Type.footnote.copy(fontFamily = FontFamily.Monospace),
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
                        )
                        IconButton({ linkText = Platform.clipboardText(context) ?: "" }) {
                            Icon(painterResource(Icons.paste), "Paste", tint = palette.accent)
                        }
                    }
                }
                if (link != null) {
                    FormSection("Server") {
                        Row(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("Name", style = Type.body, color = palette.text, modifier = Modifier.weight(1f))
                            Text(link.name, style = Type.body, color = palette.subtext)
                        }
                        for (url in link.urls) {
                            FormDivider()
                            Text(url, style = Type.footnote.copy(fontFamily = FontFamily.Monospace), color = palette.subtext, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
                FormSection("This device") {
                    FormTextField(deviceName, { deviceName = it }, "Device name", keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
                }
                error?.let {
                    FormSection {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(Icons.warning), null, tint = palette.red, modifier = Modifier.size(18.dp))
                            Text(inlineCode(it), color = palette.red, style = Type.subheadline, modifier = Modifier.testTag("pair-error"))
                        }
                    }
                }
            }
        }
    }
}
