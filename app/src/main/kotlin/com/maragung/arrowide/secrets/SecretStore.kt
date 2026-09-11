package com.maragung.arrowide.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Named secret storage (plan #21) for values that must never sit in plain
 * text inside a project (tokens, API keys, passwords). The ONLY file in the
 * secrets package allowed to touch android.* — no logic beyond framework
 * calls, so it needs no unit tests (name validation is the pure
 * [SecretNameValidation] helper).
 */
interface SecretStore {

    /** The stored value, or null when absent or unreadable. */
    fun get(name: String): String?

    /**
     * Stores [value] encrypted; `set(name, null)` removes the secret. When
     * the last secret is removed the Keystore key is deleted as well.
     */
    fun set(name: String, value: String?)

    /** All stored secret names, sorted. */
    fun names(): List<String>
}

/**
 * [SecretStore] backed by Android Keystore + app-private storage (plan #21),
 * mirroring the GitHub token store (plan #12):
 *
 *  - one AES-256-GCM key (alias `arrowide_secrets`) lives in the
 *    AndroidKeyStore and never leaves it — it is shared by every entry and
 *    only deleted once the last secret is removed;
 *  - each secret is encrypted with a fresh random 12-byte IV;
 *  - one file per secret under `filesDir/secrets/` holds
 *    Base64(IV || ciphertext), NO_WRAP — names are validated by
 *    [SecretNameValidation] so they can never escape that directory;
 *  - writes go through a staging sibling directory and are renamed into
 *    place, so a crash never leaves a half-written entry behind;
 *  - the plaintext value is never on disk, in source, in logs or in the UI
 *    after save. Error messages contain names only.
 */
class AndroidKeystoreSecretStore(
    private val context: Context,
) : SecretStore {

    private val secretsDir: File
        get() = File(context.filesDir, SECRETS_DIR)

    private val stagingDir: File
        get() = File(context.filesDir, STAGING_DIR)

    /** Guards the file operations so a concurrent write is never observed half-done. */
    private val lock = Any()

    override fun get(name: String): String? {
        val safeName = SecretNameValidation.validate(name)
        return synchronized(lock) {
            val file = File(secretsDir, safeName)
            if (!file.isFile) return null
            try {
                val blob = Base64.decode(file.readBytes(), Base64.NO_WRAP)
                if (blob.size <= IV_SIZE_BYTES) return null // corrupt; treat as absent
                val iv = blob.copyOfRange(0, IV_SIZE_BYTES)
                val ciphertext = blob.copyOfRange(IV_SIZE_BYTES, blob.size)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, loadKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
                String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            } catch (e: GeneralSecurityException) {
                null // unreadable (key rotated/corrupt) — behave as absent
            } catch (e: IllegalArgumentException) {
                null // Base64.decode of corrupt data
            } catch (e: IOException) {
                null // unreadable file — behave as absent
            }
        }
    }

    override fun set(name: String, value: String?) {
        val safeName = SecretNameValidation.validate(name)
        synchronized(lock) {
            if (value == null) {
                File(secretsDir, safeName).delete()
                deleteKeyIfEmpty()
                return
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = ByteArray(IV_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val blob = Base64.encode(iv + ciphertext, Base64.NO_WRAP)

            secretsDir.mkdirs()
            stagingDir.mkdirs()
            val staged = File(stagingDir, safeName)
            val target = File(secretsDir, safeName)
            try {
                FileOutputStream(staged).use { output -> output.write(blob) }
                if (!staged.renameTo(target)) {
                    throw IOException("Renaming secret \"$safeName\" into place failed")
                }
            } finally {
                staged.delete()
            }
        }
    }

    override fun names(): List<String> {
        val files = synchronized(lock) {
            val dir = secretsDir
            if (!dir.isDirectory) return emptyList()
            dir.listFiles { file -> file.isFile }
        } ?: return emptyList()
        return files.map { it.name }.sorted()
    }

    /** Removes the shared key once no secret is left (mirrors the token store's `set(null)`). */
    private fun deleteKeyIfEmpty() {
        val dir = secretsDir
        if (dir.isDirectory && !dir.listFiles().isNullOrEmpty()) return
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** The existing key; throws when absent (i.e. only after [set] stored a secret). */
    private fun loadKey(): SecretKey {
        val entry = loadKeyStore().getEntry(KEY_ALIAS, null)
            as? KeyStore.SecretKeyEntry
        return entry?.secretKey
            ?: throw GeneralSecurityException("Secrets key missing from AndroidKeyStore")
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
        const val KEY_ALIAS = "arrowide_secrets"
        const val SECRETS_DIR = "secrets"
        const val STAGING_DIR = "secrets-staging"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val TAG_LENGTH_BITS = 128
        const val IV_SIZE_BYTES = 12
    }
}
