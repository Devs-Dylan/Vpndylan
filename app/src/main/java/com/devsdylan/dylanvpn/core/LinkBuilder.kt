package com.devsdylan.dylanvpn.core

import java.net.URLEncoder
import java.util.Base64
import org.json.JSONObject

/** Reconstruit un lien de partage (vless://, vmess://, trojan://, ss://) à partir d'un [ServerLink]. */
object LinkBuilder {

    private val CANONICAL = mapOf(
        "servicename" to "serviceName",
        "allowinsecure" to "allowInsecure",
        "headertype" to "headerType"
    )

    fun build(link: ServerLink): String =
        when (link.protocol) {
            "vless", "trojan" -> buildUrlStyle(link)
            "vmess" -> buildVmess(link)
            "ss" -> buildShadowsocks(link)
            else -> throw IllegalArgumentException("Protocole non pris en charge : " + link.protocol)
        }

    private fun buildUrlStyle(link: ServerLink): String {
        val sb = StringBuilder(link.protocol).append("://")
        sb.append(enc(link.secret)).append('@').append(hostPort(link))
        val query = query(link.params)
        if (query.isNotEmpty()) sb.append('?').append(query)
        sb.append('#').append(enc(link.name))
        return sb.toString()
    }

    private fun buildVmess(link: ServerLink): String {
        val p = link.params
        val json = JSONObject()
            .put("v", "2")
            .put("ps", link.name)
            .put("add", link.host)
            .put("port", link.port.toString())
            .put("id", link.secret)
            .put("aid", p["aid"] ?: "0")
            .put("scy", link.method.ifBlank { "auto" })
            .put("net", p["type"] ?: "tcp")
            .put("type", p["headertype"] ?: "none")
            .put("host", p["host"] ?: "")
            .put("path", p["path"] ?: "")
            .put("tls", if ((p["security"] ?: "") == "tls") "tls" else "")
            .put("sni", p["sni"] ?: "")
            .put("alpn", p["alpn"] ?: "")
            .put("fp", p["fp"] ?: "")
        return "vmess://" + Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun buildShadowsocks(link: ServerLink): String {
        val userInfo = Base64.getUrlEncoder().withoutPadding()
            .encodeToString((link.method + ":" + link.secret).toByteArray(Charsets.UTF_8))
        val sb = StringBuilder("ss://").append(userInfo).append('@').append(hostPort(link))
        val query = query(link.params)
        if (query.isNotEmpty()) sb.append('?').append(query)
        sb.append('#').append(enc(link.name))
        return sb.toString()
    }

    private fun hostPort(link: ServerLink): String =
        if (link.host.contains(':')) "[" + link.host + "]:" + link.port else link.host + ":" + link.port

    private fun query(params: Map<String, String>): String =
        params.entries
            .filter { it.value.isNotBlank() }
            .joinToString("&") { (CANONICAL[it.key] ?: it.key) + "=" + enc(it.value) }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
