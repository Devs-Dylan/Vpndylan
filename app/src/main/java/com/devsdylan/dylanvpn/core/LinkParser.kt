package com.devsdylan.dylanvpn.core

import java.net.URLDecoder
import java.util.Base64
import org.json.JSONObject

/** Serveur décodé à partir d'un lien de partage (vless://, vmess://, trojan://, ss://). */
data class ServerLink(
    val protocol: String,
    val name: String,
    val host: String,
    val port: Int,
    val secret: String,
    val method: String = "",
    val params: Map<String, String> = emptyMap()
)

object LinkParser {

    fun parse(raw: String): ServerLink {
        val link = raw.trim()
        val scheme = link.substringBefore("://", "").lowercase()
        return when (scheme) {
            "vless", "trojan" -> parseUrlStyle(scheme, link)
            "vmess" -> parseVmess(link)
            "ss" -> parseShadowsocks(link)
            "" -> throw IllegalArgumentException("Lien invalide : il doit commencer par vless://, vmess://, trojan:// ou ss://")
            else -> throw IllegalArgumentException("Protocole non pris en charge : $scheme")
        }
    }

    private fun parseUrlStyle(scheme: String, link: String): ServerLink {
        val rest = link.substringAfter("://")
        val name = dec(rest.substringAfter('#', ""))
        val noFragment = rest.substringBefore('#')
        val query = noFragment.substringAfter('?', "")
        val main = noFragment.substringBefore('?')
        if (!main.contains('@')) {
            throw IllegalArgumentException("Lien incomplet : il manque identifiant@serveur:port")
        }
        val secret = dec(main.substringBeforeLast('@'))
        val (host, port) = splitHostPort(main.substringAfterLast('@'))
        if (secret.isBlank()) throw IllegalArgumentException("Lien incomplet : identifiant ou mot de passe vide")
        return ServerLink(
            protocol = scheme,
            name = name.ifBlank { "$host:$port" },
            host = host,
            port = port,
            secret = secret,
            params = parseQuery(query)
        )
    }

    private fun parseVmess(link: String): ServerLink {
        val payload = link.substringAfter("://").substringBefore('#').trim()
        val json = try {
            JSONObject(String(b64(payload), Charsets.UTF_8))
        } catch (e: Exception) {
            throw IllegalArgumentException("Lien vmess illisible (base64 ou JSON invalide)")
        }
        val host = json.optString("add", "")
        val port = json.opt("port")?.toString()?.trim()?.toIntOrNull() ?: 0
        checkHostPort(host, port)
        val id = json.optString("id", "")
        if (id.isBlank()) throw IllegalArgumentException("Lien vmess incomplet : id manquant")

        val params = HashMap<String, String>()
        params["type"] = json.optString("net", "tcp").ifBlank { "tcp" }
        params["headertype"] = json.optString("type", "")
        params["host"] = json.optString("host", "")
        params["path"] = json.optString("path", "")
        params["aid"] = json.opt("aid")?.toString() ?: "0"
        val tls = json.optString("tls", "")
        params["security"] = if (tls == "tls") "tls" else "none"
        params["sni"] = json.optString("sni", "")
        params["alpn"] = json.optString("alpn", "")
        params["fp"] = json.optString("fp", "")
        val name = json.optString("ps", "").ifBlank { "$host:$port" }
        return ServerLink("vmess", name, host, port, id, json.optString("scy", "auto"), params)
    }

    private fun parseShadowsocks(link: String): ServerLink {
        val rest = link.substringAfter("://")
        val name = dec(rest.substringAfter('#', ""))
        val noFragment = rest.substringBefore('#')
        val query = noFragment.substringAfter('?', "")
        val main = noFragment.substringBefore('?').trimEnd('/')
        val params = parseQuery(query)
        if (params.containsKey("plugin")) {
            throw IllegalArgumentException("Les plugins Shadowsocks ne sont pas pris en charge")
        }

        val userInfo: String
        val hostPort: String
        try {
            if (main.contains('@')) {
                val raw = main.substringBeforeLast('@')
                userInfo = if (raw.contains(':')) dec(raw) else String(b64(raw), Charsets.UTF_8)
                hostPort = main.substringAfterLast('@')
            } else {
                val decoded = String(b64(main), Charsets.UTF_8)
                userInfo = decoded.substringBeforeLast('@')
                hostPort = decoded.substringAfterLast('@')
            }
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Lien ss illisible (base64 invalide)")
        }
        val method = userInfo.substringBefore(':')
        val password = userInfo.substringAfter(':', "")
        if (method.isBlank() || password.isBlank()) {
            throw IllegalArgumentException("Lien ss incomplet : méthode ou mot de passe manquant")
        }
        val (host, port) = splitHostPort(hostPort)
        return ServerLink("ss", name.ifBlank { "$host:$port" }, host, port, password, method, params)
    }

    private fun splitHostPort(hostPort: String): Pair<String, Int> {
        val hp = hostPort.trim().trimEnd('/')
        val host: String
        val portText: String
        if (hp.startsWith("[")) {
            host = hp.substringAfter('[').substringBefore(']')
            portText = hp.substringAfter("]:", "")
        } else {
            host = hp.substringBeforeLast(':', "")
            portText = hp.substringAfterLast(':', "")
        }
        val port = portText.toIntOrNull() ?: 0
        checkHostPort(host, port)
        return Pair(host, port)
    }

    private fun checkHostPort(host: String, port: Int) {
        if (host.isBlank()) throw IllegalArgumentException("Lien incomplet : adresse du serveur manquante")
        if (port !in 1..65535) throw IllegalArgumentException("Lien incomplet : port invalide")
    }

    private fun parseQuery(query: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isBlank()) continue
            val key = dec(pair.substringBefore('=')).lowercase()
            val value = dec(pair.substringAfter('=', ""))
            map[key] = value
        }
        return map
    }

    private fun dec(s: String): String =
        try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: Exception) {
            s
        }

    private fun b64(s: String): ByteArray =
        Base64.getMimeDecoder().decode(s.trim().replace('-', '+').replace('_', '/'))
}
