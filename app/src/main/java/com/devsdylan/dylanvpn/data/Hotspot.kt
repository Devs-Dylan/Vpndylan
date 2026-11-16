package com.devsdylan.dylanvpn.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class HotspotState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val ssid: String = "",
    val password: String = "",
    val error: String = ""
)

/**
 * Point d'accès Wi-Fi local créé par l'application (API « LocalOnlyHotspot »).
 * Android choisit lui-même le nom et le mot de passe ; ce point d'accès n'a pas Internet par lui-même :
 * les appareils connectés utilisent le proxy de partage de DYLAN VPN pour sortir.
 */
object Hotspot {
    private val _state = MutableStateFlow(HotspotState())
    val state: StateFlow<HotspotState> = _state

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (reservation != null || _state.value.starting) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        _state.value = HotspotState(starting = true)
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    val (ssid, password) = credentials(res)
                    _state.value = HotspotState(running = true, ssid = ssid, password = password)
                    AppLog.i("Point d'accès Wi-Fi démarré")
                }

                override fun onStopped() {
                    reservation = null
                    _state.value = HotspotState()
                    AppLog.i("Point d'accès Wi-Fi arrêté")
                }

                override fun onFailed(reason: Int) {
                    reservation = null
                    val message = explain(reason)
                    _state.value = HotspotState(error = message)
                    AppLog.e("Point d'accès Wi-Fi : $message")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            _state.value = HotspotState(error = "Permission refusée (active aussi la localisation de l'appareil)")
        } catch (e: IllegalStateException) {
            _state.value = HotspotState(error = e.message ?: "Impossible de démarrer le point d'accès")
        }
    }

    fun stop() {
        try {
            reservation?.close()
        } catch (e: Exception) {
            // déjà fermé
        }
        reservation = null
        _state.value = HotspotState()
    }

    @Suppress("DEPRECATION")
    private fun credentials(res: WifiManager.LocalOnlyHotspotReservation): Pair<String, String> {
        if (Build.VERSION.SDK_INT >= 30) {
            val config = res.softApConfiguration
            return Pair(config.ssid ?: "", config.passphrase ?: "")
        }
        val config = res.wifiConfiguration
        return Pair((config?.SSID ?: "").trim('"'), (config?.preSharedKey ?: "").trim('"'))
    }

    private fun explain(reason: Int): String =
        when (reason) {
            WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "Aucun canal Wi-Fi disponible"
            WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
                "Mode incompatible : désactive le partage de connexion d'Android et le Wi-Fi Direct"
            WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "Le partage est interdit sur cet appareil"
            else -> "Échec (active le Wi-Fi et la localisation, puis réessaie)"
        }
}
