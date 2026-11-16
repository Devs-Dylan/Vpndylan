package com.devsdylan.dylanvpn.data

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.devsdylan.dylanvpn.core.DylanConfig
import com.devsdylan.dylanvpn.core.DylanFormat
import com.devsdylan.dylanvpn.core.Expiry
import com.devsdylan.dylanvpn.core.SyncLimit
import com.devsdylan.dylanvpn.core.LinkParser
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Profils et réglages, enregistrés dans les préférences de l'application. */
object Store {
    private const val KEY_PROFILES = "profiles"
    private const val KEY_SELECTED = "selected"
    private const val KEY_DEVICE = "device_key_v2"

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("dylanvpn", Context.MODE_PRIVATE)
        prefs = p
        appContext = context.applicationContext
        _profiles.value = decodeProfiles(p.getString(KEY_PROFILES, "[]") ?: "[]")
        _selectedId.value = p.getString(KEY_SELECTED, null)
        val d = AppSettings()
        val s = AppSettings(
            speedUnit = p.getString("speed_unit", d.speedUnit) ?: d.speedUnit,
            logLimit = p.getInt("log_limit", d.logLimit),
            mtu = p.getInt("mtu", d.mtu),
            dns = p.getString("dns", d.dns) ?: d.dns,
            dns2 = p.getString("dns2", d.dns2) ?: d.dns2,
            coreLogLevel = p.getString("core_log_level", d.coreLogLevel) ?: d.coreLogLevel,
            autoSync = p.getBoolean("auto_sync", d.autoSync),
            blockQuic = p.getBoolean("block_quic", d.blockQuic),
            localDns = p.getBoolean("local_dns", d.localDns),
            mux = p.getBoolean("mux", d.mux),
            fragment = p.getBoolean("fragment", d.fragment),
            bypassLan = p.getBoolean("bypass_lan", d.bypassLan),
            confirmDelete = p.getBoolean("confirm_delete", d.confirmDelete),
            pingAfterSync = p.getBoolean("ping_after_sync", d.pingAfterSync),
            autoReconnect = p.getBoolean("auto_reconnect", d.autoReconnect),
            shareEnabled = p.getBoolean("share_enabled", d.shareEnabled),
            connCheck = p.getBoolean("conn_check", d.connCheck),
            appsMode = p.getString("apps_mode", d.appsMode) ?: d.appsMode,
            apps = p.getStringSet("apps", d.apps)?.toSet() ?: d.apps
        )
        _settings.value = s
        AppLog.limit = s.logLimit
    }

    /** Valeurs mémorisées (dernière adresse du serveur, empreinte de certificat...). */
    fun cacheGet(key: String): String? = prefs?.getString("cache_$key", null)

    fun cachePut(key: String, value: String) {
        prefs?.edit()?.putString("cache_$key", value)?.apply()
    }

    fun selectedProfile(): Profile? {
        val id = _selectedId.value ?: return null
        return _profiles.value.firstOrNull { it.id == id }
    }

    /**
     * Heure de référence : jamais antérieure à la plus tardive déjà observée, pour qu'un retour
     * en arrière de l'horloge de l'appareil ne prolonge pas une config.
     */
    fun now(): Long {
        val system = System.currentTimeMillis()
        val p = prefs ?: return system
        val seen = p.getLong("max_seen", 0L)
        if (system > seen) p.edit().putLong("max_seen", system).apply()
        return maxOf(system, seen)
    }

    fun isExpired(profile: Profile): Boolean = profile.expiresAt > 0 && now() >= profile.expiresAt

    private fun activations(): JSONObject =
        try {
            JSONObject(prefs?.getString("activations", "{}") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }

    /** Identifiant de cet appareil (DYL-XXXX-XXXX-XXXX-XXXX), à communiquer à l'administrateur. */
    fun deviceId(): String {
        val ctx = appContext ?: return "DYL-INCONNU"
        val raw = try {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            null
        } ?: "inconnu"
        return DylanFormat.deviceIdFromRaw(raw)
    }

    /** Ajoute un ou plusieurs liens (un par ligne) : vless, vmess, trojan, ss ou dylan. */
    fun addProfiles(text: String, expectedProtocol: String? = null, synced: Boolean = false, passphrase: String = ""): AddResult {
        val errors = ArrayList<String>()
        val list = ArrayList(_profiles.value)
        var added = 0
        var changed = false
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            try {
                var linkText = line
                var locked = false
                var nameOverride = ""
                var cfgForProfile: DylanConfig? = null
                var endAt = 0L
                if (DylanFormat.isDylan(line)) {
                    if (expectedProtocol != null && expectedProtocol != "dylan") {
                        errors.add("Ce lien est de type DYLAN alors que tu as choisi " + expectedProtocol.uppercase())
                        continue
                    }
                    val cfg = DylanFormat.decode(line, passphrase)
                    if (cfg.deviceId.isNotBlank() &&
                        DylanFormat.normalizeId(cfg.deviceId) != DylanFormat.normalizeId(deviceId())
                    ) {
                        errors.add("Cette config est réservée à un autre appareil. ID de cet appareil : " + deviceId())
                        continue
                    }
                    val nowMs = now()
                    var relative = 0L
                    if (cfg.durationMs > 0 && cfg.cid.isNotBlank()) {
                        val acts = activations()
                        relative = Expiry.relativeEnd(cfg, nowMs, acts.optLong(cfg.cid, 0L))
                        acts.put(cfg.cid, relative)
                        prefs?.edit()?.putString("activations", acts.toString())?.apply()
                    } else {
                        relative = Expiry.relativeEnd(cfg, nowMs, 0L)
                    }
                    endAt = Expiry.effectiveEnd(cfg, relative)
                    if (endAt > 0 && endAt <= nowMs) {
                        errors.add("Cette config a expiré le " + com.devsdylan.dylanvpn.util.Format.dateTime(endAt))
                        continue
                    }
                    cfgForProfile = cfg
                    linkText = cfg.link
                    locked = cfg.locked
                    nameOverride = cfg.name
                } else if (expectedProtocol == "dylan") {
                    errors.add("Ce n'est pas un lien dylan://")
                    continue
                }
                val link = LinkParser.parse(linkText)
                if (expectedProtocol != null && expectedProtocol != "dylan" && link.protocol != expectedProtocol) {
                    errors.add(
                        "Ce lien est de type " + link.protocol.uppercase() +
                            " alors que tu as choisi " + expectedProtocol.uppercase()
                    )
                    continue
                }
                val name = nameOverride.ifBlank { link.name }
                val cfgCid = cfgForProfile?.cid ?: ""
                val idx = list.indexOfFirst { it.link == linkText || (cfgCid.isNotBlank() && it.cid == cfgCid) }
                if (idx >= 0) {
                    if (synced && list[idx].synced) {
                        list[idx] = list[idx].copy(
                            name = name,
                            link = linkText,
                            protocol = link.protocol,
                            host = link.host,
                            port = link.port,
                            locked = locked,
                            cid = cfgCid,
                            expiresAt = endAt,
                            limitDown = cfgForProfile?.downBps ?: 0L,
                            limitUp = cfgForProfile?.upBps ?: 0L,
                            limitConn = cfgForProfile?.connBps ?: 0L,
                            burst = cfgForProfile?.burst ?: 0L,
                            quotaBytes = cfgForProfile?.quotaBytes ?: 0L,
                            quotaScope = cfgForProfile?.quotaScope ?: "file",
                            maxSyncs = cfgForProfile?.maxSyncs ?: 0,
                            mobileDataOnly = cfgForProfile?.mobileDataOnly ?: false,
                            userMessage = cfgForProfile?.userMessage ?: "",
                            customServerMessage = cfgForProfile?.customServerMessage ?: ""
                        )
                        changed = true
                    } else {
                        errors.add("« $name » est déjà enregistré")
                    }
                    continue
                }
                list.add(
                    Profile(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        link = linkText,
                        protocol = link.protocol,
                        host = link.host,
                        port = link.port,
                        locked = locked,
                        cid = cfgForProfile?.cid ?: "",
                        expiresAt = endAt,
                        limitDown = cfgForProfile?.downBps ?: 0L,
                        limitUp = cfgForProfile?.upBps ?: 0L,
                        limitConn = cfgForProfile?.connBps ?: 0L,
                        burst = cfgForProfile?.burst ?: 0L,
                        synced = synced,
                        quotaBytes = cfgForProfile?.quotaBytes ?: 0L,
                        quotaScope = cfgForProfile?.quotaScope ?: "file",
                        maxSyncs = cfgForProfile?.maxSyncs ?: 0,
                        mobileDataOnly = cfgForProfile?.mobileDataOnly ?: false,
                        userMessage = cfgForProfile?.userMessage ?: "",
                        customServerMessage = cfgForProfile?.customServerMessage ?: ""
                    )
                )
                added++
            } catch (e: IllegalArgumentException) {
                errors.add(e.message ?: "Lien invalide")
            }
        }
        if (added > 0 || changed) {
            _profiles.value = list
            if (selectedProfile() == null) _selectedId.value = list.first().id
            saveProfiles()
        }
        return AddResult(added, errors)
    }

    /**
     * Applique les configs reçues du dépôt Git : ajoute les nouvelles, met à jour les modifiées et
     * retire celles qui ont disparu. Les profils importés à la main ne sont pas touchés.
     */
    fun applySync(lines: List<String>): Int {
        val decoded = ArrayList<Pair<String, DylanConfig>>()
        for (l in lines) {
            try {
                decoded.add(Pair(l, DylanFormat.decode(l)))
            } catch (e: IllegalArgumentException) {
                // ligne illisible : ignorée
            }
        }
        val wanted = decoded.map { it.second.link }.toHashSet()
        val kept = _profiles.value.filter { !it.synced || it.link in wanted }
        if (kept.size != _profiles.value.size) {
            _profiles.value = kept
            if (selectedProfile() == null) _selectedId.value = kept.firstOrNull()?.id
            saveProfiles()
        }
        // limite du nombre de synchronisations par fichier : au-delà, la config n'est plus mise à jour
        val toApply = ArrayList<String>()
        for ((line, cfg) in decoded) {
            if (cfg.cid.isBlank()) {
                toApply.add(line)
                continue
            }
            val decision = SyncLimit.decide(Usage.syncCount(cfg.cid), cfg.maxSyncs)
            if (decision.apply) {
                toApply.add(line)
                Usage.setSyncCount(cfg.cid, decision.count)
            }
        }
        return addProfiles(toApply.joinToString("\n"), null, true).added
    }

    /** Remplace le lien d'un profil (édition). Retourne un message d'erreur, ou null. */
    fun updateProfile(id: String, newLink: String): String? {
        val current = _profiles.value.firstOrNull { it.id == id } ?: return "Config introuvable"
        if (current.locked) return "Cette config est verrouillée"
        return try {
            val parsed = LinkParser.parse(newLink)
            val line = newLink.trim()
            _profiles.value = _profiles.value.map {
                if (it.id == id) it.copy(name = parsed.name, link = line, protocol = parsed.protocol, host = parsed.host, port = parsed.port) else it
            }
            saveProfiles()
            null
        } catch (e: IllegalArgumentException) {
            e.message ?: "Lien invalide"
        }
    }

    fun deleteProfile(id: String) {
        val list = _profiles.value.filter { it.id != id }
        _profiles.value = list
        if (_selectedId.value == id) _selectedId.value = list.firstOrNull()?.id
        saveProfiles()
    }

    fun select(id: String) {
        _selectedId.value = id
        prefs?.edit()?.putString(KEY_SELECTED, id)?.apply()
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val s = transform(_settings.value)
        _settings.value = s
        AppLog.limit = s.logLimit
        prefs?.edit()
            ?.putString("speed_unit", s.speedUnit)
            ?.putInt("log_limit", s.logLimit)
            ?.putInt("mtu", s.mtu)
            ?.putString("dns", s.dns)
            ?.putString("dns2", s.dns2)
            ?.putString("core_log_level", s.coreLogLevel)
            ?.putBoolean("auto_sync", s.autoSync)
            ?.putBoolean("block_quic", s.blockQuic)
            ?.putBoolean("local_dns", s.localDns)
            ?.putBoolean("mux", s.mux)
            ?.putBoolean("fragment", s.fragment)
            ?.putBoolean("bypass_lan", s.bypassLan)
            ?.putBoolean("confirm_delete", s.confirmDelete)
            ?.putBoolean("ping_after_sync", s.pingAfterSync)
            ?.putBoolean("auto_reconnect", s.autoReconnect)
            ?.putBoolean("share_enabled", s.shareEnabled)
            ?.putBoolean("conn_check", s.connCheck)
            ?.putString("apps_mode", s.appsMode)
            ?.putStringSet("apps", s.apps)
            ?.apply()
    }

    /** Clé propre à l'appareil demandée par le core Xray : 32 octets aléatoires en base64 URL sans remplissage. */
    fun deviceKey(): String {
        val p = prefs ?: return randomDeviceKey()
        var key = p.getString(KEY_DEVICE, null)
        if (key == null) {
            key = randomDeviceKey()
            p.edit().putString(KEY_DEVICE, key).apply()
        }
        return key
    }

    private fun randomDeviceKey(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun saveProfiles() {
        val arr = JSONArray()
        for (p in _profiles.value) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("link", p.link)
                    .put("protocol", p.protocol)
                    .put("host", p.host)
                    .put("port", p.port)
                    .put("locked", p.locked)
                    .put("cid", p.cid)
                    .put("expiresAt", p.expiresAt)
                    .put("limitDown", p.limitDown)
                    .put("limitUp", p.limitUp)
                    .put("limitConn", p.limitConn)
                    .put("burst", p.burst)
                    .put("synced", p.synced)
                    .put("quotaBytes", p.quotaBytes)
                    .put("quotaScope", p.quotaScope)
                    .put("maxSyncs", p.maxSyncs)
                    .put("mobileDataOnly", p.mobileDataOnly)
                    .put("userMessage", p.userMessage)
                    .put("customServerMessage", p.customServerMessage)
            )
        }
        val editor = prefs?.edit() ?: return
        editor.putString(KEY_PROFILES, arr.toString())
        if (_selectedId.value != null) editor.putString(KEY_SELECTED, _selectedId.value)
        editor.apply()
    }

    private fun decodeProfiles(json: String): List<Profile> {
        val result = ArrayList<Profile>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result.add(
                    Profile(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        link = o.getString("link"),
                        protocol = o.getString("protocol"),
                        host = o.getString("host"),
                        port = o.getInt("port"),
                        locked = o.optBoolean("locked", false),
                        cid = o.optString("cid", ""),
                        expiresAt = o.optLong("expiresAt", 0L),
                        limitDown = o.optLong("limitDown", 0L),
                        limitUp = o.optLong("limitUp", 0L),
                        limitConn = o.optLong("limitConn", 0L),
                        burst = o.optLong("burst", 0L),
                        synced = o.optBoolean("synced", false),
                        quotaBytes = o.optLong("quotaBytes", 0L),
                        quotaScope = o.optString("quotaScope", "file"),
                        maxSyncs = o.optInt("maxSyncs", 0),
                        mobileDataOnly = o.optBoolean("mobileDataOnly", false),
                        userMessage = o.optString("userMessage", ""),
                        customServerMessage = o.optString("customServerMessage", "")
                    )
                )
            }
        } catch (e: Exception) {
            // données illisibles : on repart d'une liste vide
        }
        return result
    }
}
