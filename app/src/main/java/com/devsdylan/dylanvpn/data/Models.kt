package com.devsdylan.dylanvpn.data

data class Profile(
    val id: String,
    val name: String,
    val link: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val locked: Boolean = false,
    val cid: String = "",
    val expiresAt: Long = 0,
    val limitDown: Long = 0,
    val limitUp: Long = 0,
    val limitConn: Long = 0,
    val burst: Long = 0,
    val synced: Boolean = false,
    val quotaBytes: Long = 0,
    val quotaScope: String = "file",
    val maxSyncs: Int = 0,
    val mobileDataOnly: Boolean = false,
    val userMessage: String = "",
    val customServerMessage: String = ""
) {
    val limits: com.devsdylan.dylanvpn.core.Limits
        get() = com.devsdylan.dylanvpn.core.Limits(limitDown, limitUp, limitConn, burst)
}

data class AppSettings(
    val speedUnit: String = "bps",
    val logLimit: Int = 1000,
    val mtu: Int = 1500,
    val dns: String = "1.1.1.1",
    val dns2: String = "8.8.8.8",
    val coreLogLevel: String = "warning",
    val autoSync: Boolean = true,
    val blockQuic: Boolean = true,
    val localDns: Boolean = true,
    val mux: Boolean = false,
    val fragment: Boolean = false,
    val bypassLan: Boolean = false,
    val confirmDelete: Boolean = false,
    val pingAfterSync: Boolean = false,
    val autoReconnect: Boolean = true,
    val shareEnabled: Boolean = false,
    val connCheck: Boolean = true,
    val appsMode: String = "all",
    val apps: Set<String> = emptySet()
)

data class AddResult(val added: Int, val errors: List<String>)
