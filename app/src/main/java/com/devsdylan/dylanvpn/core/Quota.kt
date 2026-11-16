package com.devsdylan.dylanvpn.core

/** Quota de données d'une config. */
object Quota {
    fun exhausted(used: Long, quota: Long): Boolean = quota > 0 && used >= quota

    fun remaining(used: Long, quota: Long): Long = if (quota <= 0) Long.MAX_VALUE else (quota - used).coerceAtLeast(0)

    /** Convertit une valeur saisie (« 5 », « 1.5 ») et une unité (« Mo », « Go ») en octets. */
    fun parse(value: String, unit: String): Long? {
        val v = value.trim().replace(',', '.').ifEmpty { "0" }.toDoubleOrNull() ?: return null
        if (v < 0) return null
        val factor = if (unit == "Go") 1024.0 * 1024 * 1024 else 1024.0 * 1024
        return (v * factor).toLong()
    }
}
