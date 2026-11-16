package com.devsdylan.dylanvpn.core

import com.devsdylan.dylanvpn.data.Account
import com.devsdylan.dylanvpn.data.SecureStore
import com.devsdylan.dylanvpn.util.Format
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

data class ChatMessage(val id: Long, val from: String, val to: String?, val ts: Long, val text: String)

data class AdminChatUser(val name: String, val online: Boolean)

/**
 * État du chat : salon commun et messages privés, récupérés par interrogation régulière du serveur.
 * Toutes les fonctions réseau sont bloquantes : à appeler depuis Dispatchers.IO.
 */
object ChatRepository {
    private val _general = MutableStateFlow<List<ChatMessage>>(emptyList())
    val general: StateFlow<List<ChatMessage>> = _general

    private val _direct = MutableStateFlow<List<ChatMessage>>(emptyList())
    val direct: StateFlow<List<ChatMessage>> = _direct

    private val _users = MutableStateFlow<List<String>>(emptyList())
    val users: StateFlow<List<String>> = _users

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private var sinceGeneral = 0L
    private var sinceDirect = 0L
    private val publicKeys = HashMap<String, String>()

    @Volatile
    private var keyPublished = false

    private const val MAX_BYTES = 3000
    private const val KEEP = 500

    fun hasChat(): Boolean = AuthClient.chatToken() != null && AuthClient.chatUser() != null

    fun me(): String = AuthClient.chatUser() ?: ""

    fun reset() {
        _general.value = emptyList()
        _direct.value = emptyList()
        _users.value = emptyList()
        _status.value = ""
        sinceGeneral = 0
        sinceDirect = 0
        publicKeys.clear()
        keyPublished = false
    }

    /** Appel authentifié. Retourne null si le chat est indisponible ou si la session a été ouverte ailleurs. */
    private fun call(path: String, build: (JSONObject) -> Unit = {}): Pair<Int, JSONObject>? {
        val token = AuthClient.chatToken()
        val user = AuthClient.chatUser()
        if (token == null || user == null) {
            _status.value = "Chat indisponible : il est réservé aux comptes créés depuis l'application"
            return null
        }
        val body = JSONObject().put("name", user).put("token", token)
        build(body)
        return try {
            val result = AuthClient.post(path, body)
            if (result.first == 401 && result.second.optString("error") == "session") {
                Account.logout("Ce compte vient d'être ouvert sur un autre appareil.")
                null
            } else {
                result
            }
        } catch (e: IOException) {
            _status.value = "Pas de connexion au serveur"
            null
        } catch (e: Exception) {
            _status.value = "Erreur inattendue du chat"
            null
        }
    }

    private fun publishKey() {
        if (keyPublished) return
        val pub = ChatCrypto.publicKeyB64()
        val result = call("/chat/key/put") { it.put("pub", pub) }
        if (result != null && result.first == 200) keyPublished = true
    }

    private fun publicKeyOf(peer: String): String? {
        publicKeys[peer]?.let { return it }
        val result = call("/chat/key/get") { it.put("user", peer) } ?: return null
        if (result.first != 200) return null
        val pub = result.second.optString("pub")
        if (pub.isEmpty()) return null
        publicKeys[peer] = pub
        return pub
    }

    /** Récupère les nouveaux messages et les déchiffre. */
    fun poll() {
        publishKey()
        val me = me()
        val result = call("/chat/poll") { it.put("sinceGeneral", sinceGeneral).put("sinceDm", sinceDirect) } ?: return
        if (result.first != 200) {
            _status.value = result.second.optString("error", "Erreur du chat")
            return
        }
        _status.value = ""
        val array = result.second.optJSONArray("messages") ?: return
        val newGeneral = ArrayList<ChatMessage>()
        val newDirect = ArrayList<ChatMessage>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val id = item.getLong("id")
            val sender = item.optString("sender")
            val recipient = if (item.isNull("recipient")) null else item.optString("recipient")
            val ts = item.optLong("ts")
            val body = item.optString("body")
            if (item.optString("room") == "general") {
                if (id > sinceGeneral) sinceGeneral = id
                val basic = Account.keys["basic"]
                val text = basic?.let { ChatCrypto.open(ChatCrypto.roomKey(it), ChatCrypto.aadGeneral(sender), body) }
                newGeneral.add(ChatMessage(id, sender, null, ts, text ?: "[message illisible]"))
            } else {
                if (id > sinceDirect) sinceDirect = id
                val to = recipient ?: continue
                val peer = if (sender == me) to else sender
                val text = try {
                    publicKeyOf(peer)?.let { ChatCrypto.open(ChatCrypto.dmKey(it, me, peer), ChatCrypto.aadDm(sender, to), body) }
                } catch (e: Exception) {
                    null
                }
                newDirect.add(ChatMessage(id, sender, to, ts, text ?: "[message illisible]"))
            }
        }
        if (newGeneral.isNotEmpty()) _general.value = (_general.value + newGeneral).takeLast(KEEP)
        if (newDirect.isNotEmpty()) _direct.value = (_direct.value + newDirect).takeLast(KEEP)
    }

    fun refreshUsers() {
        val result = call("/chat/users") ?: return
        if (result.first != 200) return
        val array = result.second.optJSONArray("users") ?: return
        _users.value = (0 until array.length()).map { array.getString(it) }
    }

    /** `to` vaut « general » ou le nom d'un utilisateur. Retourne un message d'erreur, ou null si c'est envoyé. */
    fun send(to: String, text: String): String? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        if (clean.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return "Message trop long"
        val me = me()
        if (me.isEmpty()) return "Chat indisponible"
        val sealed = try {
            if (to == "general") {
                val basic = Account.keys["basic"] ?: return "Clé du salon indisponible : reconnecte-toi"
                ChatCrypto.seal(ChatCrypto.roomKey(basic), ChatCrypto.aadGeneral(me), clean)
            } else {
                val pub = publicKeyOf(to) ?: return "Cet utilisateur n'a pas encore ouvert le chat"
                ChatCrypto.seal(ChatCrypto.dmKey(pub, me, to), ChatCrypto.aadDm(me, to), clean)
            }
        } catch (e: Exception) {
            return "Chiffrement impossible"
        }
        val result = call("/chat/send") { it.put("to", to).put("body", sealed) } ?: return "Message non envoyé"
        return if (result.first == 200) null else result.second.optString("error", "Envoi impossible")
    }

    // ----- administration : métadonnées des comptes, jamais le contenu des messages -----

    private const val ADMIN_KEY = "admin_key"

    fun adminKey(): String? = SecureStore.get(ADMIN_KEY)

    fun setAdminKey(key: String?) = SecureStore.put(ADMIN_KEY, key?.trim()?.ifEmpty { null })

    private fun adminCall(path: String, body: JSONObject): Pair<Int, JSONObject>? {
        val key = adminKey() ?: return null
        return try {
            AuthClient.post(path, body, mapOf("x-admin-key" to key))
        } catch (e: Exception) {
            null
        }
    }

    fun adminUsers(): List<AdminChatUser>? {
        val result = adminCall("/admin/users", JSONObject()) ?: return null
        if (result.first != 200) return null
        val array = result.second.optJSONArray("users") ?: return emptyList()
        return (0 until array.length()).map {
            val item = array.getJSONObject(it)
            AdminChatUser(item.optString("name"), !item.isNull("lastSession"))
        }
    }

    /** Texte des informations d'un compte, ou un message d'erreur. */
    fun adminUserInfo(name: String): String {
        val result = adminCall("/admin/user", JSONObject().put("name", name)) ?: return "Clé admin absente ou serveur injoignable"
        if (result.first != 200) return result.second.optString("error", "Erreur du serveur")
        val u = result.second
        val last = u.optLong("lastLogin")
        return "Nom : " + u.optString("name") +
            "\nNuméro : " + u.optString("phoneMasked") +
            "\nAppareil d'inscription : " + u.optString("deviceId") +
            "\nInscrit le : " + Format.dateTime(u.optLong("createdAt")) +
            "\nDernière connexion : " + (if (last > 0) Format.dateTime(last) else "jamais") +
            "\nConnecté : " + (if (u.optBoolean("online")) "oui" else "non") +
            (if (u.optBoolean("online") && u.optString("sessionDevice").isNotEmpty()) "\nAppareil actuel : " + u.optString("sessionDevice") else "")
    }
}
