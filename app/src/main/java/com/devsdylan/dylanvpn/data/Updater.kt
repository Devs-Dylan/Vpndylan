package com.devsdylan.dylanvpn.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.devsdylan.dylanvpn.BuildConfig
import com.devsdylan.dylanvpn.core.GitHubClient
import com.devsdylan.dylanvpn.core.GitHubException
import com.devsdylan.dylanvpn.core.Updates
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class UpdateStatus { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, ERROR }

data class UpdateState(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val message: String = "",
    val progress: Float = 0f
)

/** Mise à jour de l'application depuis la dernière publication GitHub (Releases, étiquette « latest »). */
object Updater {
    private const val TAG = "latest"

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state

    private var prefs: SharedPreferences? = null
    private var asset: GitHubClient.ReleaseAsset? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("dylanvpn_update", Context.MODE_PRIVATE)
    }

    private fun client() = GitHubClient(Repository.OWNER, Repository.REPO, "main", Repository.anyToken())

    /** Cherche une nouvelle version. Bloquant : à appeler hors du thread principal. */
    @Synchronized
    fun check() {
        val p = prefs ?: return
        _state.value = UpdateState(UpdateStatus.CHECKING, "Recherche de mise à jour...")
        try {
            val found = client().latestApk(TAG)
            if (found == null) {
                asset = null
                _state.value = UpdateState(UpdateStatus.UP_TO_DATE, "Aucune version publiée pour l'instant")
                return
            }
            val created = try {
                Instant.parse(found.createdAt).toEpochMilli()
            } catch (e: Exception) {
                0L
            }
            if (p.getLong("build_time", 0L) != BuildConfig.BUILD_TIME) {
                p.edit().putLong("build_time", BuildConfig.BUILD_TIME).putString("baseline", "").apply()
            }
            val baseline = p.getString("baseline", "") ?: ""
            val decision = Updates.decide(BuildConfig.BUILD_TIME, baseline, found.id.toString(), created)
            if (decision.baseline != baseline) p.edit().putString("baseline", decision.baseline).apply()
            if (decision.available) {
                asset = found
                val mb = String.format(java.util.Locale.FRANCE, "%.1f", found.size / 1048576.0)
                _state.value = UpdateState(UpdateStatus.AVAILABLE, "Nouvelle version disponible ($mb Mo)")
            } else {
                asset = null
                _state.value = UpdateState(UpdateStatus.UP_TO_DATE, "Tu as la dernière version")
            }
        } catch (e: GitHubException) {
            _state.value = UpdateState(UpdateStatus.ERROR, e.message ?: "Erreur de mise à jour")
        }
    }

    private fun apkFile(context: Context) = File(context.cacheDir, "update/DylanVPN.apk")

    /** Télécharge la nouvelle version. Bloquant. */
    fun download(context: Context) {
        val a = asset ?: return
        _state.value = UpdateState(UpdateStatus.DOWNLOADING, "Téléchargement...", 0f)
        try {
            val file = apkFile(context)
            file.delete()
            client().downloadAsset(a.id, file) { done, total ->
                val ratio = if (total > 0) done.toFloat() / total else 0f
                _state.value = UpdateState(UpdateStatus.DOWNLOADING, "Téléchargement...", ratio)
            }
            val header = file.inputStream().use { input -> ByteArray(2).also { input.read(it) } }
            if (file.length() < 100_000 || header[0] != 'P'.code.toByte() || header[1] != 'K'.code.toByte()) {
                file.delete()
                throw GitHubException(0, "Le fichier téléchargé n'est pas une application valide")
            }
            _state.value = UpdateState(UpdateStatus.READY, "Prêt à installer", 1f)
        } catch (e: GitHubException) {
            _state.value = UpdateState(UpdateStatus.ERROR, e.message ?: "Téléchargement impossible")
        }
    }

    /** Lance l'installation. Retourne un message si l'autorisation « sources inconnues » manque. */
    fun install(context: Context): String? {
        val file = apkFile(context)
        if (!file.exists()) return "Fichier introuvable : télécharge d'abord la mise à jour"
        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "Autorise DYLAN VPN à installer des applications, puis reviens appuyer sur Installer"
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return null
    }
}
