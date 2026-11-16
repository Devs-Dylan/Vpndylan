package com.devsdylan.dylanvpn.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class VpnStatus { IDLE, CONNECTING, CHECKING, CONNECTED, WAITING, ERROR }

data class VpnUiState(
    val status: VpnStatus = VpnStatus.IDLE,
    val message: String = "",
    val upSpeed: Long = 0,
    val downSpeed: Long = 0,
    val upTotal: Long = 0,
    val downTotal: Long = 0,
    val upPeak: Long = 0,
    val downPeak: Long = 0,
    val connectedAt: Long = 0,
    val warning: String = ""
)

/** État partagé entre le service VPN et l'interface. */
object VpnState {
    private val _state = MutableStateFlow(VpnUiState())
    val state: StateFlow<VpnUiState> = _state

    fun set(status: VpnStatus, message: String = "") {
        _state.value = VpnUiState(
            status = status,
            message = message,
            connectedAt = if (status == VpnStatus.CONNECTED) System.currentTimeMillis() else 0
        )
    }

    /** Change l'état sans effacer les statistiques de trafic. */
    fun setStatus(status: VpnStatus) {
        _state.update { it.copy(status = status, warning = "") }
    }

    fun setWarning(text: String) {
        _state.update { it.copy(warning = text) }
    }

    /** Octets échangés depuis la dernière mesure (environ 1 seconde). */
    fun updateTraffic(up: Long, down: Long) {
        _state.update {
            it.copy(
                upSpeed = up,
                downSpeed = down,
                upTotal = it.upTotal + up,
                downTotal = it.downTotal + down,
                upPeak = maxOf(it.upPeak, up),
                downPeak = maxOf(it.downPeak, down)
            )
        }
    }
}
