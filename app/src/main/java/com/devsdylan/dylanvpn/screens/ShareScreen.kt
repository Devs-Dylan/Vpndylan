package com.devsdylan.dylanvpn.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devsdylan.dylanvpn.core.ConfigBuilder
import com.devsdylan.dylanvpn.core.Net
import com.devsdylan.dylanvpn.data.Hotspot
import com.devsdylan.dylanvpn.data.Store
import com.devsdylan.dylanvpn.ui.Pal
import com.devsdylan.dylanvpn.vpn.DylanVpnService
import com.devsdylan.dylanvpn.vpn.VpnState
import com.devsdylan.dylanvpn.vpn.VpnStatus

@Composable
fun ShareScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val hotspot by Hotspot.state.collectAsStateWithLifecycle()
    val settings by Store.settings.collectAsStateWithLifecycle()
    val vpn by VpnState.state.collectAsStateWithLifecycle()
    val connected = vpn.status == VpnStatus.CONNECTED

    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            Hotspot.start(context)
        } else {
            Toast.makeText(context, "Permission refusée : impossible de créer le point d'accès", Toast.LENGTH_LONG).show()
        }
    }
    val onToggleHotspot: () -> Unit = {
        if (hotspot.running || hotspot.starting) {
            Hotspot.stop()
        } else if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            Hotspot.start(context)
        } else {
            launcher.launch(permission)
        }
    }
    val onShareToggle: (Boolean) -> Unit = { on ->
        Store.updateSettings { it.copy(shareEnabled = on) }
        if (connected) {
            // le proxy de partage est ajouté à la configuration du core : on reconnecte pour l'appliquer
            Toast.makeText(context, "Reconnexion pour appliquer le partage...", Toast.LENGTH_SHORT).show()
            DylanVpnService.stop(context)
            Handler(Looper.getMainLooper()).postDelayed({ DylanVpnService.start(context) }, 1500)
        }
    }
    val addresses = remember(hotspot.running, connected, settings.shareEnabled) { Net.privateAddresses() }

    fun copy(label: String, text: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "$label copié", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ShareCard {
            Text("1. Point d'accès Wi-Fi", style = MaterialTheme.typography.titleMedium, color = Pal.Text)
            Text(
                when {
                    hotspot.running -> "Actif : un autre appareil peut s'y connecter."
                    hotspot.starting -> "Démarrage..."
                    else -> "Arrêté"
                },
                color = if (hotspot.running) Pal.Green else Pal.Sub
            )
            if (hotspot.running) {
                Text("Nom du réseau (SSID)", color = Pal.Sub)
                Text(hotspot.ssid, color = Pal.Accent, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { copy("Nom", hotspot.ssid) }) { Text("Copier le nom") }
                Text("Mot de passe Wi-Fi", color = Pal.Sub)
                Text(hotspot.password, color = Pal.Accent, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { copy("Mot de passe", hotspot.password) }) { Text("Copier le mot de passe") }
            }
            if (hotspot.error.isNotEmpty()) Text(hotspot.error, color = Pal.Red)
            Button(onClick = onToggleHotspot) {
                Text(if (hotspot.running || hotspot.starting) "Arrêter le point d'accès" else "Créer le point d'accès")
            }
            Text(
                "Android choisit lui-même le nom et le mot de passe du réseau : une application ne peut pas les modifier.",
                color = Pal.Sub
            )
        }

        ShareCard {
            Text("2. Partager la connexion VPN", style = MaterialTheme.typography.titleMedium, color = Pal.Text)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Proxy de partage", color = Pal.Text)
                    Text("Les appareils du point d'accès sortent par ton VPN.", color = Pal.Sub)
                }
                Switch(checked = settings.shareEnabled, onCheckedChange = onShareToggle)
            }
            if (settings.shareEnabled && !connected) {
                Text("Connecte le VPN (onglet Accueil) pour activer le partage.", color = Pal.Amber)
            }
            if (settings.shareEnabled && connected) {
                Text("Sur l'autre appareil, dans les réglages Wi-Fi du réseau : Proxy manuel", color = Pal.Text)
                if (addresses.isEmpty()) {
                    Text("Adresse du téléphone introuvable : crée d'abord le point d'accès.", color = Pal.Amber)
                }
                for (entry in addresses) {
                    val http = entry.second + ":" + ConfigBuilder.SHARE_HTTP_PORT
                    Text("Hôte " + entry.second + " (" + entry.first + ")", color = Pal.Accent)
                    Text("Port HTTP " + ConfigBuilder.SHARE_HTTP_PORT + " · SOCKS5 " + ConfigBuilder.SHARE_SOCKS_PORT, color = Pal.Sub)
                    TextButton(onClick = { copy("Proxy", http) }) { Text("Copier $http") }
                }
            }
            Text(
                "Seules les applications qui respectent le proxy du Wi-Fi (navigateurs, la plupart des applications de bureau) " +
                    "passent par le VPN. Le proxy refuse toute adresse qui n'est pas locale.",
                color = Pal.Sub
            )
        }

        ShareCard {
            Text("3. Partage de connexion d'Android", style = MaterialTheme.typography.titleMedium, color = Pal.Text)
            Text(
                "Pour choisir toi-même le nom et le mot de passe, active le point d'accès d'Android : " +
                    "le proxy de DYLAN VPN fonctionne aussi avec lui.",
                color = Pal.Sub
            )
            TextButton(onClick = {
                try {
                    context.startActivity(Intent("android.settings.TETHER_SETTINGS"))
                } catch (e: ActivityNotFoundException) {
                    try {
                        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                    } catch (e2: ActivityNotFoundException) {
                        Toast.makeText(context, "Réglage indisponible sur cet appareil", Toast.LENGTH_SHORT).show()
                    }
                }
            }) { Text("Ouvrir les réglages du partage") }
        }
    }
}

@Composable
private fun ShareCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Pal.Card)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            content()
        }
    }
}
