package com.maragung.arrowide.github

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secure storage for the GitHub Personal Access Token (plan #12). The ONLY
 * file in the GitHub package allowed to touch android.* — no logic beyond
 * framework calls, so it needs no unit tests (everything else in the
 * package is pure JVM and fakes this interface).
 */
interface TokenStore {

    /** The stored token, or null when none is stored. */
    fun get(): String?

    /**
     * Stores [token] encrypted; `set(null)` removes the token AND its key
     * (used by disconnect).
     */
    fun set(token: String?)
}

/**
 * [TokenStore] backed by Android Keystore + app-private storage (plan #12):
 *
 *  - an AES-256-GCM key (alias `arrowide_github_pat`) lives in the
 *    AndroidKeyStore and never leaves it;
 *  - the token is encrypted with a fresh random 12-byte IV;
 *  - the file `github_pat.bin` in `filesDir` holds Base64(IV || ciphertext),
 *    NO_WRAP, written with MODE_PRIVATE — the token is never plaintext on
 *    disk, in source, in logs or in the UI after save.
 */
class AndroidKeystoreTokenStore(
    private val context: Context,
) : TokenStore {

    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    override fun get(): String? {
        if (!file.isFile) return null
        return try {
            val blob = Base64.decode(file.readBytes(), Base64.NO_WRAP)
            if (blob.size <= IV_SIZE_BYTES) return null // corrupt; treat as absent
            val iv = blob.copyOfRange(0, IV_SIZE_BYTES)
            val ciphertext = blob.copyOfRange(IV_SIZE_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, loadKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null // unreadable (key rotated/corrupt) — behave as not connected
        } catch (e: IllegalArgumentException) {
            null // Base64.decode of corrupt data
        } catch (e: IOException) {
            null // unreadable file — behave as not connected
        }
    }

    override fun set(token: String?) {
        if (token == null) {
            file.delete()
            val keyStore = loadKeyStore()
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(IV_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).use { output ->
            output.write(Base64.encode(iv + ciphertext, Base64.NO_WRAP))
        }
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** The existing key; throws when absent (i.e. only after [set] stored a token). */
    private fun loadKey(): SecretKey {
        val entry = loadKeyStore().getEntry(KEY_ALIAS, null)
            as? KeyStore.SecretKeyEntry
        return entry?.secretKey
            ?: throw GeneralSecurityException("GitHub token key missing from AndroidKeyStore")
    }

    private fun loadOrCreateKey(): SecretKey {
        val entry = loadKeyStore().getEntry(KEY_ALIAS, null)
            as? KeyStore.SecretKeyEntry
        if (entry != null) return entry.secretKey
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "arrowide_github_pat"
        const val FILE_NAME = "github_pat.bin"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val TAG_LENGTH_BITS = 128
        const val IV_SIZE_BYTES = 12
    }
}
