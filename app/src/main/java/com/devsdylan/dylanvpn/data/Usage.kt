package com.devsdylan.dylanvpn.data

import android.content.Context
import android.content.SharedPreferences
import com.devsdylan.dylanvpn.core.Quota
import com.devsdylan.dylanvpn.core.SyncLimit
import com.devsdylan.dylanvpn.util.Format

/** Compteurs locaux : données consommées (par fichier ou par utilisateur) et synchronisations par fichier. */
object Usage {
    private var prefs: SharedPreferences? = null

    class Line(val text: String, val level: Int)

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("dylanvpn_usage", Context.MODE_PRIVATE)
    }

    fun used(key: String): Long = prefs?.getLong("u_$key", 0L) ?: 0L

    fun add(key: String, bytes: Long) {
        if (bytes <= 0) return
        prefs?.edit()?.putLong("u_$key", used(key) + bytes)?.apply()
    }

    fun syncCount(cid: String): Int = prefs?.getInt("s_$cid", 0) ?: 0

    fun setSyncCount(cid: String, count: Int) {
        prefs?.edit()?.putInt("s_$cid", count)?.apply()
    }

    fun keyFor(p: Profile, login: String): String = SyncLimit.usageKey(p.quotaScope, p.cid, p.id, login)

    fun usedFor(p: Profile): Long = used(keyFor(p, Account.session.value?.login ?: ""))

    /** Lignes à afficher sous une config : quota de données et synchronisations. */
    fun lines(p: Profile): List<Line> {
        val out = ArrayList<Line>()
        if (p.quotaBytes > 0) {
            val used = usedFor(p)
            val level = when {
                Quota.exhausted(used, p.quotaBytes) -> 2
                used * 100 >= p.quotaBytes * 80 -> 1
                else -> 0
            }
            val scope = if (p.quotaScope == "user") "par utilisateur" else "pour ce fichier"
            out.add(Line("Données : " + Format.bytes(used) + " / " + Format.bytes(p.quotaBytes) + " (" + scope + ")", level))
        }
        if (p.maxSyncs > 0 && p.cid.isNotBlank()) {
            val n = syncCount(p.cid)
            out.add(Line("Synchronisations : $n / " + p.maxSyncs, if (n >= p.maxSyncs) 1 else 0))
        }
        return out
    }
}
