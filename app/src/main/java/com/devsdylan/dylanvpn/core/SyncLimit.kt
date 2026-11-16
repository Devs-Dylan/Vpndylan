package com.devsdylan.dylanvpn.core

/** Limite du nombre de synchronisations d'un fichier de config sur un appareil. */
object SyncLimit {
    class Decision(val apply: Boolean, val count: Int)

    /**
     * [count] : synchronisations déjà effectuées pour ce fichier ; [max] : maximum autorisé (0 = illimité).
     * Quand le maximum est atteint, la config n'est plus mise à jour (elle reste utilisable).
     */
    fun decide(count: Int, max: Int): Decision {
        if (max <= 0) return Decision(true, count + 1)
        if (count >= max) return Decision(false, count)
        return Decision(true, count + 1)
    }

    /** Clé du compteur de données : par fichier (cid ou identifiant du profil) ou par utilisateur. */
    fun usageKey(scope: String, cid: String, profileId: String, login: String): String =
        if (scope == "user") "user:" + login.lowercase() else "file:" + cid.ifBlank { profileId }
}
