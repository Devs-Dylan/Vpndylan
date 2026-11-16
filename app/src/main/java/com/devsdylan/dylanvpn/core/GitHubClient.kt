package com.devsdylan.dylanvpn.core

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import org.json.JSONObject

class GitHubException(val code: Int, message: String) : Exception(message)

/** Petit client de l'API « contents » de GitHub (lecture et écriture de fichiers texte). */
class GitHubClient(
    private val owner: String,
    private val repo: String,
    private val branch: String,
    private val token: String,
    private val apiBase: String = "https://api.github.com"
) {
    class RemoteFile(val text: String, val sha: String)

    /** Retourne le fichier, ou null s'il n'existe pas. */
    fun getFile(path: String): RemoteFile? {
        val (code, body) = request("GET", "/contents/$path?ref=$branch", null)
        if (code == 404) return null
        if (code != 200) throw GitHubException(code, describe(code))
        val json = JSONObject(body)
        val content = json.optString("content", "").replace("\n", "").replace("\r", "")
        val text = String(Base64.getDecoder().decode(content), Charsets.UTF_8)
        return RemoteFile(text, json.optString("sha", ""))
    }

    /** Crée ou met à jour un fichier. [sha] = version actuelle (null pour un nouveau fichier). Retourne le nouveau sha. */
    fun putFile(path: String, text: String, message: String, sha: String?): String {
        val body = JSONObject()
            .put("message", message)
            .put("content", Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
            .put("branch", branch)
        if (sha != null) body.put("sha", sha)
        val (code, resp) = request("PUT", "/contents/$path", body.toString())
        if (code != 200 && code != 201) throw GitHubException(code, describe(code))
        return JSONObject(resp).optJSONObject("content")?.optString("sha", "") ?: ""
    }

    /** Crée la branche de données à partir de [base] si elle n'existe pas encore. */
    fun ensureBranch(base: String) {
        val (code, _) = request("GET", "/git/ref/heads/$branch", null)
        if (code == 200) return
        if (code != 404) throw GitHubException(code, describe(code))
        val (baseCode, baseBody) = request("GET", "/git/ref/heads/$base", null)
        if (baseCode != 200) throw GitHubException(baseCode, "Branche « $base » introuvable : " + describe(baseCode))
        val sha = JSONObject(baseBody).optJSONObject("object")?.optString("sha", "") ?: ""
        val body = JSONObject().put("ref", "refs/heads/$branch").put("sha", sha)
        val (createCode, _) = request("POST", "/git/refs", body.toString())
        if (createCode != 201 && createCode != 200) throw GitHubException(createCode, describe(createCode))
    }

    class RepoInfo(val fullName: String, val isPrivate: Boolean, val canPush: Boolean)

    /** Informations sur le dépôt et droits du jeton (écriture ou non). */
    fun repoInfo(): RepoInfo {
        val (code, body) = request("GET", "", null)
        if (code != 200) throw GitHubException(code, describe(code))
        val json = JSONObject(body)
        val push = json.optJSONObject("permissions")?.opt("push") == true
        return RepoInfo(json.optString("full_name", "$owner/$repo"), json.opt("private") == true, push)
    }

    /** Vérifie l'accès au dépôt. Retourne « nom (privé|public) ». */
    fun checkAccess(): String {
        val (code, body) = request("GET", "", null)
        if (code != 200) throw GitHubException(code, describe(code))
        val json = JSONObject(body)
        val isPrivate = json.opt("private") == true
        return json.optString("full_name", "$owner/$repo") + if (isPrivate) " (privé)" else " (PUBLIC)"
    }

    private fun describe(code: Int): String =
        when (code) {
            401 -> "Jeton GitHub invalide ou expiré"
            403 -> "Accès refusé (droits du jeton insuffisants ou limite d'appels atteinte)"
            404 -> "Dépôt ou fichier introuvable (vérifie le nom du dépôt et les droits du jeton)"
            409, 422 -> "Conflit : le fichier a changé entre-temps, réessaie"
            else -> "Erreur GitHub ($code)"
        }

    private fun request(method: String, path: String, body: String?): Pair<Int, String> {
        val url = URL("$apiBase/repos/$owner/$repo$path")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "DylanVPN")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Pair(code, text)
        } catch (e: IOException) {
            throw GitHubException(0, "Impossible de joindre GitHub (vérifie ta connexion Internet)")
        } finally {
            conn.disconnect()
        }
    }

    class ReleaseAsset(val id: Long, val name: String, val size: Long, val createdAt: String)

    /** Fichier .apk de la publication [tag], ou null s'il n'y en a pas. */
    fun latestApk(tag: String): ReleaseAsset? {
        val (code, body) = request("GET", "/releases/tags/$tag", null)
        if (code == 404) return null
        if (code != 200) throw GitHubException(code, describe(code))
        val assets = org.json.JSONObject(body).optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            if (name.endsWith(".apk", ignoreCase = true)) {
                return ReleaseAsset(a.optLong("id", 0L), name, a.optLong("size", 0L), a.optString("created_at", ""))
            }
        }
        return null
    }

    /** Télécharge un fichier de publication (le dépôt peut être privé : jeton requis, redirection suivie sans jeton). */
    fun downloadAsset(id: Long, target: File, onProgress: (Long, Long) -> Unit) {
        val first = URL("$apiBase/repos/$owner/$repo/releases/assets/$id")
        var conn = first.openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/octet-stream")
            conn.setRequestProperty("User-Agent", "DylanVPN")
            var code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: throw GitHubException(code, "Redirection sans adresse")
                conn.disconnect()
                // l'adresse signée ne doit pas recevoir le jeton GitHub
                conn = URL(location).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "DylanVPN")
                code = conn.responseCode
            }
            if (code != 200) throw GitHubException(code, describe(code))
            val total = conn.contentLengthLong
            target.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        } catch (e: IOException) {
            throw GitHubException(0, "Téléchargement interrompu (vérifie ta connexion Internet)")
        } finally {
            conn.disconnect()
        }
    }
}
