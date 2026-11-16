package com.devsdylan.dylanvpn.core

import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Résolution du nom du serveur avec mémoire : si le réseau est coupé ou instable au lancement,
 * on utilise la dernière adresse connue au lieu de refuser de se connecter.
 */
object Resolver {
    interface Cache {
        fun get(host: String): String?
        fun put(host: String, ip: String)
    }

    fun systemLookup(host: String): String {
        val all = InetAddress.getAllByName(host)
        val v4 = all.firstOrNull { it is Inet4Address }
        return (v4 ?: all.first()).hostAddress ?: throw UnknownHostException(host)
    }

    /**
     * Retourne l'adresse IP du serveur. [lookup] peut échouer ou être trop lent (> [timeoutMs]) :
     * on retombe alors sur l'adresse mémorisée. Lève UnknownHostException s'il n'y a rien en mémoire.
     */
    fun resolve(
        host: String,
        cache: Cache,
        timeoutMs: Long = 4000,
        lookup: (String) -> String = ::systemLookup
    ): String {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r).also { it.isDaemon = true } }
        try {
            val ip = executor.submit(Callable { lookup(host) }).get(timeoutMs, TimeUnit.MILLISECONDS)
            cache.put(host, ip)
            return ip
        } catch (e: Exception) {
            val remembered = cache.get(host)
            if (remembered != null) return remembered
            throw UnknownHostException(host)
        } finally {
            executor.shutdownNow()
        }
    }
}
