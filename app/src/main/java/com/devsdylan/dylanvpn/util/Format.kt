package com.devsdylan.dylanvpn.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {

    /** [unit] = "bps" (bits par seconde) ou "Bps" (octets par seconde). */
    fun speed(bytesPerSecond: Long, unit: String): String {
        return if (unit == "Bps") {
            scale(bytesPerSecond.toDouble(), 1024.0, listOf("B/s", "Ko/s", "Mo/s", "Go/s"))
        } else {
            scale(bytesPerSecond * 8.0, 1000.0, listOf("bps", "Kbps", "Mbps", "Gbps"))
        }
    }

    fun bytes(count: Long): String =
        scale(count.toDouble(), 1024.0, listOf("o", "Ko", "Mo", "Go", "To"))

    /** Temps restant lisible : « 2 j 3 h », « 45 min », « Expirée ». */
    fun remaining(ms: Long): String {
        if (ms <= 0) return "Expirée"
        val minutes = ms / 60_000
        val days = minutes / 1440
        val hours = (minutes / 60) % 24
        val mins = minutes % 60
        return when {
            days > 0 -> "$days j $hours h"
            hours > 0 -> "$hours h $mins min"
            else -> maxOf(mins, 1).toString() + " min"
        }
    }

    fun dateTime(ms: Long): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(ms))

    /** Limite lisible (octets/s) : « 300 Ko/s », « illimité ». */
    fun limit(bytesPerSecond: Long): String =
        if (bytesPerSecond <= 0) "illimité" else scale(bytesPerSecond.toDouble(), 1024.0, listOf("o/s", "Ko/s", "Mo/s", "Go/s"))

    private fun scale(value: Double, base: Double, units: List<String>): String {
        var v = value
        var i = 0
        while (v >= base && i < units.size - 1) {
            v /= base
            i++
        }
        return if (i == 0) {
            String.format(Locale.US, "%.0f %s", v, units[i])
        } else {
            String.format(Locale.US, "%.1f %s", v, units[i])
        }
    }
}
