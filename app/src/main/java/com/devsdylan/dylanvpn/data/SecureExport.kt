package com.devsdylan.dylanvpn.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.devsdylan.dylanvpn.core.DylanConfig
import com.devsdylan.dylanvpn.core.DylanFormat
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import java.security.spec.AlgorithmParameterSpec
import java.security.SecureRandom

/** Export autonome dylanx : AES-GCM pour le contenu, clés AES protégées par RSA-OAEP ou passphrase. */
object SecureExport {
    private const val ALIAS = "dylanvpn_export_rsa_v1"
    private const val ITERATIONS = 210_000
    private val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

    fun publicKey(): String {
        val pair = keyPair()
        return "DYLAN-RSA-PUBLIC:" + Base64.getEncoder().encodeToString(pair.public.encoded)
    }

    fun fingerprint(): String = sha256(keyPair().public.encoded).take(24).chunked(4).joinToString("-")

    private fun keyPair(): java.security.KeyPair {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getCertificate(ALIAS)
        if (existing != null) return java.security.KeyPair(existing.publicKey, store.getKey(ALIAS, null) as java.security.PrivateKey)
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .build()
        )
        return generator.generateKeyPair()
    }

    fun encode(config: DylanConfig, recipients: List<String>, includeSelf: Boolean, passphrase: String = "", openToAll: Boolean = false): String {
        val normalized = recipients.map { parsePublicKey(it) }.toMutableList()
        if (includeSelf) normalized.add(keyPair().public)
        if (normalized.isEmpty() && passphrase.isBlank() && !openToAll) {
            throw IllegalArgumentException("Ajoute au moins une clé destinataire ou une phrase secrète")
        }
        val aes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("DYLAN-EXPORT-1".toByteArray())
        val ciphertext = cipher.doFinal(DylanFormat.serialize(config))
        val wraps = JSONArray()
        normalized.distinctBy { fingerprint(it.encoded) }.forEach { pub ->
            val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            rsa.init(Cipher.ENCRYPT_MODE, pub, OAEP)
            wraps.put(JSONObject().put("id", fingerprint(pub.encoded)).put("key", DylanFormat.b64(rsa.doFinal(aes))))
        }
        var salt = ""
        var rounds = 0
        var passWrap = ""
        if (passphrase.isNotBlank()) {
            salt = DylanFormat.b64(ByteArray(16).also { SecureRandom().nextBytes(it) })
            rounds = ITERATIONS
            val derived = derive(passphrase, DylanFormat.unb64(salt), rounds)
            val pc = Cipher.getInstance("AES/GCM/NoPadding")
            pc.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "AES"))
            passWrap = DylanFormat.b64(pc.iv + pc.doFinal(aes))
        }
        var openWrap = ""
        if (openToAll) {
            val openKey = MessageDigest.getInstance("SHA-256").digest("DYLAN-VPN-PUBLIC-OPEN-EXPORT-V1".toByteArray())
            val oc = Cipher.getInstance("AES/GCM/NoPadding")
            oc.init(Cipher.ENCRYPT_MODE, SecretKeySpec(openKey, "AES"))
            openWrap = DylanFormat.b64(oc.iv + oc.doFinal(aes))
        }
        val envelope = JSONObject().put("v", 1).put("iv", DylanFormat.b64(iv))
            .put("data", DylanFormat.b64(ciphertext)).put("recipients", wraps)
            .put("salt", salt).put("rounds", rounds).put("pass", passWrap).put("open", openWrap)
        return DylanFormat.SECURE_PREFIX + DylanFormat.b64(envelope.toString().toByteArray(Charsets.UTF_8))
    }

    fun decode(text: String, passphrase: String = ""): DylanConfig {
        try {
            val raw = String(DylanFormat.unb64(text.trim().substring(DylanFormat.SECURE_PREFIX.length)), Charsets.UTF_8)
            val env = JSONObject(raw)
            if (env.optInt("v") != 1) throw IllegalArgumentException("Version d’export non prise en charge")
            val encryptedKey = (0 until env.getJSONArray("recipients").length()).asSequence()
                .map { env.getJSONArray("recipients").getJSONObject(it) }
                .firstOrNull { it.optString("id") == fingerprint() }
                ?.optString("key")
            val aes = if (encryptedKey != null) {
                val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
                rsa.init(Cipher.DECRYPT_MODE, keyPair().private, OAEP)
                rsa.doFinal(DylanFormat.unb64(encryptedKey))
            } else if (passphrase.isNotBlank() && env.optString("pass").isNotBlank()) {
                val salt = DylanFormat.unb64(env.getString("salt"))
                val rounds = env.optInt("rounds", 0)
                if (rounds !in 100_000..1_000_000) throw IllegalArgumentException("Paramètres de passphrase invalides")
                val blob = DylanFormat.unb64(env.getString("pass"))
                val pc = Cipher.getInstance("AES/GCM/NoPadding")
                pc.init(Cipher.DECRYPT_MODE, SecretKeySpec(derive(passphrase, salt, rounds), "AES"), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
                pc.doFinal(blob, 12, blob.size - 12)
            } else if (env.optString("open").isNotBlank()) {
                val openKey = MessageDigest.getInstance("SHA-256").digest("DYLAN-VPN-PUBLIC-OPEN-EXPORT-V1".toByteArray())
                val blob = DylanFormat.unb64(env.getString("open"))
                val oc = Cipher.getInstance("AES/GCM/NoPadding")
                oc.init(Cipher.DECRYPT_MODE, SecretKeySpec(openKey, "AES"), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
                oc.doFinal(blob, 12, blob.size - 12)
            } else {
                if (env.optString("pass").isBlank())
                    throw IllegalArgumentException("Cet appareil n’est pas autorisé. Demande au créateur ou saisis la passphrase.")
                throw IllegalArgumentException("Passphrase incorrecte ou manquante")
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, DylanFormat.unb64(env.getString("iv"))))
            cipher.updateAAD("DYLAN-EXPORT-1".toByteArray())
            return DylanFormat.deserialize(cipher.doFinal(DylanFormat.unb64(env.getString("data"))))
                ?: throw IllegalArgumentException("Contenu de configuration invalide")
        } catch (e: IllegalArgumentException) { throw e }
        catch (e: Exception) { throw IllegalArgumentException("Export chiffré illisible ou clé incorrecte", e) }
    }

    private fun parsePublicKey(value: String): PublicKey {
        val encoded = value.trim().removePrefix("DYLAN-RSA-PUBLIC:").replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "").replace(Regex("\\s"), "")
        return try {
            java.security.KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded)))
        } catch (e: Exception) { throw IllegalArgumentException("Clé publique RSA invalide : vérifie son format") }
    }

    private fun derive(passphrase: String, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, rounds, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
    private fun fingerprint(bytes: ByteArray): String = sha256(bytes).take(24)
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
