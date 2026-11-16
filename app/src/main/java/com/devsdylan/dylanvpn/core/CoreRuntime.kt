package com.devsdylan.dylanvpn.core

import android.content.Context
import com.devsdylan.dylanvpn.data.AppLog
import com.devsdylan.dylanvpn.data.Profile
import com.devsdylan.dylanvpn.data.Store
import go.Seq
import java.net.UnknownHostException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import libv2ray.Libv2ray
import org.json.JSONObject

/** Initialisation du core Xray, préparation de la config et test des profils. */
object CoreRuntime {
    private const val TEST_URL = "https://www.gstatic.com/generate_204"

    @Volatile
    private var ready = false

    class PingResult(val ms: Long, val at: Long, val error: String = "")

    private val _pings = MutableStateFlow<Map<String, PingResult>>(emptyMap())
    val pings: StateFlow<Map<String, PingResult>> = _pings

    private val _pinging = MutableStateFlow<Set<String>>(emptySet())
    val pinging: StateFlow<Set<String>> = _pinging

    @Synchronized
    fun init(context: Context) {
        if (ready) return
        val app = context.applicationContext
        Seq.setContext(app)
        Libv2ray.initCoreEnv(app.getDir("assets", Context.MODE_PRIVATE).absolutePath, Store.deviceKey())
        ready = true
    }

    private val ipCache = object : Resolver.Cache {
        override fun get(host: String): String? = Store.cacheGet("ip:$host")
        override fun put(host: String, ip: String) = Store.cachePut("ip:$host", ip)
    }

    /** Adresse IP du serveur si son adresse est un nom de domaine, sinon null (dernière adresse connue en secours). */
    private fun resolve(host: String): String? {
        val isIp = host.contains(':') || host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))
        if (isIp) return null
        val ip = Resolver.resolve(host, ipCache)
        if (Store.cacheGet("ip:$host") == ip) AppLog.i("Adresse du serveur : mémoire de l'appareil ou résolution réussie")
        return ip
    }

    /** Construit la configuration Xray complète d'un profil (le core doit être initialisé). */
    fun buildConfig(profile: Profile, withTun: Boolean, shaperPort: Int = 0, share: Boolean = false): String {
        val link = LinkParser.parse(profile.link)
        val settings = Store.settings.value
        val ip = resolve(link.host)

        var pin: String? = null
        if (ConfigBuilder.needsCertPin(link)) {
            val request = JSONObject()
                .put("address", ip ?: link.host)
                .put("port", link.port)
                .put("timeoutMs", 6000)
            val serverName = ConfigBuilder.certServerName(link)
            if (serverName != null) request.put("serverName", serverName)
            val pinKey = "pin:" + link.host + ":" + link.port + ":" + (serverName ?: "")
            var sha = ""
            var reason = ""
            try {
                val answer = JSONObject(Libv2ray.fetchTlsCertSha256(request.toString()))
                sha = answer.optString("sha256", "").replace(":", "").replace(" ", "").lowercase()
                reason = answer.optString("error", "")
            } catch (e: Exception) {
                reason = e.message ?: ""
            }
            if (sha.length == 64) {
                Store.cachePut(pinKey, sha)
            } else {
                // réseau instable : on réutilise l'empreinte mémorisée lors d'une connexion précédente
                val remembered = Store.cacheGet(pinKey)
                if (remembered == null || remembered.length != 64) {
                    throw IllegalStateException(
                        "Impossible de lire le certificat du serveur" + if (reason.isNotBlank()) " ($reason)" else ""
                    )
                }
                sha = remembered
                AppLog.w("Certificat : empreinte mémorisée utilisée (le serveur n'a pas répondu)")
            }
            pin = sha
            AppLog.i("Certificat du serveur épinglé (lien en allowInsecure)")
        }
        return ConfigBuilder.build(link, ip, settings.mtu, settings.coreLogLevel, pin, withTun, shaperPort, share,
            ConfigBuilder.Extras(
                settings.mux, settings.fragment, settings.bypassLan,
                settings.blockQuic, settings.localDns, settings.dns, settings.dns2
            ))
    }

    /** Mesure la latence d'un profil (sans VPN) en ms ; lève une exception si le serveur ne répond pas. */
    fun measure(context: Context, profile: Profile): Long {
        init(context)
        val ms = Libv2ray.measureOutboundDelay(buildConfig(profile, false), TEST_URL)
        if (ms < 0) throw IllegalStateException("aucune réponse du serveur")
        return ms
    }

    /** Teste un profil (sans VPN) ; le résultat est publié dans [pings]. Bloquant : à appeler hors du thread principal. */
    fun pingNow(context: Context, profile: Profile) {
        _pinging.update { it + profile.id }
        val result = try {
            PingResult(measure(context, profile), System.currentTimeMillis())
        } catch (t: Throwable) {
            val message = scrub(profile, friendly(t))
            AppLog.e("Ping « ${profile.name} » : $message")
            PingResult(-1, System.currentTimeMillis(), message)
        }
        if (result.ms >= 0) AppLog.i("Ping « ${profile.name} » : ${result.ms} ms")
        _pings.update { it + (profile.id to result) }
        _pinging.update { it - profile.id }
    }

    fun ping(context: Context, profile: Profile) {
        Thread { pingNow(context, profile) }.start()
    }

    fun pingAll(context: Context, profiles: List<Profile>) {
        Thread {
            for (p in profiles) {
                if (!Store.isExpired(p)) pingNow(context, p)
            }
        }.start()
    }

    /** Masque l'adresse du serveur dans un message si le profil est verrouillé. */
    fun scrub(profile: Profile?, text: String): String {
        if (profile == null || !profile.locked) return text
        var out = text
        if (profile.host.isNotEmpty()) out = out.replace(profile.host, "***")
        return out
    }

    fun friendly(t: Throwable): String {
        val raw = t.message ?: t.javaClass.simpleName
        return when {
            t is UnknownHostException -> "adresse du serveur introuvable (vérifie ta connexion Internet)"
            t is UnsatisfiedLinkError -> "core Xray incompatible avec ce processeur ($raw)"
            raw.contains("without TLS", ignoreCase = true) ->
                "Xray refuse ce lien VLESS : il n'a ni TLS ni Reality"
            else -> raw
        }
    }
}
