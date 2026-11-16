package com.devsdylan.dylanvpn.core

import org.json.JSONArray
import org.json.JSONObject

/** Construit la configuration JSON de Xray (entrée TUN + sortie « proxy »). */
object ConfigBuilder {
    /** Ports du proxy de partage de connexion (appareils connectés au point d'accès). */
    const val SHARE_SOCKS_PORT = 10808
    const val SHARE_HTTP_PORT = 10809


    /**
     * [resolvedIp] : adresse IP déjà résolue du serveur (si son adresse est un nom de domaine).
     * Elle est transmise à Xray via « dns.hosts » pour qu'il n'ait pas à résoudre le nom lui-même.
     */
    /** Options avancées de la configuration générée. */
    data class Extras(
        val mux: Boolean = false,
        val fragment: Boolean = false,
        val bypassLan: Boolean = false,
        /** Bloque QUIC (UDP 443) : les applications reviennent tout de suite en TCP, bien plus fiable dans un tunnel. */
        val blockQuic: Boolean = false,
        /** Répond aux requêtes DNS dans le tunnel (avec cache, IPv4 seulement) au lieu de les envoyer en UDP au serveur. */
        val localDns: Boolean = false,
        val dns1: String = "1.1.1.1",
        val dns2: String = "8.8.8.8"
    )

    fun build(
        link: ServerLink,
        resolvedIp: String?,
        mtu: Int,
        logLevel: String,
        pinnedCertSha256: String? = null,
        withTun: Boolean = true,
        shaperPort: Int = 0,
        share: Boolean = false,
        extras: Extras = Extras()
    ): String {
        val root = JSONObject()
        root.put("stats", JSONObject())
        root.put("log", JSONObject().put("loglevel", logLevel))
        root.put(
            "policy",
            JSONObject()
                .put(
                    "levels",
                    JSONObject().put(
                        "8",
                        JSONObject()
                            .put("bufferSize", 512)
                            .put("handshake", 8)
                            .put("connIdle", 300)
                            .put("uplinkOnly", 1)
                            .put("downlinkOnly", 1)
                    )
                )
                .put(
                    "system",
                    JSONObject()
                        .put("statsOutboundUplink", true)
                        .put("statsOutboundDownlink", true)
                )
        )
        val inbounds = JSONArray()
        if (withTun) inbounds.put(tunInbound(mtu))
        if (share) {
            inbounds.put(shareInbound("share-socks", "socks", SHARE_SOCKS_PORT, JSONObject().put("udp", false).put("auth", "noauth")))
            inbounds.put(shareInbound("share-http", "http", SHARE_HTTP_PORT, JSONObject()))
        }
        root.put("inbounds", inbounds)
        root.put(
            "outbounds",
            JSONArray()
                .put(proxyOutbound(link, link.host, pinnedCertSha256, shaperPort, extras))
                .put(directOutbound(extras.bypassLan))
                .put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
                .also { arr ->
                    if (withTun && extras.localDns) arr.put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
                    if (shaperPort > 0) arr.put(shaperOutbound(shaperPort))
                    if (useFragment(link, extras, shaperPort)) arr.put(fragmentOutbound())
                }
        )
        val rules = JSONArray()
        if (share) {
            // Le proxy de partage n'accepte que les adresses privées (appareils du point d'accès) ;
            // tout le reste est bloqué. Le trafic du tunnel n'est pas concerné (autre inbound).
            val tags = JSONArray().put("share-socks").put("share-http")
            val privateRanges = JSONArray().put("192.168.0.0/16").put("172.16.0.0/12").put("10.0.0.0/8")
            rules.put(
                JSONObject().put("type", "field").put("inboundTag", tags)
                    .put("source", privateRanges).put("outboundTag", "proxy")
            )
            rules.put(JSONObject().put("type", "field").put("inboundTag", tags).put("outboundTag", "block"))
        }
        if (withTun && extras.localDns) {
            // les requêtes DNS du téléphone sont traitées par le module DNS de Xray (cache, IPv4 seulement)
            rules.put(
                JSONObject().put("type", "field").put("inboundTag", JSONArray().put("tun"))
                    .put("port", "53").put("outboundTag", "dns-out")
            )
        }
        if (withTun && extras.blockQuic) {
            rules.put(
                JSONObject().put("type", "field").put("inboundTag", JSONArray().put("tun"))
                    .put("network", "udp").put("port", "443").put("outboundTag", "block")
            )
        }
        if (extras.bypassLan) {
            val lan = JSONArray().put("10.0.0.0/8").put("172.16.0.0/12").put("192.168.0.0/16").put("127.0.0.0/8")
            rules.put(JSONObject().put("type", "field").put("ip", lan).put("outboundTag", "direct"))
        }
        root.put(
            "routing",
            JSONObject().put("domainStrategy", "AsIs").put("rules", rules)
        )
        val hosts = JSONObject()
        if (!resolvedIp.isNullOrBlank() && resolvedIp != link.host) {
            hosts.put(link.host, resolvedIp)
        }
        val dns = JSONObject().put("hosts", hosts)
        val servers = JSONArray()
        if (extras.localDns) {
            // DNS chiffré (DoH) à travers le tunnel, puis DNS classique en secours
            val list = LinkedHashSet<String>()
            list.add("https://" + extras.dns1 + "/dns-query")
            list.add("https://" + extras.dns2 + "/dns-query")
            list.add(extras.dns1)
            list.add(extras.dns2)
            for (server in list) servers.put(server)
            dns.put("queryStrategy", "UseIPv4")
        }
        dns.put("servers", servers)
        root.put("dns", dns)
        return root.toString()
    }

    private fun directOutbound(allowLan: Boolean): JSONObject {
        val out = JSONObject().put("tag", "direct").put("protocol", "freedom")
        // Xray bloque par défaut les adresses locales : on les autorise pour « contourner le réseau local »
        if (allowLan) {
            out.put("settings", JSONObject().put("finalRules", JSONArray().put(JSONObject().put("action", "allow"))))
        }
        return out
    }

    private fun useFragment(link: ServerLink, extras: Extras, shaperPort: Int): Boolean {
        val security = (link.params["security"] ?: "").lowercase()
        return extras.fragment && shaperPort == 0 && (security == "tls")
    }

    private fun fragmentOutbound(): JSONObject =
        JSONObject()
            .put("tag", "fragment")
            .put("protocol", "freedom")
            .put(
                "settings",
                JSONObject().put(
                    "fragment",
                    JSONObject().put("packets", "tlshello").put("length", "100-200").put("interval", "10-20")
                )
            )

    private fun shareInbound(tag: String, protocol: String, port: Int, settings: JSONObject): JSONObject =
        JSONObject()
            .put("tag", tag)
            .put("listen", "0.0.0.0")
            .put("port", port)
            .put("protocol", protocol)
            .put("settings", settings)

    private fun tunInbound(mtu: Int): JSONObject =
        JSONObject()
            .put("tag", "tun")
            .put("protocol", "tun")
            .put(
                "settings",
                JSONObject().put("name", "xray0").put("MTU", mtu).put("userLevel", 8)
            )
            .put(
                "sniffing",
                JSONObject()
                    .put("enabled", true)
                    .put("destOverride", JSONArray().put("http").put("tls"))
            )

    private fun shaperOutbound(port: Int): JSONObject =
        JSONObject()
            .put("tag", "shaper")
            .put("protocol", "socks")
            .put(
                "settings",
                JSONObject().put(
                    "servers",
                    JSONArray().put(JSONObject().put("address", "127.0.0.1").put("port", port))
                )
            )

    private fun proxyOutbound(link: ServerLink, address: String, pin: String?, shaperPort: Int, extras: Extras): JSONObject {
        val out = JSONObject().put("tag", "proxy").put("protocol", protocolName(link))
        out.put("settings", outboundSettings(link, address))
        val stream = streamSettings(link, pin)
        // Keep-alive TCP : détecte plus vite une liaison morte et garde les connexions ouvertes sur réseau mobile instable.
        val sockopt = JSONObject().put("tcpKeepAliveIdle", 30).put("tcpKeepAliveInterval", 15)
        if (shaperPort > 0) {
            // le trafic vers le serveur passe par le relais local qui limite le débit
            sockopt.put("dialerProxy", "shaper")
        } else if (useFragment(link, extras, shaperPort)) {
            // fragmentation du « TLS hello » (contournement de certains filtrages)
            sockopt.put("dialerProxy", "fragment")
        }
        stream.put("sockopt", sockopt)
        out.put("streamSettings", stream)
        // le multiplexage est incompatible avec le flow XTLS (Vision)
        val muxOn = extras.mux && (link.params["flow"] ?: "").isBlank()
        val mux = JSONObject().put("enabled", muxOn)
        if (muxOn) mux.put("concurrency", 8)
        out.put("mux", mux)
        return out
    }

    private fun protocolName(link: ServerLink): String =
        when (link.protocol) {
            "ss" -> "shadowsocks"
            else -> link.protocol
        }

    private fun outboundSettings(link: ServerLink, address: String): JSONObject {
        val p = link.params
        return when (link.protocol) {
            "vless" -> {
                val user = JSONObject()
                    .put("id", link.secret)
                    .put("encryption", p["encryption"]?.ifBlank { "none" } ?: "none")
                    .put("level", 8)
                val flow = p["flow"]
                if (!flow.isNullOrBlank()) user.put("flow", flow)
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", address)
                            .put("port", link.port)
                            .put("users", JSONArray().put(user))
                    )
                )
            }
            "vmess" -> {
                val user = JSONObject()
                    .put("id", link.secret)
                    .put("alterId", p["aid"]?.toIntOrNull() ?: 0)
                    .put("security", link.method.ifBlank { "auto" })
                    .put("level", 8)
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", address)
                            .put("port", link.port)
                            .put("users", JSONArray().put(user))
                    )
                )
            }
            "trojan" -> JSONObject().put(
                "servers",
                JSONArray().put(
                    JSONObject()
                        .put("address", address)
                        .put("port", link.port)
                        .put("password", link.secret)
                        .put("level", 8)
                )
            )
            "ss" -> JSONObject().put(
                "servers",
                JSONArray().put(
                    JSONObject()
                        .put("address", address)
                        .put("port", link.port)
                        .put("method", link.method)
                        .put("password", link.secret)
                        .put("level", 8)
                )
            )
            else -> throw IllegalArgumentException("Protocole non pris en charge : ${link.protocol}")
        }
    }

    private fun streamSettings(link: ServerLink, pin: String?): JSONObject {
        val p = link.params
        val network = when (val n = (p["type"] ?: "tcp").lowercase().ifBlank { "tcp" }) {
            "splithttp" -> "xhttp"
            "raw" -> "tcp"
            else -> n
        }
        val security = (p["security"] ?: "none").lowercase().ifBlank { "none" }
        val sni = (p["sni"] ?: p["peer"] ?: "").trim()
        val hostParam = (p["host"] ?: "").trim()
        val path = (p["path"] ?: "").ifBlank { "/" }
        val stream = JSONObject().put("network", network)

        when (network) {
            "tcp" -> {
                if ((p["headertype"] ?: "") == "http") {
                    val request = JSONObject()
                        .put("path", JSONArray().put(path))
                        .put(
                            "headers",
                            JSONObject().put(
                                "Host",
                                JSONArray().put(hostParam.ifBlank { link.host })
                            )
                        )
                    stream.put(
                        "tcpSettings",
                        JSONObject().put(
                            "header",
                            JSONObject().put("type", "http").put("request", request)
                        )
                    )
                }
            }
            "ws" -> stream.put(
                "wsSettings",
                JSONObject()
                    .put("path", path)
                    .put(
                        "headers",
                        JSONObject().put(
                            "Host",
                            hostParam.ifBlank { sni.ifBlank { link.host } }
                        )
                    )
            )
            "grpc" -> stream.put(
                "grpcSettings",
                JSONObject()
                    .put("serviceName", p["servicename"] ?: "")
                    .put("multiMode", (p["mode"] ?: "") == "multi")
            )
            "httpupgrade" -> stream.put(
                "httpupgradeSettings",
                JSONObject()
                    .put("path", path)
                    .put("host", hostParam.ifBlank { sni.ifBlank { link.host } })
            )
            "xhttp" -> stream.put(
                "xhttpSettings",
                JSONObject()
                    .put("path", path)
                    .put("host", hostParam.ifBlank { sni.ifBlank { link.host } })
                    .put("mode", (p["mode"] ?: "").ifBlank { "auto" })
            )
            "kcp" -> {
                val kcp = JSONObject()
                    .put("header", JSONObject().put("type", (p["headertype"] ?: "").ifBlank { "none" }))
                val seed = p["seed"]
                if (!seed.isNullOrBlank()) kcp.put("seed", seed)
                stream.put("kcpSettings", kcp)
            }
            "h2", "http", "quic" -> throw IllegalArgumentException(
                "Transport « $network » supprimé de Xray : le serveur doit utiliser xhttp, ws ou grpc"
            )
            else -> throw IllegalArgumentException("Transport non pris en charge : $network")
        }

        when (security) {
            "tls" -> {
                stream.put("security", "tls")
                val tls = JSONObject()
                    .put("serverName", sni.ifBlank { hostParam.ifBlank { link.host } })
                val alpn = (p["alpn"] ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (alpn.isNotEmpty()) {
                    val arr = JSONArray()
                    for (a in alpn) arr.put(a)
                    tls.put("alpn", arr)
                }
                val fp = p["fp"]
                if (!fp.isNullOrBlank()) tls.put("fingerprint", fp)
                if (!pin.isNullOrBlank()) tls.put("pinnedPeerCertSha256", pin)
                stream.put("tlsSettings", tls)
            }
            "reality" -> {
                val pbk = p["pbk"] ?: ""
                if (pbk.isBlank()) {
                    throw IllegalArgumentException("Lien Reality incomplet : clé publique (pbk) manquante")
                }
                stream.put("security", "reality")
                val reality = JSONObject()
                    .put("serverName", sni.ifBlank { link.host })
                    .put("fingerprint", (p["fp"] ?: "").ifBlank { "chrome" })
                    .put("publicKey", pbk)
                    .put("shortId", p["sid"] ?: "")
                    .put("spiderX", p["spx"] ?: "")
                val pqv = p["pqv"]
                if (!pqv.isNullOrBlank()) reality.put("mldsa65Verify", pqv)
                stream.put("realitySettings", reality)
            }
            "none" -> {}
            else -> throw IllegalArgumentException("Sécurité non prise en charge : $security")
        }
        return stream
    }

    /**
     * Xray n'accepte plus « allowInsecure » : pour un lien qui l'utilise, on récupère l'empreinte SHA-256
     * du certificat du serveur et on l'épingle (pinnedPeerCertSha256).
     */
    fun needsCertPin(link: ServerLink): Boolean {
        val p = link.params
        if ((p["security"] ?: "").lowercase() != "tls") return false
        val flag = (p["allowinsecure"] ?: p["insecure"] ?: "").lowercase()
        return flag == "1" || flag == "true"
    }

    /** Nom de serveur (SNI) à utiliser pour récupérer le certificat, ou null si c'est une adresse IP. */
    fun certServerName(link: ServerLink): String? {
        val p = link.params
        val name = (p["sni"] ?: p["peer"] ?: p["host"] ?: "").trim()
        if (name.isEmpty()) return null
        val isIp = name.contains(':') || name.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))
        return if (isIp) null else name
    }
}
