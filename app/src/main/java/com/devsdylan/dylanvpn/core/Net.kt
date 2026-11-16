package com.devsdylan.dylanvpn.core

import java.net.Inet4Address
import java.net.NetworkInterface

object Net {
    /** (interface, adresse IPv4 privée) des interfaces actives hors boucle locale. */
    fun privateAddresses(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return out
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    val ip = addr.hostAddress ?: continue
                    if (addr is Inet4Address && addr.isSiteLocalAddress) out.add(Pair(iface.name, ip))
                }
            }
        } catch (e: Exception) {
            // liste indisponible
        }
        return out
    }
}
