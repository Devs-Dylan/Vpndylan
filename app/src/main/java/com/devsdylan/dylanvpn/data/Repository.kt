package com.devsdylan.dylanvpn.data

import com.devsdylan.dylanvpn.BuildConfig
import com.devsdylan.dylanvpn.core.CoreRuntime
import com.devsdylan.dylanvpn.core.GitHubClient
import com.devsdylan.dylanvpn.core.GitHubException
import com.devsdylan.dylanvpn.core.Keyring
import com.devsdylan.dylanvpn.core.RepoService
import com.devsdylan.dylanvpn.core.Secrets
import com.devsdylan.dylanvpn.vpn.DylanVpnService
import com.devsdylan.dylanvpn.vpn.VpnState
import com.devsdylan.dylanvpn.vpn.VpnStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SyncStatus(val running: Boolean = false, val lastOk: Long = 0, val message: String = "")

/** Dépôt Git privé : utilisateurs et configs par catégorie (basic, pro, admin). */
object Repository {
    const val OWNER = "Devs-Dylan"
    const val REPO = "Vpndylan"
    const val BRANCH = "vpn-data"

    @Volatile
    var appContext: android.content.Context? = null

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    // ----- jetons -----

    fun writeToken(): String = SecureStore.get("git_write_token") ?: ""

    fun hasWriteToken(): Boolean = writeToken().isNotBlank()

    fun setWriteToken(token: String?) {
        val clean = token?.trim().orEmpty()
        SecureStore.put("git_write_token", if (clean.isEmpty()) null else clean)
    }

    private fun client(token: String) = GitHubClient(OWNER, REPO, BRANCH, token)

    /** Jeton disponible : celui de l'admin s'il existe, sinon le jeton de lecture intégré. */
    fun anyToken(): String = writeToken().ifEmpty { BuildConfig.GIT_READ_TOKEN }

    /** Lecture : le jeton d'écriture s'il existe (admin), sinon le jeton de lecture intégré à l'application. */
    fun readService(): RepoService = RepoService(client(anyToken()))

    private fun writeService(): RepoService {
        val token = writeToken()
        if (token.isBlank()) {
            throw IllegalStateException("Ajoute d'abord le jeton GitHub d'écriture (onglet Dépôt Git)")
        }
        return RepoService(client(token))
    }

    // ----- synchronisation -----

    fun syncAsync() {
        Thread { sync() }.start()
    }

    /** Télécharge les configs de la catégorie du compte. Retourne un message d'erreur, ou null. */
    @Synchronized
    fun sync(): String? {
        val session = Account.session.value ?: return "Non connecté"
        _status.value = _status.value.copy(running = true)
        val problem: String? = try {
            val service = readService()
            val users = service.fetchUsers()
                ?: throw IllegalStateException("Le dépôt de configs n'est pas encore initialisé par l'administrateur")
            if (!Account.verify(users.lines)) throw IllegalStateException("Session fermée")
            val key = Account.keys[session.category]
                ?: throw IllegalStateException("Clé de catégorie absente : reconnecte-toi en ligne")
            val lines = ArrayList(service.readCategory(session.category, key))
            // configs réservées à cet utilisateur par l'administrateur
            Account.keys["personal"]?.let { personal ->
                try {
                    lines.addAll(service.readUserConfigs(session.login, personal))
                } catch (e: IllegalStateException) {
                    AppLog.w("Configs personnelles illisibles : " + e.message)
                }
            }
            val activeId = Store.selectedId.value
            Store.applySync(lines)
            val vpnStatus = VpnState.state.value.status
            val vpnActive = vpnStatus != VpnStatus.IDLE && vpnStatus != VpnStatus.ERROR
            if (activeId != null && vpnActive && Store.profiles.value.none { it.id == activeId }) {
                // l'administrateur a retiré la config utilisée : on coupe le VPN
                AppLog.w("La config en cours a été retirée par l'administrateur : déconnexion")
                appContext?.let { DylanVpnService.stop(it) }
            }
            if (Store.settings.value.pingAfterSync && VpnState.state.value.status == VpnStatus.IDLE) {
                // le test se fait sans VPN : on ne le lance pas pendant une connexion
                appContext?.let { CoreRuntime.pingAll(it, Store.profiles.value) }
            }
            AppLog.i("Synchronisation : " + lines.size + " config(s)")
            _status.value = SyncStatus(false, System.currentTimeMillis(), "Synchronisé à " + clock() + " · " + lines.size + " config(s)")
            null
        } catch (e: GitHubException) {
            e.message
        } catch (e: IllegalStateException) {
            e.message
        }
        if (problem != null) {
            AppLog.w("Synchronisation : $problem")
            _status.value = SyncStatus(false, _status.value.lastOk, problem)
        }
        return problem
    }

    private fun clock(): String = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date())

    // ----- administration (catégorie admin uniquement) -----

    private fun adminKeys(): Map<String, ByteArray> {
        if (!Account.isAdmin) throw IllegalStateException("Réservé à l'administrateur")
        val keys = Account.keys
        if (!keys.keys.containsAll(Keyring.CATEGORIES)) {
            throw IllegalStateException("Clés admin absentes : initialise le dépôt ou reconnecte-toi en ligne")
        }
        return keys
    }

    fun checkAccess(): String {
        return client(anyToken()).checkAccess()
    }

    /** Crée la branche de données, le compte admin et les trois fichiers de catégories. */
    fun initialize(adminPassword: String): String {
        if (!Account.isAdmin) throw IllegalStateException("Réservé à l'administrateur")
        if (Secrets.builtinCategory("admin", adminPassword) != "admin") {
            throw IllegalArgumentException("Mot de passe admin incorrect")
        }
        val service = writeService()
        val keys = service.initialize(adminPassword)
        val adminLine = service.fetchUsers()?.lines?.firstOrNull { it.login == "admin" }
        val grant = adminLine?.let { Keyring.unwrap(it, adminPassword) }
        if (Account.session.value?.login == "admin") Account.updateKeys(grant?.keys ?: keys, adminLine)
        AppLog.i("Dépôt initialisé")
        return "Dépôt initialisé : compte admin et fichiers de catégories créés"
    }

    fun listUsers(): List<Keyring.UserLine> = readService().fetchUsers()?.lines ?: emptyList()

    fun addUser(login: String, password: String, category: String) {
        writeService().addUser(login, password, category, adminKeys())
        AppLog.i("Utilisateur créé : " + Keyring.normalizeLogin(login) + " ($category)")
    }

    fun removeUser(login: String) {
        writeService().removeUser(login, adminKeys())
        AppLog.i("Utilisateur supprimé : " + Keyring.normalizeLogin(login))
    }

    fun readCategory(category: String): List<String> =
        readService().readCategory(category, adminKeys().getValue(category))

    /** Publie une config (lien dylan://) dans une catégorie. Retourne false si elle y était déjà. */
    fun publishConfig(category: String, line: String): Boolean {
        val added = writeService().addConfig(category, adminKeys().getValue(category), line)
        if (added) AppLog.i("Config publiée dans $category")
        return added
    }

    fun removeConfig(category: String, index: Int) {
        writeService().removeConfig(category, adminKeys().getValue(category), index)
        AppLog.i("Config supprimée de $category")
    }

    // ----- configs ciblées par utilisateur -----

    private fun usersKey(): ByteArray =
        adminKeys()["users"] ?: throw IllegalStateException("Clé des utilisateurs absente : réinitialise le dépôt")

    fun readUserConfigs(login: String): List<String> {
        val key = Keyring.personalKey(usersKey(), login)
        return readService().readUserConfigs(login, key)
    }

    /** Publie une config pour chacun des utilisateurs choisis. Retourne le nombre de comptes qui l'ont reçue. */
    fun publishToUsers(logins: List<String>, line: String): Int {
        if (logins.isEmpty()) throw IllegalArgumentException("Choisis au moins un utilisateur")
        val service = writeService()
        val master = usersKey()
        var count = 0
        for (login in logins) {
            if (service.addUserConfig(login, master, line)) count++
        }
        AppLog.i("Config publiée pour $count utilisateur(s)")
        return count
    }

    fun removeUserConfig(login: String, index: Int) {
        writeService().removeUserConfig(login, usersKey(), index)
        AppLog.i("Config supprimée pour $login")
    }
}
