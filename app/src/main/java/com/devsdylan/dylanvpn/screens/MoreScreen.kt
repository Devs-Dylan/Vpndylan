package com.devsdylan.dylanvpn.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devsdylan.dylanvpn.BuildConfig
import com.devsdylan.dylanvpn.data.Account
import com.devsdylan.dylanvpn.data.Repository
import com.devsdylan.dylanvpn.data.UpdateStatus
import com.devsdylan.dylanvpn.data.Updater
import com.devsdylan.dylanvpn.data.SecureExport
import com.devsdylan.dylanvpn.ui.NpvCard
import com.devsdylan.dylanvpn.ui.Pal
import com.devsdylan.dylanvpn.ui.SectionLabel

@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val session by Account.session.collectAsStateWithLifecycle()
    val update by Updater.state.collectAsStateWithLifecycle()
    val sync by Repository.status.collectAsStateWithLifecycle()

    fun openSystem(action: String) {
        try {
            context.startActivity(Intent(action))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Ce réglage n'est pas disponible sur cet appareil", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionLabel("COMPTE")
        session?.let {
            NpvCard(modifier = Modifier.padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(it.login + " · " + categoryLabel(it.category), color = Pal.Accent, fontFamily = Pal.Mono, fontWeight = FontWeight.Bold)
                    Text(
                        if (sync.running) "Synchronisation en cours..." else sync.message.ifEmpty { "Pas encore synchronisé" },
                        color = Pal.Sub,
                        fontSize = 12.sp
                    )
                }
            }
        }
        MoreItem(Icons.Default.Refresh, "Synchroniser les configs", "Récupérer les configs de ta catégorie et celles qui te sont réservées") {
            Repository.syncAsync()
            Toast.makeText(context, "Synchronisation...", Toast.LENGTH_SHORT).show()
        }
        MoreItem(Icons.Default.ExitToApp, "Se déconnecter", "Revenir à l'écran de connexion") { Account.logout() }

        SectionLabel("PARTAGE")
        MoreItem(Icons.Default.VpnKey, "Mon ID appareil", "Afficher ou copier l'identifiant de cet appareil") { onNavigate("about") }
        MoreItem(Icons.Default.VpnKey, "Ma clé publique", "À transmettre à la personne qui prépare un export privé") {
            clipboard.setText(AnnotatedString(SecureExport.publicKey()))
            Toast.makeText(context, "Clé publique copiée · empreinte ${SecureExport.fingerprint()}", Toast.LENGTH_LONG).show()
        }
        MoreItem(Icons.Default.Wifi, "Partage de connexion", "Créer un point d'accès Wi-Fi et partager le VPN") { onNavigate("share") }

        SectionLabel("APPLICATION")
        MoreItem(Icons.Default.Apps, "Proxy par application", "Choisir quelles applications passent par le VPN") { onNavigate("apps") }
        MoreItem(Icons.Default.Settings, "Réglages", "Personnaliser l'application (DNS, MTU, multiplexage...)") { onNavigate("settings") }
        MoreItem(
            Icons.Default.SystemUpdate,
            "Mise à jour de l'application",
            when (update.status) {
                UpdateStatus.AVAILABLE, UpdateStatus.READY -> "Nouvelle version disponible"
                UpdateStatus.UP_TO_DATE -> "Tu as la dernière version"
                else -> "Vérifier et installer la dernière version"
            }
        ) { onNavigate("update") }
        MoreItem(Icons.Default.BatteryChargingFull, "Optimisation de la batterie", "Autoriser DYLAN VPN à rester actif en arrière-plan") {
            openSystem(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        MoreItem(Icons.Default.VpnKey, "Profils VPN du système", "Ouvrir les réglages VPN d'Android (VPN permanent, suppression)") {
            openSystem(Settings.ACTION_VPN_SETTINGS)
        }
        if (session?.category == "admin") {
            MoreItem(Icons.Default.AdminPanelSettings, "Admin", "Utilisateurs, configs par catégorie ou par personne, dépôt Git") { onNavigate("admin") }
        }

        SectionLabel("À PROPOS")
        MoreItem(Icons.Outlined.Description, "Guide", "Premiers pas et explications") { onNavigate("guide") }
        MoreItem(Icons.Default.Info, "Informations de version", "Application " + BuildConfig.VERSION_NAME) { onNavigate("about") }
        Spacer(Modifier.height(24.dp))
    }
}

fun categoryLabel(category: String): String =
    when (category) {
        "basic" -> "Basic"
        "pro" -> "Pro"
        "admin" -> "Admin"
        else -> category
    }

@Composable
private fun MoreItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = Pal.Accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Pal.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(subtitle, color = Pal.Sub, fontSize = 12.sp)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Pal.Line))
    }
}
