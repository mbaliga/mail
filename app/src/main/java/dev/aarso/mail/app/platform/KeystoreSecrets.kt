package dev.aarso.mail.app.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.aarso.mail.model.SecretRef
import dev.aarso.mail.sync.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Passwords, app passwords, OAuth refresh state and BYOK API keys, sealed with AES-256-GCM under a
 * key that lives in the Android Keystore (hardware-backed where the device has it) and never leaves
 * it. Only ciphertext touches disk, and the app opts out of backup, so a copied data directory is
 * useless without this device's key.
 */
class KeystoreSecrets(context: Context) : SecretStore {

    private val prefs = context.getSharedPreferences("sealed", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override suspend fun get(ref: SecretRef): String? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(ref.key, null) ?: return@withContext null
        runCatching {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 12)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    override suspend fun put(ref: SecretRef, value: String) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(ref.key, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    override suspend fun delete(ref: SecretRef) = withContext(Dispatchers.IO) {
        prefs.edit().remove(ref.key).apply()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "ygm.sealed.v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
