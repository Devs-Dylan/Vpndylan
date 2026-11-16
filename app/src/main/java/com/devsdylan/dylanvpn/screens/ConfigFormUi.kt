package com.devsdylan.dylanvpn.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devsdylan.dylanvpn.core.ConfigForm
import com.devsdylan.dylanvpn.core.FormFields
import com.devsdylan.dylanvpn.ui.Pal

/** Formulaire de configuration (protocole, adresse, transport, sécurité...), réutilisé par l'éditeur et par l'admin. */
@Composable
fun ConfigFormUi(f: FormFields, onChange: (FormFields) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DropdownField("Protocole", f.protocol, ConfigForm.PROTOCOLS) {
            onChange(f.copy(protocol = it, method = if (it == "ss") "chacha20-ietf-poly1305" else "auto"))
        }
        FormText("Remarque (nom)", f.name) { onChange(f.copy(name = it)) }
        FormText("Adresse", f.host) { onChange(f.copy(host = it)) }
        FormText("Port", f.port) { onChange(f.copy(port = it)) }
        when (f.protocol) {
            "vless" -> {
                FormText("UUID", f.secret) { onChange(f.copy(secret = it)) }
                FormText("Chiffrement (encryption)", f.encryption) { onChange(f.copy(encryption = it)) }
                DropdownField("Flow", f.flow, ConfigForm.FLOWS) { onChange(f.copy(flow = it)) }
            }
            "vmess" -> {
                FormText("UUID", f.secret) { onChange(f.copy(secret = it)) }
                DropdownField("Méthode", f.method, ConfigForm.VMESS_METHODS) { onChange(f.copy(method = it)) }
                FormText("AlterId", f.aid) { onChange(f.copy(aid = it)) }
            }
            "trojan" -> FormText("Mot de passe", f.secret) { onChange(f.copy(secret = it)) }
            else -> {
                FormText("Mot de passe", f.secret) { onChange(f.copy(secret = it)) }
                DropdownField("Méthode", f.method, ConfigForm.SS_METHODS) { onChange(f.copy(method = it)) }
            }
        }
        if (f.protocol != "ss") {
            DropdownField("Transport", f.transport, ConfigForm.TRANSPORTS) { onChange(f.copy(transport = it)) }
            if (f.transport == "tcp") {
                DropdownField("Type d'en-tête", f.headerType, listOf("none", "http")) { onChange(f.copy(headerType = it)) }
            }
            FormText("Host (ex. google.com)", f.hostHeader) { onChange(f.copy(hostHeader = it)) }
            if (f.transport == "ws" || f.transport == "httpupgrade" || f.transport == "xhttp" || f.transport == "tcp") {
                FormText("Path", f.path) { onChange(f.copy(path = it)) }
            }
            if (f.transport == "grpc") {
                FormText("Nom du service gRPC", f.serviceName) { onChange(f.copy(serviceName = it)) }
            }
            DropdownField("Sécurité", f.security, ConfigForm.SECURITIES) { onChange(f.copy(security = it)) }
            if (f.security != "none") {
                FormText("SNI", f.sni) { onChange(f.copy(sni = it)) }
                DropdownField("Empreinte (fingerprint)", f.fp, ConfigForm.FINGERPRINTS) { onChange(f.copy(fp = it)) }
                FormText("ALPN (ex. h2,http/1.1)", f.alpn) { onChange(f.copy(alpn = it)) }
            }
            if (f.security == "tls") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Autoriser les certificats non vérifiés", color = Pal.Text)
                        Text("allowInsecure : l'empreinte du certificat du serveur est épinglée à la connexion.", color = Pal.Sub)
                    }
                    Switch(checked = f.allowInsecure, onCheckedChange = { onChange(f.copy(allowInsecure = it)) })
                }
            }
            if (f.security == "reality") {
                FormText("Clé publique (pbk)", f.pbk) { onChange(f.copy(pbk = it)) }
                FormText("Short ID", f.sid) { onChange(f.copy(sid = it)) }
                FormText("SpiderX", f.spx) { onChange(f.copy(spx = it)) }
            }
        }
    }
}

@Composable
private fun FormText(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun DropdownField(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = value.ifEmpty { "—" },
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.ExpandMore, contentDescription = null) },
            modifier = Modifier.fillMaxWidth()
        )
        Box(modifier = Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(option.ifEmpty { "—" }) },
                    onClick = {
                        onSelect(option)
                        open = false
                    }
                )
            }
        }
    }
}
