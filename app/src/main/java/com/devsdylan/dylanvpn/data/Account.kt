package com.devsdylan.dylanvpn.data

import android.content.Context
import android.content.SharedPreferences
import com.devsdylan.dylanvpn.core.GitHubException
import com.devsdylan.dylanvpn.core.Keyring
import com.devsdylan.dylanvpn.core.Secrets
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

data class Session(val login: String, val category: String)

/** Compte connecté : identifiant, catégorie (basic, pro, admin) et clés de sa catégorie. */
object Account {
    private var prefs: SharedPreferences? = null

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session

    /** Message affiché sur l'écran de connexion après une déconnexion forcée. */
    private val _notice = MutableStateFlow("")
    val notice: StateFlow<String> = _notice

    @Volatile
    var keys: Map<String, ByteArray> = emptyMap()
        private set

    private var entryHash: String = ""

    val isAdmin: Boolean get() = _session.value?.category == "admin"

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences("dylanvpn_account", Context.MODE_PRIVATE)
        prefs = p
        val login = p.getString("login", null)
        val category = p.getString("category", null)
        if (login != null && category != null) {
            _session.value = Session(login, category)
            entryHash = p.getString("entry", "") ?: ""
            keys = decodeKeys(SecureStore.get("session_keys"))
        }
    }

    /** Vérifie l'identifiant et le mot de passe. Retourne un message d'erreur, ou null en cas de succès. */
    fun login(loginInput: String, password: String): String? {
        val login = Keyring.normalizeLogin(loginInput)
        if (login.isEmpty() || password.isEmpty()) return "Saisis ton identifiant et ton mot de passe"

        val builtin = Secrets.builtinCategory(login, password)
        var grant: Keyring.Grant? = null
        var hash = ""
        var networkProblem: String? = null
        try {
            val service = Repository.readService()
            val users = service.fetchUsers()
            if (users == null) {
                networkProblem = "le dépôt de configs n'est pas encore initialisé"
            } else {
                val line = users.lines.firstOrNull { it.login == login }
                if (line != null) {
                    grant = Keyring.unwrap(line, password)
                    hash = lineHash(line)
                }
            }
        } catch (e: GitHubException) {
            networkProblem = e.message
        }

        val category = builtin ?: grant?.category
        if (category == null) {
            return if (networkProblem != null && Secrets.builtinCategoryOf(login) == null) {
                "Impossible de vérifier ton compte : $networkProblem"
            } else {
                "Identifiant ou mot de passe incorrect. Réessaie."
            }
        }

        keys = grant?.keys ?: emptyMap()
        entryHash = hash
        _session.value = Session(login, category)
        _notice.value = ""
        persist()
        if (grant == null) {
            AppLog.w("Connecté sans accès au dépôt de configs" + (networkProblem?.let { " ($it)" } ?: ""))
        }
        AppLog.i("Connecté : $login ($category)")
        Repository.syncAsync()
        return null
    }

    fun logout(reason: String = "") {
        _session.value = null
        keys = emptyMap()
        entryHash = ""
        _notice.value = reason
        prefs?.edit()?.clear()?.apply()
        SecureStore.put("session_keys", null)
        AppLog.i("Déconnecté du compte")
    }

    /** Remplace les clés de la session (après l'initialisation du dépôt par l'admin). */
    fun updateKeys(newKeys: Map<String, ByteArray>, line: Keyring.UserLine?) {
        keys = newKeys
        if (line != null) entryHash = lineHash(line)
        persist()
    }

    /**
     * Vérifie que le compte existe toujours et que ses accès n'ont pas changé.
     * Retourne false (et déconnecte) si l'administrateur a supprimé ou modifié le compte.
     */
    fun verify(lines: List<Keyring.UserLine>): Boolean {
        val s = _session.value ?: return false
        val line = lines.firstOrNull { it.login == s.login }
        if (line == null) {
            if (Secrets.builtinCategoryOf(s.login) != null) return true
            logout("Ton compte a été supprimé.")
            return false
        }
        if (entryHash.isNotEmpty() && lineHash(line) != entryHash) {
            logout("Tes accès ont changé : reconnecte-toi.")
            return false
        }
        if (entryHash.isEmpty()) {
            // première synchronisation après une connexion hors ligne : on mémorise l'état actuel
            entryHash = lineHash(line)
            persist()
        }
        return true
    }

    private fun persist() {
        val s = _session.value ?: return
        prefs?.edit()
            ?.putString("login", s.login)
            ?.putString("category", s.category)
            ?.putString("entry", entryHash)
            ?.apply()
        SecureStore.put("session_keys", encodeKeys(keys))
    }

    private fun lineHash(line: Keyring.UserLine): String {
        val raw = line.login + "|" + line.category + "|" + line.salt + "|" + line.blob
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { String.format("%02x", it) }
    }

    private fun encodeKeys(map: Map<String, ByteArray>): String {
        val json = JSONObject()
        for ((name, key) in map) json.put(name, Base64.getEncoder().encodeToString(key))
        return json.toString()
    }

    private fun decodeKeys(text: String?): Map<String, ByteArray> {
        if (text == null) return emptyMap()
        return try {
            val json = JSONObject(text)
            val out = LinkedHashMap<String, ByteArray>()
            val names = json.keys()
            while (names.hasNext()) {
                val name = names.next()
                out[name] = Base64.getDecoder().decode(json.optString(name, ""))
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
