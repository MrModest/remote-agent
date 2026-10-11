package dev.remoteagent.app.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dev.remoteagent.core.store.TokenStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device tokens, keyed by server id, encrypted with an AES key that never
 * leaves the Android Keystore (the Apple apps keep them in the Keychain).
 * The file is left out of backups and device transfers, so a token stays on
 * this device; if it can't be decrypted, the server shows as unauthorized and
 * the user pairs again.
 */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("tokens", Context.MODE_PRIVATE)

    override fun get(serverId: String): String? {
        val stored = prefs.getString(serverId, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE).decodeToString()
        }.getOrNull()
    }

    override fun set(serverId: String, token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(token.encodeToByteArray())
        prefs.edit(commit = true) { putString(serverId, Base64.encodeToString(sealed, Base64.NO_WRAP)) }
    }

    override fun delete(serverId: String) {
        prefs.edit(commit = true) { remove(serverId) }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        // The Keychain's service in the Apple apps is dev.remote-agent.token.
        const val ALIAS = "dev.remoteagent.token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
