package com.devsdylan.dylanvpn.core

/** Opérations sur le dépôt Git : utilisateurs et fichiers de configs par catégorie. */
class RepoService(private val client: GitHubClient) {

    class UsersState(val lines: List<Keyring.UserLine>, val sha: String?)

    companion object {
        const val USERS = "vpn/users.txt"
        fun categoryPath(category: String) = "vpn/$category.txt"
        fun userPath(login: String) = "vpn/u/" + Keyring.normalizeLogin(login) + ".txt"

        /** Clés remises à un utilisateur : celle de sa catégorie (toutes pour l'admin) et sa clé personnelle. */
        fun grantFor(category: String, keys: Map<String, ByteArray>, login: String): Keyring.Grant {
            val map = LinkedHashMap<String, ByteArray>()
            if (category == "admin") {
                map.putAll(keys)
            } else {
                map[category] = keys.getValue(category)
            }
            keys["users"]?.let { map["personal"] = Keyring.personalKey(it, login) }
            return Keyring.Grant(category, map)
        }

        fun validLogin(login: String): Boolean = Regex("[a-z0-9._-]{3,32}").matches(Keyring.normalizeLogin(login))
    }

    /** Liste des utilisateurs, ou null si le dépôt n'est pas encore initialisé. */
    fun fetchUsers(): UsersState? {
        val file = client.getFile(USERS) ?: return null
        return UsersState(Keyring.parseUsers(file.text), file.sha)
    }

    /**
     * Initialise le dépôt : branche de données, compte admin et fichiers de catégories vides.
     * Retourne les clés (catégories + clé maître « users »).
     */
    fun initialize(adminPassword: String): Map<String, ByteArray> {
        client.ensureBranch("main")
        val keys = LinkedHashMap<String, ByteArray>()
        for (category in Keyring.CATEGORIES) keys[category] = Keyring.newKey()
        keys["users"] = Keyring.newKey()
        val adminLine = Keyring.createUserLine("admin", adminPassword, grantFor("admin", keys, "admin"))
        val users = client.getFile(USERS)
        client.putFile(USERS, Keyring.renderUsers(listOf(adminLine)), "Initialisation des utilisateurs", users?.sha)
        for (category in Keyring.CATEGORIES) {
            val existing = client.getFile(categoryPath(category))
            client.putFile(
                categoryPath(category),
                Keyring.encryptFile(keys.getValue(category), ""),
                "Initialisation $category",
                existing?.sha
            )
        }
        return keys
    }

    private fun readLines(path: String, key: ByteArray, label: String, missingIsEmpty: Boolean): List<String> {
        val file = client.getFile(path)
        if (file == null) {
            // Un fichier de catégorie introuvable n'est PAS « tout a été supprimé » : on refuse de conclure.
            if (missingIsEmpty) return emptyList()
            throw IllegalStateException("Fichier de configs introuvable sur le dépôt (accès refusé ou dépôt non initialisé) : tes configs locales sont conservées")
        }
        val plain = Keyring.decryptFile(key, file.text)
            ?: throw IllegalStateException("Clé incorrecte pour $label")
        return plain.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun writeLines(path: String, key: ByteArray, lines: List<String>, message: String) {
        val file = client.getFile(path)
        client.putFile(path, Keyring.encryptFile(key, lines.joinToString("\n")), message, file?.sha)
    }

    fun readCategory(category: String, key: ByteArray): List<String> =
        readLines(categoryPath(category), key, "la catégorie $category", false)

    private fun writeCategory(category: String, key: ByteArray, lines: List<String>, message: String) =
        writeLines(categoryPath(category), key, lines, message)

    /** Configs personnelles d'un utilisateur, avec sa clé personnelle (ou dérivée de la clé maître par l'admin). */
    fun readUserConfigs(login: String, personalKey: ByteArray): List<String> =
        readLines(userPath(login), personalKey, "les configs de $login", true)

    fun addUserConfig(login: String, master: ByteArray, line: String): Boolean {
        val key = Keyring.personalKey(master, login)
        val lines = readUserConfigs(login, key).toMutableList()
        if (lines.contains(line.trim())) return false
        lines.add(line.trim())
        writeLines(userPath(login), key, lines, "Ajout d'une config (" + Keyring.normalizeLogin(login) + ")")
        return true
    }

    fun removeUserConfig(login: String, master: ByteArray, index: Int) {
        val key = Keyring.personalKey(master, login)
        val lines = readUserConfigs(login, key).toMutableList()
        if (index !in lines.indices) throw IllegalArgumentException("Config introuvable")
        lines.removeAt(index)
        writeLines(userPath(login), key, lines, "Suppression d'une config (" + Keyring.normalizeLogin(login) + ")")
    }

    /** Ajoute une config (ligne dylan://) à une catégorie. Retourne false si elle y était déjà. */
    fun addConfig(category: String, key: ByteArray, line: String): Boolean {
        val lines = readCategory(category, key).toMutableList()
        if (lines.contains(line.trim())) return false
        lines.add(line.trim())
        writeCategory(category, key, lines, "Ajout d'une config ($category)")
        return true
    }

    fun removeConfig(category: String, key: ByteArray, index: Int) {
        val lines = readCategory(category, key).toMutableList()
        if (index !in lines.indices) throw IllegalArgumentException("Config introuvable")
        lines.removeAt(index)
        writeCategory(category, key, lines, "Suppression d'une config ($category)")
    }

    fun addUser(login: String, password: String, category: String, keys: Map<String, ByteArray>) {
        if (!validLogin(login)) throw IllegalArgumentException("Identifiant invalide : 3 à 32 caractères (lettres, chiffres, . _ -)")
        if (password.length < 6) throw IllegalArgumentException("Mot de passe trop court (6 caractères minimum)")
        if (category !in Keyring.CATEGORIES) throw IllegalArgumentException("Catégorie inconnue")
        val state = fetchUsers() ?: throw IllegalStateException("Dépôt non initialisé")
        val name = Keyring.normalizeLogin(login)
        if (state.lines.any { it.login == name }) throw IllegalArgumentException("Cet identifiant existe déjà")
        val line = Keyring.createUserLine(name, password, grantFor(category, keys, name))
        client.putFile(USERS, Keyring.renderUsers(state.lines + line), "Ajout de l'utilisateur $name", state.sha)
    }

    fun removeUser(login: String, keys: Map<String, ByteArray> = emptyMap()) {
        val state = fetchUsers() ?: throw IllegalStateException("Dépôt non initialisé")
        val name = Keyring.normalizeLogin(login)
        // vide le fichier de configs personnelles de l'utilisateur s'il existe
        val master = keys["users"]
        if (master != null && client.getFile(userPath(name)) != null) {
            writeLines(userPath(name), Keyring.personalKey(master, name), emptyList(), "Nettoyage des configs de $name")
        }
        val rest = state.lines.filter { it.login != name }
        if (rest.size == state.lines.size) throw IllegalArgumentException("Utilisateur introuvable")
        client.putFile(USERS, Keyring.renderUsers(rest), "Suppression de l'utilisateur $name", state.sha)
    }
}
