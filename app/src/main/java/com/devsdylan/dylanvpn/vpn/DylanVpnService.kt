package com.devsdylan.dylanvpn.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.devsdylan.dylanvpn.core.ConfigBuilder
import com.devsdylan.dylanvpn.core.CoreRuntime
import com.devsdylan.dylanvpn.core.Quota
import com.devsdylan.dylanvpn.core.Shaper
import com.devsdylan.dylanvpn.data.Account
import com.devsdylan.dylanvpn.data.AppLog
import com.devsdylan.dylanvpn.data.Profile
import com.devsdylan.dylanvpn.data.Store
import com.devsdylan.dylanvpn.data.Usage
import com.devsdylan.dylanvpn.util.Format
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

class DylanVpnService : VpnService() {

    private val executor = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var statsExecutor: ScheduledExecutorService? = null
    private var tun: ParcelFileDescriptor? = null
    private var controller: CoreController? = null
    private var shaper: Shaper? = null

    private var connectivity: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var currentNet: Network? = null
    private var pendingReconnect: ScheduledFuture<*>? = null

    @Volatile
    private var expiring = false

    @Volatile
    private var running = false

    /** L'utilisateur veut être connecté : la reconnexion automatique n'agit que dans ce cas. */
    @Volatile
    private var desired = false

    @Volatile
    private var reconnecting = false

    @Volatile
    private var waitingForNetwork = false

    @Volatile
    private var autoAttempts = 0

    @Volatile
    private var noDownSeconds = 0

    @Volatile
    private var lastStart = 0L

    @Volatile
    private var lastNetworkReconnect = 0L

    private val handler = object : CoreCallbackHandler {
        override fun startup(): Long = 0

        override fun shutdown(): Long = 0

        override fun onEmitStatus(code: Long, message: String?): Long {
            if (!message.isNullOrBlank()) AppLog.i("Core : $message")
            return 0
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            desired = false
            pendingReconnect?.cancel(false)
            executor.execute {
                unregisterNetworkMonitor()
                stopTunnel(true)
                stopSelf()
            }
        } else {
            executor.execute {
                autoAttempts = 0
                startTunnel(false)
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(auto: Boolean) {
        if (running) return
        val profile = Store.selectedProfile()
        if (profile == null) {
            fail("Aucun profil sélectionné")
            return
        }
        if (Store.isExpired(profile)) {
            fail("Cette config a expiré le " + Format.dateTime(profile.expiresAt))
            return
        }
        if (profile.mobileDataOnly && !isCellularDefault()) {
            fail("Cette configuration fonctionne uniquement sur les données mobiles.")
            return
        }
        if (profile.quotaBytes > 0 && Quota.exhausted(Usage.usedFor(profile), profile.quotaBytes)) {
            fail("Quota de données épuisé (" + Format.bytes(Usage.usedFor(profile)) + " utilisés)")
            return
        }
        desired = true
        expiring = false
        noDownSeconds = 0
        VpnState.set(VpnStatus.CONNECTING)
        if (profile.locked) {
            AppLog.i("Connexion au profil « ${profile.name} » (config verrouillée)")
        } else {
            AppLog.i("Connexion au profil « ${profile.name} » (${profile.protocol}, ${profile.host}:${profile.port})")
        }
        try {
            val settings = Store.settings.value
            CoreRuntime.init(applicationContext)
            var shaperPort = 0
            val limits = profile.limits
            if (limits.active) {
                val relay = Shaper(limits)
                shaperPort = relay.start()
                shaper = relay
                AppLog.i("Débit limité par la config (↓ " + Format.limit(limits.downBps) + ", ↑ " + Format.limit(limits.upBps) + ", par fichier " + Format.limit(limits.connBps) + ")")
            }
            val config = CoreRuntime.buildConfig(profile, true, shaperPort, settings.shareEnabled)
            if (settings.shareEnabled) {
                AppLog.i("Partage de connexion actif (proxy HTTP " + ConfigBuilder.SHARE_HTTP_PORT + ", SOCKS " + ConfigBuilder.SHARE_SOCKS_PORT + ")")
            }

            val builder = Builder()
                .setSession("DYLAN VPN")
                .setMtu(settings.mtu)
                .addAddress("10.10.14.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(settings.dns)
            // second DNS : Android bascule dessus si le premier ne répond pas (réseau instable)
            if (settings.dns2.isNotBlank() && settings.dns2 != settings.dns) builder.addDnsServer(settings.dns2)
            applyAppRules(builder, settings.appsMode, settings.apps)
            currentNet?.let {
                try {
                    builder.setUnderlyingNetworks(arrayOf(it))
                } catch (e: Exception) {
                    // facultatif
                }
            }
            val fd = builder.establish() ?: throw IllegalStateException("Permission VPN refusée")
            tun = fd

            val c = Libv2ray.newCoreController(handler)
            controller = c
            c.startLoop(config, fd.fd)
            if (!c.isRunning) throw IllegalStateException("Le core n'a pas démarré")

            running = true
            lastStart = System.currentTimeMillis()
            registerNetworkMonitor()
            startStats(c, profile)
            if (settings.connCheck) {
                VpnState.set(VpnStatus.CHECKING)
                AppLog.i("Tunnel ouvert")
                Thread { checkConnection(profile) }.start()
            } else {
                VpnState.set(VpnStatus.CONNECTED)
                AppLog.i(profile.customServerMessage.ifBlank { "Connecté" })
            }
            if (profile.userMessage.isNotBlank()) AppLog.i(profile.userMessage)
        } catch (t: Throwable) {
            val message = CoreRuntime.scrub(profile, CoreRuntime.friendly(t))
            AppLog.e("Échec de la connexion : $message")
            stopTunnel(false)
            val retry = auto && desired && Store.settings.value.autoReconnect && autoAttempts < MAX_AUTO
            if (retry) {
                autoAttempts++
                AppLog.w("Nouvelle tentative ($autoAttempts/$MAX_AUTO) dans 5 s")
                VpnState.set(VpnStatus.CONNECTING)
                scheduler.schedule({ executor.execute { startTunnel(true) } }, 5, TimeUnit.SECONDS)
            } else {
                fail(message)
            }
        }
    }

    /** Échec définitif : état d'erreur, arrêt du service. */
    private fun fail(message: String) {
        desired = false
        unregisterNetworkMonitor()
        AppLog.e(message)
        VpnState.set(VpnStatus.ERROR, message)
        stopSelf()
    }

    // ----- stabilité : surveillance du réseau et reconnexion automatique -----

    private fun registerNetworkMonitor() {
        if (netCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = currentNet
                currentNet = network
                try {
                    setUnderlyingNetworks(arrayOf(network))
                } catch (e: Exception) {
                    // facultatif
                }
                if (!desired || System.currentTimeMillis() - lastStart < 5000) return
                if (previous == null && waitingForNetwork) {
                    waitingForNetwork = false
                    AppLog.i("Réseau rétabli")
                    scheduleReconnect("Réseau rétabli", false)
                } else if (previous != null && previous != network) {
                    AppLog.i("Changement de réseau")
                    scheduleReconnect("Changement de réseau", false)
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val profile = Store.selectedProfile() ?: return
                if (profile.mobileDataOnly &&
                    (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) && desired
                ) {
                    executor.execute {
                        if (desired && profile.mobileDataOnly) {
                            AppLog.w("Connexion arrêtée : cette config est limitée aux données mobiles.")
                            desired = false
                            stopTunnel(false)
                            VpnState.set(VpnStatus.ERROR, "Cette configuration fonctionne uniquement sur les données mobiles.")
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                if (network == currentNet) {
                    currentNet = null
                    if (desired) {
                        waitingForNetwork = true
                        AppLog.w("Aucun réseau : en attente de reconnexion.")
                        if (Store.settings.value.autoReconnect) {
                            VpnState.setStatus(VpnStatus.WAITING)
                        } else {
                            VpnState.setWarning("Réseau perdu")
                        }
                    }
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
            connectivity = cm
        } catch (e: Exception) {
            AppLog.w("Surveillance du réseau indisponible : " + e.message)
        }
    }

    private fun unregisterNetworkMonitor() {
        val cb = netCallback ?: return
        try {
            connectivity?.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            // déjà retiré
        }
        netCallback = null
        connectivity = null
        currentNet = null
        waitingForNetwork = false
    }

    private fun isCellularDefault(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Planifie une reconnexion. [counted] = vient du chien de garde (limitée à [MAX_AUTO] essais). */
    private fun scheduleReconnect(reason: String, counted: Boolean) {
        if (!desired || !Store.settings.value.autoReconnect) return
        if (!counted) {
            // changements de réseau rapprochés (Wi-Fi/données qui basculent) : une seule reconnexion toutes les 15 s
            val now = System.currentTimeMillis()
            if (now - lastNetworkReconnect < 15_000) return
            lastNetworkReconnect = now
        }
        if (counted) {
            if (autoAttempts >= MAX_AUTO) {
                VpnState.setWarning("Le serveur ne répond plus. Reconnexion automatique abandonnée : essaie une autre config.")
                return
            }
            autoAttempts++
        }
        pendingReconnect?.cancel(false)
        pendingReconnect = scheduler.schedule({ reconnect(reason) }, 2, TimeUnit.SECONDS)
    }

    private fun reconnect(reason: String) {
        if (!desired || reconnecting) return
        reconnecting = true
        executor.execute {
            try {
                AppLog.w("$reason : reconnexion...")
                stopTunnel(false)
                VpnState.set(VpnStatus.CONNECTING)
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                if (desired) startTunnel(true)
            } finally {
                reconnecting = false
            }
        }
    }

    // ----- applications, vérification, statistiques -----

    /** Choix des applications qui passent par le VPN (tunnel par application). */
    private fun applyAppRules(builder: Builder, mode: String, chosen: Set<String>) {
        if (mode == "only" && chosen.isNotEmpty()) {
            // Seules ces applications utilisent le VPN ; la nôtre n'y est pas, donc le core joint le serveur.
            for (pkg in chosen) {
                try {
                    builder.addAllowedApplication(pkg)
                } catch (e: Exception) {
                    AppLog.w("Application ignorée ($pkg) : " + e.message)
                }
            }
            AppLog.i("Tunnel limité à " + chosen.size + " application(s)")
            return
        }
        if (mode == "only") AppLog.w("Aucune application choisie : toutes passent par le VPN")
        if (mode == "except") {
            for (pkg in chosen) {
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (e: Exception) {
                    AppLog.w("Application ignorée ($pkg) : " + e.message)
                }
            }
            if (chosen.isNotEmpty()) AppLog.i(chosen.size.toString() + " application(s) hors du tunnel")
        }
        try {
            // L'application elle-même reste hors du tunnel : c'est ce qui permet au core de joindre le serveur.
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            AppLog.w("Impossible d'exclure l'application du tunnel : " + e.message)
        }
    }

    /** Vérifie que le serveur répond à travers le tunnel (comme NPV Tunnel). */
    private fun checkConnection(profile: Profile) {
        AppLog.i("Vérification de la connexion...")
        try {
            val ms = CoreRuntime.measure(applicationContext, profile)
            if (running) {
                VpnState.set(VpnStatus.CONNECTED)
                AppLog.i(profile.customServerMessage.ifBlank { "Connexion vérifiée ($ms ms) : le trafic passe par le tunnel." })
            }
        } catch (t: Throwable) {
            val message = CoreRuntime.scrub(profile, CoreRuntime.friendly(t))
            AppLog.w("Échec de la vérification : le serveur n'a pas répondu à travers le tunnel ($message). Le trafic peut ne pas passer ; essaie une autre configuration.")
            if (running) {
                VpnState.set(VpnStatus.CONNECTED)
                VpnState.setWarning("Le serveur ne répond pas. Le trafic peut ne pas passer : essaie une autre configuration.")
            }
            // pas de reconnexion automatique ici : un serveur lent ferait couper le VPN en boucle
        }
    }

    private fun startStats(c: CoreController, profile: Profile) {
        val ex = Executors.newSingleThreadScheduledExecutor()
        statsExecutor = ex
        val usageKey = Usage.keyFor(profile, Account.session.value?.login ?: "")
        ex.scheduleWithFixedDelay({
            try {
                var up = 0L
                var down = 0L
                for (entry in c.queryAllOutboundTrafficStats().split(';')) {
                    val parts = entry.split(',')
                    if (parts.size < 3 || parts[0] != "proxy") continue
                    val value = parts[2].trim().toLongOrNull() ?: continue
                    if (parts[1].contains("up", ignoreCase = true)) {
                        up += value
                    } else if (parts[1].contains("down", ignoreCase = true)) {
                        down += value
                    }
                }
                VpnState.updateTraffic(up, down)

                // quota de données : on compte ce qui passe, et on coupe quand il est épuisé
                if (up + down > 0) Usage.add(usageKey, up + down)
                if (profile.quotaBytes > 0 && Quota.exhausted(Usage.used(usageKey), profile.quotaBytes) && !expiring) {
                    expiring = true
                    desired = false
                    executor.execute {
                        unregisterNetworkMonitor()
                        stopTunnel(false)
                        fail("Quota de données épuisé")
                    }
                }

                // chien de garde : du trafic part mais rien ne revient pendant 40 s => connexion morte
                if (up > 0 && down == 0L) {
                    noDownSeconds++
                } else {
                    if (down > 0) autoAttempts = 0
                    noDownSeconds = 0
                }
                if (noDownSeconds >= WATCHDOG_SECONDS) {
                    noDownSeconds = 0
                    scheduleReconnect("Aucune réponse du serveur", true)
                }

                if (profile.expiresAt > 0 && Store.now() >= profile.expiresAt && !expiring) {
                    expiring = true
                    desired = false
                    executor.execute {
                        unregisterNetworkMonitor()
                        stopTunnel(false)
                        fail("Cette config a expiré")
                    }
                }
            } catch (t: Throwable) {
                // le core est peut-être en train de s'arrêter
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun stopTunnel(setIdle: Boolean) {
        running = false
        statsExecutor?.shutdownNow()
        statsExecutor = null
        shaper?.stop()
        shaper = null
        val c = controller
        controller = null
        if (c != null) {
            try {
                c.stopLoop()
            } catch (t: Throwable) {
                AppLog.w("Arrêt du core : ${t.message}")
            }
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        try {
            tun?.close()
        } catch (e: Exception) {
            AppLog.w("Fermeture du tunnel : ${e.message}")
        }
        tun = null
        if (setIdle && VpnState.state.value.status != VpnStatus.ERROR) {
            VpnState.set(VpnStatus.IDLE)
            AppLog.i("Déconnecté")
        }
    }

    override fun onRevoke() {
        desired = false
        executor.execute {
            unregisterNetworkMonitor()
            stopTunnel(true)
            stopSelf()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        desired = false
        pendingReconnect?.cancel(false)
        executor.execute {
            unregisterNetworkMonitor()
            stopTunnel(true)
        }
        executor.shutdown()
        scheduler.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.devsdylan.dylanvpn.STOP"
        private const val MAX_AUTO = 3
        private const val WATCHDOG_SECONDS = 40

        fun start(context: Context) {
            context.startService(Intent(context, DylanVpnService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DylanVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
