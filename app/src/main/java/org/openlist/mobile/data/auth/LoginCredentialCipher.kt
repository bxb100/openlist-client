package org.openlist.mobile.data.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.openlist.mobile.data.account.AccountId
import java.security.KeyStore
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the reusable login hash encrypted with a device-local, non-exportable key. */
internal class LoginCredentialCipher {
    private val keyStore by lazy {
        KeyStore.getInstance(KEY_STORE).apply { load(null) }
    }

    fun encrypt(passwordHash: String, accountId: AccountId): String {
        require(passwordHash.isNotBlank()) { "Login hash must not be blank" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        cipher.updateAAD(accountId.value.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(passwordHash.toByteArray(Charsets.UTF_8))
        require(cipher.iv.size == NONCE_BYTES) { "Unexpected credential nonce length" }
        return listOf(
            FORMAT_VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP),
        ).joinToString(":")
    }

    fun decrypt(value: String, accountId: AccountId): String {
        val parts = value.split(':')
        require(parts.size == 3 && parts[0] == FORMAT_VERSION) { "Invalid encrypted credential format" }
        val nonce = Base64.decode(parts[1], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[2], Base64.NO_WRAP)
        require(nonce.size == NONCE_BYTES && encrypted.size > TAG_BITS / 8) {
            "Invalid encrypted credential length"
        }
        val key = synchronized(KEY_LOCK) {
            keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                ?: throw GeneralSecurityException("Login credential key is unavailable")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(accountId.value.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
            .also { require(it.isNotBlank()) { "Decrypted login hash must not be blank" } }
    }

    private fun encryptionKey(): SecretKey = synchronized(KEY_LOCK) {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_STORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }
            .generateKey()
    }

    private companion object {
        const val KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "openlist.login_password_hash.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = "v1"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        val KEY_LOCK = Any()
    }
}
