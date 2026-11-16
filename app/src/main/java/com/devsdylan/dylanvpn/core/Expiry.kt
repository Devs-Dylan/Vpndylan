package com.devsdylan.dylanvpn.core

/** Calcul de la date de fin d'une config à l'import. */
object Expiry {
    /**
     * Date de fin relative (durée à partir de la première activation).
     * [previous] = fin mémorisée si cette config a déjà été importée (0 sinon) : un nouvel import ne prolonge pas.
     */
    fun relativeEnd(config: DylanConfig, now: Long, previous: Long): Long {
        if (config.durationMs <= 0) return 0
        return if (previous > 0) previous else now + config.durationMs
    }

    /** Date de fin effective : la plus proche entre la date fixe et la fin relative (0 = aucune). */
    fun effectiveEnd(config: DylanConfig, relativeEnd: Long): Long {
        val fixed = config.expiresAt
        return when {
            fixed > 0 && relativeEnd > 0 -> minOf(fixed, relativeEnd)
            fixed > 0 -> fixed
            else -> relativeEnd
        }
    }
}
