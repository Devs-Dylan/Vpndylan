package com.devsdylan.dylanvpn.core

/** Décide si une mise à jour de l'application est disponible. */
object Updates {
    /** Un build est publié quelques minutes après sa compilation : en dessous de cet écart, c'est le même build. */
    const val WINDOW_MS = 20 * 60_000L

    class Decision(val available: Boolean, val baseline: String)

    /**
     * [buildTime] : date de compilation de l'application installée.
     * [baseline] : identifiant du fichier publié qui correspond à l'application installée (vide si inconnu).
     * [assetId] / [createdAt] : fichier actuellement publié.
     */
    fun decide(buildTime: Long, baseline: String, assetId: String, createdAt: Long): Decision {
        if (baseline.isNotEmpty()) return Decision(baseline != assetId, baseline)
        if (kotlin.math.abs(createdAt - buildTime) <= WINDOW_MS) return Decision(false, assetId)
        return Decision(createdAt > buildTime, "")
    }
}
