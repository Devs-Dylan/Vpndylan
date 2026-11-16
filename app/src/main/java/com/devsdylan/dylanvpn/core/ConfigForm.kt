package com.devsdylan.dylanvpn.core

/** Champs du formulaire de config (éditeur façon NPV Tunnel). */
data class FormFields(
    val protocol: String = "vless",
    val name: String = "",
    val host: String = "",
    val port: String = "443",
    val secret: String = "",
    val encryption: String = "none",
    val flow: String = "",
    val method: String = "auto",
    val aid: String = "0",
    val transport: String = "tcp",
    val headerType: String = "none",
    val hostHeader: String = "",
    val path: String = "",
    val serviceName: String = "",
    val security: String = "none",
    val sni: String = "",
    val fp: String = "",
    val alpn: String = "",
    val allowInsecure: Boolean = false,
    val pbk: String = "",
    val sid: String = "",
    val spx: String = ""
)

object ConfigForm {
    val PROTOCOLS = listOf("vless", "vmess", "trojan", "ss")
    val TRANSPORTS = listOf("tcp", "ws", "grpc", "httpupgrade", "xhttp", "kcp")
    val SECURITIES = listOf("none", "tls", "reality")
    val FINGERPRINTS = listOf("", "chrome", "firefox", "safari", "ios", "android", "edge", "random", "randomized")
    val FLOWS = listOf("", "xtls-rprx-vision")
    val VMESS_METHODS = listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none")
    val SS_METHODS = listOf("chacha20-ietf-poly1305", "aes-128-gcm", "aes-256-gcm", "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm")

    fun fromLink(raw: String): FormFields {
        val l = LinkParser.parse(raw)
        val p = l.params
        return FormFields(
            protocol = l.protocol,
            name = l.name,
            host = l.host,
            port = l.port.toString(),
            secret = l.secret,
            encryption = (p["encryption"] ?: "none").ifBlank { "none" },
            flow = p["flow"] ?: "",
            method = if (l.protocol == "ss") l.method else l.method.ifBlank { "auto" },
            aid = p["aid"] ?: "0",
            transport = (p["type"] ?: "tcp").ifBlank { "tcp" }.let { if (it == "raw") "tcp" else it },
            headerType = (p["headertype"] ?: "none").ifBlank { "none" },
            hostHeader = p["host"] ?: "",
            path = p["path"] ?: "",
            serviceName = p["servicename"] ?: "",
            security = (p["security"] ?: "none").ifBlank { "none" },
            sni = p["sni"] ?: p["peer"] ?: "",
            fp = p["fp"] ?: "",
            alpn = p["alpn"] ?: "",
            allowInsecure = (p["allowinsecure"] ?: p["insecure"] ?: "") in listOf("1", "true"),
            pbk = p["pbk"] ?: "",
            sid = p["sid"] ?: "",
            spx = p["spx"] ?: ""
        )
    }

    /** Construit le lien. Lève IllegalArgumentException (message en français) si le formulaire est incomplet. */
    fun toLink(f: FormFields): String {
        val host = f.host.trim()
        if (host.isEmpty()) throw IllegalArgumentException("Adresse manquante")
        val port = f.port.trim().toIntOrNull()
        if (port == null || port !in 1..65535) throw IllegalArgumentException("Port invalide (1 à 65535)")
        val secret = f.secret.trim()
        if (secret.isEmpty()) {
            throw IllegalArgumentException(if (f.protocol == "vless" || f.protocol == "vmess") "UUID manquant" else "Mot de passe manquant")
        }
        if (f.protocol == "ss" && f.method.isBlank()) throw IllegalArgumentException("Méthode de chiffrement manquante")

        val params = LinkedHashMap<String, String>()
        if (f.protocol == "vless") {
            params["encryption"] = f.encryption.ifBlank { "none" }
            if (f.flow.isNotBlank()) params["flow"] = f.flow
        }
        if (f.protocol == "vmess") params["aid"] = f.aid.ifBlank { "0" }
        if (f.protocol != "ss") {
            params["type"] = f.transport
            if (f.transport == "tcp" || f.transport == "kcp") {
                if (f.headerType != "none") params["headertype"] = f.headerType
            }
            if (f.hostHeader.isNotBlank()) params["host"] = f.hostHeader.trim()
            if (f.transport in listOf("ws", "httpupgrade", "xhttp") || f.path.isNotBlank()) {
                if (f.path.isNotBlank()) params["path"] = f.path.trim()
            }
            if (f.transport == "grpc" && f.serviceName.isNotBlank()) params["servicename"] = f.serviceName.trim()
            params["security"] = f.security
            if (f.security != "none") {
                if (f.sni.isNotBlank()) params["sni"] = f.sni.trim()
                if (f.fp.isNotBlank()) params["fp"] = f.fp
                if (f.alpn.isNotBlank()) params["alpn"] = f.alpn.trim()
            }
            if (f.security == "tls" && f.allowInsecure) params["allowinsecure"] = "1"
            if (f.security == "reality") {
                params["pbk"] = f.pbk.trim()
                if (f.sid.isNotBlank()) params["sid"] = f.sid.trim()
                if (f.spx.isNotBlank()) params["spx"] = f.spx.trim()
            }
        }
        val method = when (f.protocol) {
            "ss" -> f.method.trim()
            "vmess" -> f.method.ifBlank { "auto" }
            else -> ""
        }
        val name = f.name.trim().ifBlank { "$host:$port" }
        val link = ServerLink(f.protocol, name, host, port, secret, method, params)
        val text = LinkBuilder.build(link)
        // vérifie que la config est utilisable (transport et sécurité pris en charge, Reality complet...)
        ConfigBuilder.build(LinkParser.parse(text), null, 1500, "warning")
        return text
    }
}
