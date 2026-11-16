package com.devsdylan.dylanvpn.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stockage de secrets (jeton GitHub, clés de session) chiffrés avec une clé du Keystore Android. */
object SecureStore {
    private const val ALIAS = "dylanvpn_secure_v1"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("dylanvpn_secure", Context.MODE_PRIVATE)
        }
    }

    fun put(name: String, value: String?) {
        val p = prefs ?: return
        if (value == null) {
            p.edit().remove(name).apply()
            return
        }
        try {
            p.edit().putString(name, encrypt(value)).apply()
        } catch (e: Exception) {
            AppLog.e("Stockage sécurisé indisponible : " + e.message)
        }
    }

    fun get(name: String): String? {
        val stored = prefs?.getString(name, null) ?: return null
        return try {
            decrypt(stored)
        } catch (e: Exception) {
            null
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    private fun decrypt(blob: String): String {
        val data = Base64.getDecoder().decode(blob)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }
}
