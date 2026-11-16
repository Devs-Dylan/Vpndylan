package com.devsdylan.dylanvpn.core

import com.devsdylan.dylanvpn.BuildConfig
import com.devsdylan.dylanvpn.data.Store
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Dialogue avec le serveur d'inscription (jamais de jeton GitHub ici : il reste côté serveur). */
object AuthClient {
    class Result(val ok: Boolean, val message: String)

    val configured: Boolean get() = BuildConfig.SERVER_URL.isNotEmpty()

    private fun post(path: String, body: JSONObject): Pair<Int, JSONObject> {
        val base = BuildConfig.SERVER_URL.trimEnd('/')
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
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
}
