package com.devsdylan.dylanvpn.screens

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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devsdylan.dylanvpn.data.Store
import com.devsdylan.dylanvpn.ui.Pal

@Composable
fun SettingsScreen() {
    val s by Store.settings.collectAsStateWithLifecycle()
    var editMtu by remember { mutableStateOf(false) }
    var editDns by remember { mutableStateOf(false) }
    var editDns2 by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "Les réglages de connexion s'appliquent à la prochaine connexion.",
            color = Pal.Sub,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
        )
        CheckRow("Tester les configs après actualisation", "Mesurer le ping des configs reçues après une synchronisation", s.pingAfterSync) {
            Store.updateSettings { c -> c.copy(pingAfterSync = it) }
        }
        CheckRow("Confirmer avant de supprimer une config", "Demander une confirmation avant la suppression", s.confirmDelete) {
            Store.updateSettings { c -> c.copy(confirmDelete = it) }
        }
        ChoiceRow(
            "Niveau de journal",
            s.coreLogLevel,
            listOf(
                "none" to "Aucun",
                "error" to "Erreurs",
                "warning" to "Avertissements",
                "info" to "Informations",
                "debug" to "Débogage"
            )
        ) { v -> Store.updateSettings { it.copy(coreLogLevel = v) } }
        EditRow("MTU du VPN", "Taille maximale des paquets de l'interface VPN (1280 à 1500, défaut 1500)", s.mtu.toString()) { editMtu = true }
        EditRow("DNS principal du VPN", "Serveur DNS annoncé au système (défaut 1.1.1.1)", s.dns) { editDns = true }
        EditRow("DNS secondaire du VPN", "Utilisé si le principal ne répond pas : stabilise les réseaux instables (défaut 8.8.8.8)", s.dns2) { editDns2 = true }
        CheckRow("Contourner le réseau local", "Les adresses 10.x, 172.16.x et 192.168.x ne passent pas par le tunnel", s.bypassLan) {
            Store.updateSettings { c -> c.copy(bypassLan = it) }
        }
        CheckRow("Bloquer QUIC (UDP 443)", "Force les applications à utiliser TCP : plus rapide et beaucoup plus stable dans le tunnel", s.blockQuic) {
            Store.updateSettings { c -> c.copy(blockQuic = it) }
        }
        CheckRow("DNS dans le tunnel", "Cache et réponses IPv4 : les pages se chargent plus vite, moins de coupures (recommandé)", s.localDns) {
            Store.updateSettings { c -> c.copy(localDns = it) }
        }
        ChoiceRow(
            "Unité de vitesse",
            s.speedUnit,
            listOf("bps" to "Bits par seconde (Kbps, Mbps)", "Bps" to "Octets par seconde (Ko/s, Mo/s)")
        ) { v -> Store.updateSettings { it.copy(speedUnit = v) } }
        ChoiceRow(
            "Limite des journaux",
            s.logLimit.toString(),
            listOf("200" to "200 lignes", "500" to "500 lignes", "1000" to "1000 lignes", "2000" to "2000 lignes")
        ) { v -> Store.updateSettings { it.copy(logLimit = v.toInt()) } }

        Section("STABILITÉ ET SYNCHRONISATION") {
            CheckRow("Reconnexion automatique", "Relance le VPN lors d'un changement de réseau ou si la connexion devient morte", s.autoReconnect) {
                Store.updateSettings { c -> c.copy(autoReconnect = it) }
            }
            CheckRow("Vérification de la connexion", "Teste que le serveur répond à travers le tunnel après la connexion", s.connCheck) {
                Store.updateSettings { c -> c.copy(connCheck = it) }
            }
            CheckRow("Synchronisation au démarrage", "Télécharge les configs du dépôt à l'ouverture de l'application", s.autoSync) {
                Store.updateSettings { c -> c.copy(autoSync = it) }
            }
        }
        Section("PROXY LOCAL") {
            CheckRow("Proxy de partage (HTTP 10809 · SOCKS 10808)", "Permet aux appareils du point d'accès d'utiliser le VPN. Reconnecte le VPN pour l'appliquer.", s.shareEnabled) {
                Store.updateSettings { c -> c.copy(shareEnabled = it) }
            }
        }
        Section("V2RAY · MULTIPLEXAGE") {
            CheckRow("Activer le multiplexage", "Regroupe plusieurs connexions dans une seule (non appliqué aux configs Vision)", s.mux) {
                Store.updateSettings { c -> c.copy(mux = it) }
            }
        }
        Section("V2RAY · FRAGMENTATION") {
            CheckRow("Fragmenter le TLS hello", "Découpe le début de la connexion TLS (utile contre certains filtrages ; désactivé si la config limite le débit)", s.fragment) {
                Store.updateSettings { c -> c.copy(fragment = it) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (editMtu) {
        EditDialog("MTU du VPN", s.mtu.toString(), { editMtu = false }) { text ->
            val v = text.trim().toIntOrNull()
            if (v == null || v !in 1280..1500) "Saisis un nombre entre 1280 et 1500"
            else {
                Store.updateSettings { it.copy(mtu = v) }
                null
            }
        }
    }
    if (editDns) {
        EditDialog("DNS principal", s.dns, { editDns = false }) { text ->
            if (!isIpv4(text)) "Adresse IPv4 invalide (ex. 1.1.1.1)"
            else {
                Store.updateSettings { it.copy(dns = text.trim()) }
                null
            }
        }
    }
    if (editDns2) {
        EditDialog("DNS secondaire", s.dns2, { editDns2 = false }) { text ->
            if (!isIpv4(text)) "Adresse IPv4 invalide (ex. 8.8.8.8)"
            else {
                Store.updateSettings { it.copy(dns2 = text.trim()) }
                null
            }
        }
    }
}

private fun isIpv4(text: String): Boolean {
    val parts = text.trim().split('.')
    return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Pal.CardHi)
                .clickable { open = !open }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = Pal.Text, fontFamily = Pal.Mono, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            Icon(
                if (open) Icons.Default.ExpandMore else Icons.Default.UnfoldMore,
                contentDescription = null,
                tint = Pal.Sub,
                modifier = Modifier.size(20.dp)
            )
        }
        if (open) content()
    }
}

@Composable
private fun CheckRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChange(!checked) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Pal.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(desc, color = Pal.Sub, fontSize = 12.sp)
            }
            Checkbox(checked = checked, onCheckedChange = onChange)
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Pal.Line))
    }
}

@Composable
private fun EditRow(title: String, desc: String, value: String, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Pal.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(desc, color = Pal.Sub, fontSize = 12.sp)
                Text(value, color = Pal.Accent, fontFamily = Pal.Mono, fontSize = 13.sp)
            }
            Icon(Icons.Default.Edit, contentDescription = null, tint = Pal.Text, modifier = Modifier.size(20.dp))
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Pal.Line))
    }
}

@Composable
private fun ChoiceRow(title: String, current: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == current }?.second ?: current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Pal.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(label, color = Pal.Accent, fontFamily = Pal.Mono, fontSize = 13.sp)
            }
            Icon(Icons.Default.ExpandMore, contentDescription = null, tint = Pal.Sub)
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Pal.Line))
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column {
                    for (option in options) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(option.first)
                                    open = false
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = option.first == current, onClick = {
                                onSelect(option.first)
                                open = false
                            })
                            Spacer(Modifier.width(4.dp))
                            Text(option.second)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Fermer") } }
        )
    }
}

/** [onSave] retourne un message d'erreur, ou null si la valeur est acceptée. */
@Composable
private fun EditDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> String?) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = ""
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error.isNotEmpty()) Text(error, color = Pal.Red)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val problem = onSave(text)
                if (problem == null) onDismiss() else error = problem
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
