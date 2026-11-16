package com.devsdylan.dylanvpn.core

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Secrets de l'application. Le mot de passe admin n'est pas stocké en clair (seulement son empreinte),
 * et la config par défaut n'est stockée que chiffrée avec le login et le mot de passe.
 */
object Secrets {
    private const val ITERATIONS = 100_000

    /** Compte intégré (admin seulement) : identifiant, catégorie, sel et empreinte du mot de passe. */
    private class Builtin(val login: String, val category: String, val salt: String, val hash: String)

    private val builtins = listOf(
        Builtin("admin", "admin", "245814b8a40668e137707732c34bada3", "77fd75853406a3769d0251c877b8a1ddad048ae640860eac7271ef1d4af8de3f")
    )

    fun pbkdf2(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    /** Catégorie d'un compte intégré si l'identifiant et le mot de passe sont bons, sinon null. */
    fun builtinCategory(login: String, password: String): String? {
        val name = Keyring.normalizeLogin(login)
        val account = builtins.firstOrNull { it.login == name } ?: return null
        val computed = pbkdf2(name + ":" + password, fromHex(account.salt))
        return if (MessageDigest.isEqual(computed, fromHex(account.hash))) account.category else null
    }

    /** Catégorie d'un compte intégré à partir de son identifiant seul (sans vérifier le mot de passe). */
    fun builtinCategoryOf(login: String): String? =
        builtins.firstOrNull { it.login == Keyring.normalizeLogin(login) }?.category

    fun hashAccount(login: String, password: String, salt: ByteArray): String =
        toHex(pbkdf2(Keyring.normalizeLogin(login) + ":" + password, salt))

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { String.format("%02x", it) }

    fun fromHex(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
