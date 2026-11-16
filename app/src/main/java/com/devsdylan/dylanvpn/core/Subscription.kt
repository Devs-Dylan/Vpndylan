package com.devsdylan.dylanvpn.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** Abonnement : une adresse web qui renvoie une liste de liens (en clair ou encodée en base64). */
object Subscription {
    private const val MAX_BYTES = 1_000_000

    fun fetch(url: String): String {
        val clean = url.trim()
        if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) {
            throw IllegalArgumentException("L'adresse doit commencer par http:// ou https://")
        }
        val conn = URL(clean).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "DylanVPN")
            if (conn.responseCode != 200) {
                throw IllegalArgumentException("Le serveur a répondu " + conn.responseCode)
            }
            val bytes = conn.inputStream.use { it.readNBytes(MAX_BYTES) }
            return decode(String(bytes, Charsets.UTF_8))
        } catch (e: IOException) {
            throw IllegalArgumentException("Impossible de joindre l'adresse (vérifie ta connexion Internet)")
        } finally {
            conn.disconnect()
        }
    }

    /** Texte en clair s'il contient déjà des liens, sinon tentative de décodage base64. */
    fun decode(text: String): String {
        val trimmed = text.trim()
        if (trimmed.contains("://")) return trimmed
        return try {
            String(Base64.getMimeDecoder().decode(trimmed.replace('-', '+').replace('_', '/')), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Contenu d'abonnement illisible")
        }
    }
}
