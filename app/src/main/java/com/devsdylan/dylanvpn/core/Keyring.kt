package com.devsdylan.dylanvpn.core

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Trousseau de clés des catégories.
 *  - Chaque catégorie (basic, pro, admin) a une clé AES aléatoire qui chiffre son fichier de configs.
 *  - Pour chaque utilisateur, la ou les clés auxquelles il a droit sont « enveloppées » avec une clé
 *    dérivée de son identifiant et de son mot de passe (PBKDF2). Sans le bon mot de passe, impossible
 *    d'ouvrir l'enveloppe : le dépôt ne contient aucun mot de passe.
 */
object Keyring {
    const val ITERATIONS = 150_000
    val CATEGORIES = listOf("basic", "pro", "admin")

    data class UserLine(val login: String, val category: String, val salt: String, val blob: String)

    class Grant(val category: String, val keys: Map<String, ByteArray>)

    fun newKey(): ByteArray {
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        return key
    }

    fun normalizeLogin(login: String): String = login.trim().lowercase()

    /** Clé du fichier de configs personnelles d'un utilisateur, dérivée de la clé maître « users » (détenue par l'admin). */
    fun personalKey(master: ByteArray, login: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(master, "HmacSHA256"))
        return mac.doFinal(("dylan-personal|" + normalizeLogin(login)).toByteArray(Charsets.UTF_8))
    }

    fun createUserLine(login: String, password: String, grant: Grant): UserLine {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val keysJson = JSONObject()
        for ((name, key) in grant.keys) keysJson.put(name, b64(key))
        val payload = JSONObject().put("c", grant.category).put("k", keysJson).toString().toByteArray(Charsets.UTF_8)
        val login0 = normalizeLogin(login)
        val sealed = gcmEncrypt(deriveKey(login0, password, salt), aad(login0, grant.category), payload)
        return UserLine(login0, grant.category, b64(salt), b64(sealed))
    }

    /** Ouvre l'enveloppe d'un utilisateur ; null si le mot de passe est faux. */
    fun unwrap(line: UserLine, password: String): Grant? {
        return try {
            val (salt, iterations) = splitSalt(line.salt)
            val key = deriveKey(line.login, password, salt, iterations)
            val plain = gcmDecrypt(key, aad(line.login, line.category), unb64(line.blob)) ?: return null
            val json = JSONObject(String(plain, Charsets.UTF_8))
            val keysJson = json.optJSONObject("k") ?: return null
            val keys = LinkedHashMap<String, ByteArray>()
            val names = keysJson.keys()
            while (names.hasNext()) {
                val name = names.next()
                keys[name] = unb64(keysJson.optString(name, ""))
            }
            Grant(json.optString("c", line.category), keys)
        } catch (e: Exception) {
            null
        }
    }

    // ----- clé d'accès : jeton de lecture enveloppé avec l'identifiant et le mot de passe d'un utilisateur -----

    private const val ACCESS_PREFIX = "dylankey-"
    private val ACCESS_AAD = "dylan-access-key-v1".toByteArray(Charsets.UTF_8)

    /** Clé d'accès d'un utilisateur : ne peut être ouverte qu'avec SON identifiant et SON mot de passe. */
    fun createAccessKey(login: String, password: String, readToken: String): String {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val key = deriveKey(normalizeLogin(login), password, salt)
        val sealed = gcmEncrypt(key, ACCESS_AAD, readToken.trim().toByteArray(Charsets.UTF_8))
        return ACCESS_PREFIX + b64(salt + sealed)
    }

    /** Retourne le jeton de lecture, ou null si la clé, l'identifiant ou le mot de passe est faux. */
    fun openAccessKey(login: String, password: String, accessKey: String): String? {
        val text = accessKey.trim()
        if (!text.startsWith(ACCESS_PREFIX)) return null
        return try {
            val data = unb64(text.removePrefix(ACCESS_PREFIX))
            if (data.size < 16 + 12 + 16) return null
            val key = deriveKey(normalizeLogin(login), password, data.copyOfRange(0, 16))
            gcmDecrypt(key, ACCESS_AAD, data.copyOfRange(16, data.size))?.let { String(it, Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        }
    }

    // ----- fichier des utilisateurs : une ligne « login|catégorie|sel|enveloppe » -----

    fun parseUsers(text: String): List<UserLine> {
        val out = ArrayList<UserLine>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split('|')
            if (parts.size != 4) continue
            out.add(UserLine(parts[0], parts[1], parts[2], parts[3]))
        }
        return out
    }

    fun renderUsers(lines: List<UserLine>): String =
        "# DYLAN VPN - utilisateurs (login|categorie|sel|enveloppe)\n" +
            lines.joinToString("\n") { it.login + "|" + it.category + "|" + it.salt + "|" + it.blob } + "\n"

    // ----- fichiers de catégorie -----

    fun encryptFile(key: ByteArray, plain: String): String =
        b64(gcmEncrypt(key, FILE_AAD, plain.toByteArray(Charsets.UTF_8)))

    fun decryptFile(key: ByteArray, text: String): String? =
        try {
            gcmDecrypt(key, FILE_AAD, unb64(text.trim()))?.let { String(it, Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        }

    // ----- interne -----

    private val FILE_AAD = "dylan-file-v1".toByteArray(Charsets.UTF_8)

    private fun aad(login: String, category: String): ByteArray =
        ("dylan-user|" + login + "|" + category).toByteArray(Charsets.UTF_8)

    /**
     * Le champ « sel » d'une ligne peut porter son nombre d'itérations : « sel~100000 ».
     * Sans suffixe, c'est ITERATIONS (anciennes lignes). Le serveur d'inscription utilise 100 000,
     * le maximum accepté par Cloudflare Workers.
     */
    private fun splitSalt(field: String): Pair<ByteArray, Int> {
        val i = field.indexOf('~')
        if (i < 0) return Pair(unb64(field), ITERATIONS)
        val iterations = field.substring(i + 1).toIntOrNull()?.takeIf { it in 10_000..1_000_000 } ?: ITERATIONS
        return Pair(unb64(field.substring(0, i)), iterations)
    }

    private fun deriveKey(login: String, password: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec((login + ":" + password).toCharArray(), salt, iterations, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun gcmEncrypt(key: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plain)
    }

    private fun gcmDecrypt(key: ByteArray, aad: ByteArray, data: ByteArray): ByteArray? {
        if (data.size < 12 + 16) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            cipher.updateAAD(aad)
            cipher.doFinal(data, 12, data.size - 12)
        } catch (e: Exception) {
            null
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trim())
}
