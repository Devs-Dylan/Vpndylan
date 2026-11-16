package com.devsdylan.dylanvpn.core

import com.devsdylan.dylanvpn.data.SecureStore
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chiffrement du chat.
 *  - Salon commun : clé dérivée de la clé « basic » que tous les inscrits possèdent (le serveur, qui connaît cette clé
 *    pour créer les comptes, pourrait donc la dériver : le salon est protégé des autres utilisateurs et du réseau,
 *    pas de l'opérateur du serveur).
 *  - Privés : clé issue d'un échange ECDH (P-256) entre les deux appareils. La clé privée ne quitte jamais l'appareil,
 *    le serveur ne voit que du texte chiffré.
 */
object ChatCrypto {
    private const val PRIV = "chat_priv"
    private const val PUB = "chat_pub"

    /** Clé publique de l'appareil (X.509, base64url). Créée au premier appel. */
    fun publicKeyB64(): String {
        val existing = SecureStore.get(PUB)
        if (existing != null && SecureStore.get(PRIV) != null) return existing
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val pub = b64(pair.public.encoded)
        SecureStore.put(PRIV, b64(pair.private.encoded))
        SecureStore.put(PUB, pub)
        return pub
    }

    fun clearKeys() {
        SecureStore.put(PRIV, null)
        SecureStore.put(PUB, null)
    }

    fun roomKey(basicKey: ByteArray): ByteArray = hmac(basicKey, "dylan-chat-room-v1")

    /** Clé d'une conversation privée entre `me` et `peer`, à partir de la clé publique de `peer`. */
    fun dmKey(peerPublicB64: String, me: String, peer: String): ByteArray {
        val privateText = SecureStore.get(PRIV) ?: throw IllegalStateException("Clé du chat absente")
        val factory = KeyFactory.getInstance("EC")
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(unb64(privateText)))
        val peerKey = factory.generatePublic(X509EncodedKeySpec(unb64(peerPublicB64)))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peerKey, true)
        val (first, second) = listOf(me, peer).sorted()
        return hmac(agreement.generateSecret(), "dylan-chat-dm-v1|$first|$second")
    }

    fun aadGeneral(from: String): String = "dylan-chat|general|$from"

    fun aadDm(from: String, to: String): String = "dylan-chat|dm|$from|$to"

    /** Chiffre un texte : base64url(iv + chiffré). L'AAD lie le message à son expéditeur et son destinataire. */
    fun seal(key: ByteArray, aad: String, text: String): String {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return b64(iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    /** Déchiffre ; null si la clé ou l'AAD ne correspondent pas (message modifié ou mal attribué). */
    fun open(key: ByteArray, aad: String, data: String): String? {
        return try {
            val raw = unb64(data)
            if (raw.size < 12 + 16) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun hmac(key: ByteArray, text: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(text.toByteArray(Charsets.UTF_8))
    }

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trim())
}
