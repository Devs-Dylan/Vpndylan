package com.devsdylan.dylanvpn.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import com.devsdylan.dylanvpn.data.SecureExport

/** Contenu d'une config Dylan. */
data class DylanConfig(
    val name: String,
    val link: String,
    val locked: Boolean,
    val deviceId: String,
    val createdAt: Long,
    /** Identifiant unique de la config (sert à mémoriser son activation). */
    val cid: String = "",
    /** Durée de validité à partir de l'import, en ms (0 = aucune). */
    val durationMs: Long = 0,
    /** Date de fin fixe, en ms depuis 1970 (0 = aucune). */
    val expiresAt: Long = 0,
    /** Limites de débit en octets par seconde (0 = illimité). */
    val downBps: Long = 0,
    val upBps: Long = 0,
    val connBps: Long = 0,
    /** Octets envoyés à pleine vitesse au début de chaque connexion (priorité). */
    val burst: Long = 0,
    /** Quota de données en octets (0 = illimité). */
    val quotaBytes: Long = 0,
    /** Portée du quota : « file » (compteur propre à ce fichier) ou « user » (compteur partagé par toutes les configs du compte). */
    val quotaScope: String = "file",
    /** Nombre maximal de synchronisations de ce fichier sur un appareil (0 = illimité). */
    val maxSyncs: Int = 0,
    val mobileDataOnly: Boolean = false,
    val userMessage: String = "",
    val customServerMessage: String = ""
)

/**
 * Format « dylan:// » : un lien de partage (vless, vmess, trojan, ss) chiffré en AES-256-GCM.
 * Le chiffrement est authentifié : modifier le texte invalide la config.
 */
object DylanFormat {
    const val PREFIX = "dylan://"
    const val SECURE_PREFIX = "dylanx://"
    private const val VERSION = 1
    private val AAD = "DYLAN-CFG-1".toByteArray(Charsets.UTF_8)

    // Clé de l'application (identique dans tous les exemplaires de l'app).
    private val appKey: ByteArray by lazy {
        val parts = listOf("Dy1an", "VPN", "cfg", "k3y", "Tecno", "2026", "v1")
        MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray(Charsets.UTF_8))
    }

    fun isDylan(text: String): Boolean = text.trim().startsWith(PREFIX, ignoreCase = true) ||
        text.trim().startsWith(SECURE_PREFIX, ignoreCase = true)

    fun encode(config: DylanConfig): String = PREFIX + b64(seal(appKey, serialize(config)))

    fun decode(text: String, passphrase: String = ""): DylanConfig {
        if (text.trim().startsWith(SECURE_PREFIX, ignoreCase = true)) return SecureExport.decode(text, passphrase)
        val body = text.trim().substring(PREFIX.length)
        val data = try {
            unb64(body)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Lien dylan:// illisible")
        }
        return open(appKey, data) ?: throw IllegalArgumentException("Config Dylan invalide ou modifiée")
    }

    /** Variantes avec une clé fournie (config par défaut protégée par login et mot de passe). */
    fun sealWith(key: ByteArray, config: DylanConfig): String = b64(seal(key, serialize(config)))

    fun openWith(key: ByteArray, text: String): DylanConfig? =
        try {
            open(key, unb64(text))
        } catch (e: IllegalArgumentException) {
            null
        }

    // ----- identifiant d'appareil -----

    /** Identifiant lisible de l'appareil, de la forme DYL-XXXX-XXXX-XXXX-XXXX. */
    fun deviceIdFromRaw(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("dylan-device|$raw".toByteArray(Charsets.UTF_8))
        val hex = digest.take(8).joinToString("") { String.format("%02X", it) }
        return "DYL-" + hex.chunked(4).joinToString("-")
    }

    fun normalizeId(id: String): String = id.uppercase().filter { it.isLetterOrDigit() }

    fun isValidId(id: String): Boolean = Regex("DYL[0-9A-F]{16}").matches(normalizeId(id))

    // ----- interne -----

    internal fun serialize(c: DylanConfig): ByteArray =
        JSONObject()
            .put("n", c.name)
            .put("l", c.link)
            .put("k", if (c.locked) 1 else 0)
            .put("d", c.deviceId)
            .put("t", c.createdAt)
            .put("i", c.cid)
            .put("dm", c.durationMs)
            .put("ex", c.expiresAt)
            .put("ld", c.downBps)
            .put("lu", c.upBps)
            .put("lc", c.connBps)
            .put("lb", c.burst)
            .put("qb", c.quotaBytes)
            .put("qs", c.quotaScope)
            .put("ms", c.maxSyncs)
            .put("mo", c.mobileDataOnly)
            .put("um", c.userMessage)
            .put("sm", c.customServerMessage)
            .toString()
            .toByteArray(Charsets.UTF_8)

    private fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(plain)
        val out = ByteArray(1 + iv.size + encrypted.size)
        out[0] = VERSION.toByte()
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(encrypted, 0, out, 1 + iv.size, encrypted.size)
        return out
    }

    internal fun deserialize(plain: ByteArray): DylanConfig? {
        return try {
            val json = JSONObject(String(plain, Charsets.UTF_8))
            val link = json.optString("l", "")
            if (link.isBlank()) return null
            DylanConfig(
                name = json.optString("n", ""), link = link,
                locked = json.optInt("k", 0) == 1, deviceId = json.optString("d", ""),
                createdAt = json.optLong("t", 0L), cid = json.optString("i", ""),
                durationMs = json.optLong("dm", 0L), expiresAt = json.optLong("ex", 0L),
                downBps = json.optLong("ld", 0L), upBps = json.optLong("lu", 0L),
                connBps = json.optLong("lc", 0L), burst = json.optLong("lb", 0L),
                quotaBytes = json.optLong("qb", 0L), quotaScope = json.optString("qs", "file").ifBlank { "file" },
                maxSyncs = json.optInt("ms", 0), mobileDataOnly = json.optBoolean("mo", false),
                userMessage = json.optString("um", ""), customServerMessage = json.optString("sm", "")
            )
        } catch (e: Exception) { null }
    }

    private fun open(key: ByteArray, data: ByteArray): DylanConfig? {
        if (data.size < 1 + 12 + 16 || data[0].toInt() != VERSION) return null
        return try {
            val iv = data.copyOfRange(1, 13)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(AAD)
            val plain = cipher.doFinal(data, 13, data.size - 13)
            deserialize(plain)
        } catch (e: Exception) {
            null
        }
    }

    internal fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    internal fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trim())
}
