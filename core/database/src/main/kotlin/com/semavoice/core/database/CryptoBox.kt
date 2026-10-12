package com.semavoice.core.database

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption with a non-exportable key in the Android Keystore. Used for
 * transcript/translation text stored in Room so a copied database file is unreadable.
 * Ciphertext format: base64( 12-byte IV || GCM ciphertext+tag ).
 */
class CryptoBox(private val alias: String = "sema_content_key") {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    private fun key(): SecretKey {
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        if (encoded.isEmpty()) return ""
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        return String(cipher.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }

    /** Wipes the key; existing ciphertext becomes permanently unreadable ("clear all data"). */
    fun destroyKey() { runCatching { keyStore.deleteEntry(alias) } }
}

/** Test seam so unit tests can run without the Android Keystore. */
interface TextCipher {
    fun encrypt(plain: String): String
    fun decrypt(encoded: String): String
}

class KeystoreTextCipher(private val box: CryptoBox = CryptoBox()) : TextCipher {
    override fun encrypt(plain: String) = box.encrypt(plain)
    override fun decrypt(encoded: String) = box.decrypt(encoded)
}

object PlainTextCipher : TextCipher {
    override fun encrypt(plain: String) = plain
    override fun decrypt(encoded: String) = encoded
}
