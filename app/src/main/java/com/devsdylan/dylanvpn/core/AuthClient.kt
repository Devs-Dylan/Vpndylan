package com.devsdylan.dylanvpn.core

import com.devsdylan.dylanvpn.BuildConfig
import com.devsdylan.dylanvpn.data.SecureStore
import com.devsdylan.dylanvpn.data.Store
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Dialogue avec le serveur d'inscription (jamais de jeton GitHub ici : il reste côté serveur). */
object AuthClient {
    class Result(val ok: Boolean, val message: String)

    val configured: Boolean get() = BuildConfig.SERVER_URL.isNotEmpty()

    fun post(path: String, body: JSONObject, headers: Map<String, String> = emptyMap()): Pair<Int, JSONObject> {
        val base = BuildConfig.SERVER_URL.trimEnd('/')
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            for ((name, value) in headers) conn.setRequestProperty(name, value)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                JSONObject()
            }
            return Pair(code, json)
        } finally {
            conn.disconnect()
        }
    }

    private fun guarded(block: () -> Result): Result {
        if (!configured) return Result(false, "Inscription indisponible : serveur non configuré dans cette version")
        return try {
            block()
        } catch (e: IOException) {
            Result(false, "Pas de connexion au serveur d'inscription")
        } catch (e: Exception) {
            Result(false, "Erreur inattendue pendant l'inscription")
        }
    }

    /** Le nom est-il libre ? */
    fun checkName(name: String): Result = guarded {
        val (code, json) = post("/check", JSONObject().put("name", name))
        when {
            code != 200 -> Result(false, json.optString("error", "Erreur du serveur"))
            json.optBoolean("available") -> Result(true, "Nom disponible")
            else -> Result(false, json.optString("reason", "Nom indisponible"))
        }
    }

    /** Crée le compte. Le numéro est haché côté serveur ; l'identifiant d'appareil est celui de Store. */
    fun register(name: String, password: String, phone: String): Result = guarded {
        val body = JSONObject()
            .put("name", name)
            .put("password", password)
            .put("phone", phone)
            .put("deviceId", Store.deviceId())
        val (code, json) = post("/register", body)
        if (code == 200 && json.optBoolean("ok")) Result(true, "Compte créé") else Result(false, json.optString("error", "Inscription refusée"))
    }

    // ----- session du chat : le jeton remplace celui de l'ancien appareil (une seule connexion par compte) -----

    private const val TOKEN = "chat_token"
    private const val USER = "chat_user"

    fun chatToken(): String? = SecureStore.get(TOKEN)

    fun chatUser(): String? = SecureStore.get(USER)

    /** Ouvre la session du chat. Échoue sans conséquence pour les comptes qui n'ont pas été créés depuis l'app. */
    fun login(name: String, password: String): Result = guarded {
        val login = Keyring.normalizeLogin(name)
        val body = JSONObject().put("name", login).put("password", password).put("deviceId", Store.deviceId())
        val (code, json) = post("/login", body)
        if (code == 200 && json.optBoolean("ok") && json.optString("token").isNotEmpty()) {
            SecureStore.put(TOKEN, json.optString("token"))
            SecureStore.put(USER, login)
            Result(true, "")
        } else {
            Result(false, json.optString("error", "Connexion au chat impossible"))
        }
    }

    fun clearChat() {
        SecureStore.put(TOKEN, null)
        SecureStore.put(USER, null)
        ChatCrypto.clearKeys()
    }
}
