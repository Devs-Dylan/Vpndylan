package com.devsdylan.dylanvpn.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devsdylan.dylanvpn.core.DylanConfig
import com.devsdylan.dylanvpn.core.ConfigForm
import com.devsdylan.dylanvpn.core.Quota
import com.devsdylan.dylanvpn.core.DylanFormat
import com.devsdylan.dylanvpn.core.FormFields
import com.devsdylan.dylanvpn.core.LinkParser
import com.devsdylan.dylanvpn.util.Format
import java.security.SecureRandom
import java.util.Calendar
import com.devsdylan.dylanvpn.core.Keyring
import com.devsdylan.dylanvpn.data.Account
import com.devsdylan.dylanvpn.data.Repository
import com.devsdylan.dylanvpn.data.Store
import com.devsdylan.dylanvpn.data.SecureExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.devsdylan.dylanvpn.ui.Pal

@Composable
private fun GenerateTab() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val profiles by Store.profiles.collectAsStateWithLifecycle()
    var source by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var device by remember { mutableStateOf("") }
    var locked by remember { mutableStateOf(true) }
    var output by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var selectedCats by remember { mutableStateOf(setOf<String>()) }
    var formOpen by remember { mutableStateOf(false) }
    var formFields by remember { mutableStateOf(FormFields()) }
    var publishing by remember { mutableStateOf(false) }
    var userChoices by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedUsers by remember { mutableStateOf(setOf<String>()) }
    var recipientKeys by remember { mutableStateOf("") }
    var allowSelf by remember { mutableStateOf(true) }
    var openToAll by remember { mutableStateOf(false) }
    var exportPassphrase by remember { mutableStateOf("") }
    var mobileDataOnly by remember { mutableStateOf(false) }
    var userMessage by remember { mutableStateOf("") }
    var serverMessage by remember { mutableStateOf("") }
    var exportFileName by remember { mutableStateOf("DYLAN-config.dylanx") }
    var lastConfig by remember { mutableStateOf<DylanConfig?>(null) }
    val exportFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(output.toByteArray(Charsets.UTF_8)) }
                    ?: throw IllegalStateException("Impossible d’écrire le fichier")
                Toast.makeText(context, "Export enregistré", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Échec de l’enregistrement", Toast.LENGTH_LONG).show()
            }
        }
    }
    LaunchedEffect(Unit) {
        try {
            userChoices = withContext(Dispatchers.IO) { Repository.listUsers().map { it.login } }
        } catch (e: Exception) {
            // la liste sera vide tant que le dépôt n'est pas initialisé
        }
    }

    // validité : 0 = aucune, 1 = durée à partir de l'import, 2 = date de fin fixe
    var expiryMode by remember { mutableStateOf(0) }
    var days by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    var endMs by remember { mutableStateOf(0L) }

    // débits en Ko/s (vide ou 0 = illimité) et priorité : 0 basse, 1 normale, 2 haute
    var downKb by remember { mutableStateOf("") }
    var upKb by remember { mutableStateOf("") }
    var connKb by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(1) }

    // quota de données, portée du quota et nombre max de synchronisations
    var quotaValue by remember { mutableStateOf("") }
    var quotaUnit by remember { mutableStateOf("Go") }
    var quotaScope by remember { mutableStateOf("file") }
    var maxSyncsText by remember { mutableStateOf("") }

    val parsedInfo = remember(source) { describeLink(source) }

    val pickDate: () -> Unit = {
        val base = Calendar.getInstance()
        if (endMs > 0) base.timeInMillis = endMs
        DatePickerDialog(
            context,
            { _, y, m, d ->
                val cal = Calendar.getInstance()
                if (endMs > 0) {
                    cal.timeInMillis = endMs
                } else {
                    cal.set(Calendar.HOUR_OF_DAY, 23)
                    cal.set(Calendar.MINUTE, 59)
                }
                cal.set(y, m, d)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                endMs = cal.timeInMillis
            },
            base.get(Calendar.YEAR),
            base.get(Calendar.MONTH),
            base.get(Calendar.DAY_OF_MONTH)
        ).show()
    }
    val pickTime: () -> Unit = {
        val base = Calendar.getInstance()
        if (endMs > 0) base.timeInMillis = endMs
        TimePickerDialog(
            context,
            { _, h, min ->
                val cal = Calendar.getInstance()
                if (endMs > 0) cal.timeInMillis = endMs
                cal.set(Calendar.HOUR_OF_DAY, h)
                cal.set(Calendar.MINUTE, min)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                endMs = cal.timeInMillis
            },
            base.get(Calendar.HOUR_OF_DAY),
            base.get(Calendar.MINUTE),
            true
        ).show()
    }

    val onGenerate: () -> Unit = {
        try {
            val line = source.trim()
            if (line.isEmpty()) throw IllegalArgumentException("Colle d'abord un lien source")
            if (DylanFormat.isDylan(line)) {
                throw IllegalArgumentException("Colle un lien vless://, vmess://, trojan:// ou ss:// (pas un lien dylan://)")
            }
            val parsed = LinkParser.parse(line)
            val id = device.trim()
            if (id.isNotEmpty() && !DylanFormat.isValidId(id)) {
                throw IllegalArgumentException("ID d'appareil invalide : format DYL-XXXX-XXXX-XXXX-XXXX")
            }

            var durationMs = 0L
            var expiresAt = 0L
            if (expiryMode == 1) {
                val d = days.trim().ifEmpty { "0" }.toLongOrNull()
                val h = hours.trim().ifEmpty { "0" }.toLongOrNull()
                val m = minutes.trim().ifEmpty { "0" }.toLongOrNull()
                if (d == null || h == null || m == null || d < 0 || h < 0 || m < 0) {
                    throw IllegalArgumentException("Durée invalide : saisis des nombres positifs")
                }
                durationMs = d * 86_400_000L + h * 3_600_000L + m * 60_000L
                if (durationMs <= 0) throw IllegalArgumentException("La durée doit être supérieure à zéro")
            } else if (expiryMode == 2) {
                if (endMs <= System.currentTimeMillis()) {
                    throw IllegalArgumentException("Choisis une date de fin dans le futur")
                }
                expiresAt = endMs
            }

            val down = downKb.trim().ifEmpty { "0" }.toLongOrNull()
            val up = upKb.trim().ifEmpty { "0" }.toLongOrNull()
            val conn = connKb.trim().ifEmpty { "0" }.toLongOrNull()
            if (down == null || up == null || conn == null || down < 0 || up < 0 || conn < 0) {
                throw IllegalArgumentException("Débit invalide : saisis des nombres positifs (0 = illimité)")
            }
            val burst = when (priority) {
                0 -> 0L
                2 -> 4L * 1024 * 1024
                else -> 1024L * 1024
            }

            val quotaBytes = Quota.parse(quotaValue, quotaUnit)
                ?: throw IllegalArgumentException("Quota invalide : saisis un nombre positif")
            val maxSyncs = maxSyncsText.trim().ifEmpty { "0" }.toIntOrNull()
            if (maxSyncs == null || maxSyncs < 0) {
                throw IllegalArgumentException("Nombre de synchronisations invalide : saisis un entier positif (0 = illimité)")
            }

            val idBytes = ByteArray(8)
            SecureRandom().nextBytes(idBytes)
            val config = DylanConfig(
                name = name.trim().ifBlank { parsed.name },
                link = line,
                locked = locked,
                deviceId = id.uppercase(),
                createdAt = System.currentTimeMillis(),
                cid = idBytes.joinToString("") { String.format("%02x", it) },
                durationMs = durationMs,
                expiresAt = expiresAt,
                downBps = down * 1024,
                upBps = up * 1024,
                connBps = conn * 1024,
                burst = burst,
                quotaBytes = quotaBytes,
                quotaScope = quotaScope,
                maxSyncs = maxSyncs,
                mobileDataOnly = mobileDataOnly,
                userMessage = userMessage.trim(),
                customServerMessage = serverMessage.trim()
            )
            lastConfig = config
            output = DylanFormat.encode(config)
            summary = buildString {
                append(if (id.isEmpty()) "Tous les appareils" else "Appareil " + id.uppercase())
                append(" · ").append(if (locked) "verrouillée" else "non verrouillée")
                append("\nValidité : ")
                append(
                    when (expiryMode) {
                        1 -> "durée de " + Format.remaining(durationMs) + " à partir de l'import"
                        2 -> "jusqu'au " + Format.dateTime(expiresAt)
                        else -> "illimitée"
                    }
                )
                append("\nDébit : ↓ ").append(Format.limit(config.downBps))
                append(" · ↑ ").append(Format.limit(config.upBps))
                append(" · par fichier ").append(Format.limit(config.connBps))
                append("\nQuota : ")
                append(if (quotaBytes > 0) Format.bytes(quotaBytes) + (if (quotaScope == "user") " par utilisateur" else " pour le fichier") else "illimité")
                append(" · synchronisations : ").append(if (maxSyncs > 0) maxSyncs.toString() else "illimitées")
            }
            error = ""
        } catch (e: IllegalArgumentException) {
            error = e.message ?: "Erreur"
            output = ""
            summary = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AdminCard {
            Text("Cet appareil", color = Pal.Sub)
            Text(Store.deviceId(), style = MaterialTheme.typography.titleMedium, color = Pal.Accent)
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(Store.deviceId()))
                Toast.makeText(context, "ID copié", Toast.LENGTH_SHORT).show()
            }) { Text("Copier l'ID") }
        }

        AdminCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Créer une config avec le formulaire", style = MaterialTheme.typography.titleMedium, color = Pal.Text, modifier = Modifier.weight(1f))
                TextButton(onClick = { formOpen = !formOpen }) { Text(if (formOpen) "Masquer" else "Ouvrir", color = Pal.Accent) }
            }
            if (formOpen) {
                ConfigFormUi(formFields) { formFields = it }
                Button(onClick = {
                    try {
                        source = ConfigForm.toLink(formFields)
                        name = formFields.name
                        error = ""
                        formOpen = false
                    } catch (e: IllegalArgumentException) {
                        error = e.message ?: "Formulaire incomplet"
                    }
                }) { Text("Utiliser cette config") }
            }
        }

        AdminCard {
            Text("1. Lien source", style = MaterialTheme.typography.titleMedium, color = Pal.Text)
            OutlinedTextField(
                value = source,
                onValueChange = {
                    source = it
                    error = ""
                },
                label = { Text("Lien (vless, vmess, trojan, ss)") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )
            Row {
                TextButton(onClick = {
                    val pasted = clipboard.getText()?.text
                    if (!pasted.isNullOrBlank()) {
                        source = pasted
                        error = ""
                    }
                }) { Text("Coller") }
                Box {
                    TextButton(onClick = { menuOpen = true }) { Text("Profil enregistré") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        for (p in profiles) {
                            DropdownMenuItem(
                                text = { Text(p.name) },
                                onClick = {
                                    source = p.link
                                    if (name.isBlank()) name = p.name
                                    menuOpen = false
                                }
                            )
                        }
                    }
                }
            }
            if (parsedInfo != null) {
                Text(parsedInfo.first, color = if (parsedInfo.second) Pal.Green else Pal.Red)
            }
        }

        AdminCard {
            Text("2. Validité", style = MaterialTheme.typography.titleMedium, color = Pal.Text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = expiryMode == 0, onClick = { expiryMode = 0 }, label = { Text("Illimitée") })
                FilterChip(selected = expiryMode == 1, onClick = { expiryMode = 1 }, label = { Text("Durée") })
                FilterChip(selected = expiryMode == 2, onClick = { expiryMode = 2 }, label = { Text("Date fixe") })
            }
            if (expiryMode == 1) {
                Text("La durée commence à l'import sur l'appareil de l'utilisateur.", color = Pal.Sub)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = days,
                        onValueChange = { days = it },
                        label = { Text("Jours") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = hours,
                        onValueChange = { hours = it },
                        label = { Text("Heures") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { minutes = it },
                        label = { Text("Minutes") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (expiryMode == 2) {
                Text(
                    if (endMs > 0) "Fin : " + Format.dateTime(endMs) else "Aucune date choisie",
                    color = if (endMs > 0) Pal.Accent else Pal.Sub
                )
                Row {
                    TextButton(onClick = pickDate) { Text("Choisir la date") }
                    TextButton(onClick = pickTime) { Text("Choisir l'heure") }
                }
            }
        }

        AdminCard {
            Text("3. Bande passante", style = MaterialTheme.typography.titleMedium, color = Pal.Text)
            Text("En Ko/s. Vide ou 0 = illimité.", color = Pal.Sub)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = downKb,
                    onValueChange = { downKb = it },
                    label = { Text("Descendant") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = upKb,
                    onValueChange = { upKb = it },
                    label = { Text("Montant") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = connKb,
                onValueChange = { connKb = it },
                label = { Text("Vitesse max par fichier (connexion)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text("Priorité", color = Pal.Text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = priority == 0, onClick = { priority = 0 }, label = { Text("Basse") })
                FilterChip(selected = priority == 1, onClick = { priority = 1 }, label = { Text("Normale") })
                FilterChip(selected = priority == 2, onClick = { priority = 2 }, label = { Text("Haute") })
            }
            Text(
                "La priorité règle la rafale à pleine vitesse au début de chaque connexion (0, 1 ou 4 Mo) " +
                    "avant d'appliquer la vitesse max par fichier. Les pages web restent rapides, les gros téléchargements sont freinés.",
                color = Pal.Sub
            )
        }

        AdminCard {
            Text("4. Quota et synchronisations", style = MaterialTheme.typography.titleMedium, color = Pal.Text)
            Text("Quota de données (vide ou 0 = illimité).", color = Pal.Sub)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = quotaValue,
                    onValueChange = { quotaValue = it },
                    label = { Text("Quota") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                FilterChip(selected = quotaUnit == "Mo", onClick = { quotaUnit = "Mo" }, label = { Text("Mo") })
                FilterChip(selected = quotaUnit == "Go", onClick = { quotaUnit = "Go" }, label = { Text("Go") })
            }
            Text("Le quota s'applique :", color = Pal.Text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = quotaScope == "user", onClick = { quotaScope = "user" }, label = { Text("Par utilisateur") })
                FilterChip(selected = quotaScope == "file", onClick = { quotaScope = "file" }, label = { Text("Pour le fichier") })
            }
            Text(
                if (quotaScope == "user") {
                    "Un seul compteur par compte : toutes les configs « par utilisateur » du même compte l'utilisent ensemble."
                } else {
                    "Un compteur propre à ce fichier : chaque config a son quota, indépendant des autres."
                },
                color = Pal.Sub
            )
            Text(
                "Le quota est compté sur l'appareil de l'utilisateur : il n'est pas partagé entre plusieurs téléphones.",
                color = Pal.Sub
            )
            OutlinedTextField(
                value = maxSyncsText,
                onValueChange = { maxSyncsText = it },
                label = { Text("Nombre max de synchronisations (0 = illimité)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Chaque synchronisation compte. Une fois le maximum atteint, la config n'est plus mise à jour sur l'appareil (elle reste utilisable).",
                color = Pal.Sub
            )
        }

        AdminCard {
            Text("5. Options", style = MaterialTheme.typography.titleMedium, color = Pal.Text)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom de la config (facultatif)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = device,
                onValueChange = {
                    device = it
                    error = ""
                },
                label = { Text("ID de l'appareil autorisé (vide = tous)") },
                placeholder = { Text("DYL-XXXX-XXXX-XXXX-XXXX") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Verrouiller la config", color = Pal.Text)
                    Text("L'utilisateur ne voit pas les détails du serveur.", color = Pal.Sub)
                }
                Switch(checked = locked, onCheckedChange = { locked = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Données mobiles uniquement", color = Pal.Text)
                    Text("Refuse la connexion sur Wi-Fi.", color = Pal.Sub)
                }
                Switch(checked = mobileDataOnly, onCheckedChange = { mobileDataOnly = it })
            }
            OutlinedTextField(value = userMessage, onValueChange = { userMessage = it },
                label = { Text("Message destiné à l’utilisateur (facultatif)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = serverMessage, onValueChange = { serverMessage = it },
                label = { Text("Message personnalisé du serveur (facultatif)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            if (error.isNotEmpty()) {
                Text(error, color = Pal.Red)
            }
            Button(onClick = onGenerate) { Text("Générer le lien dylan://") }
        }

        if (output.isNotEmpty()) {
            AdminCard {
                Text("Config générée", style = MaterialTheme.typography.titleMedium, color = Pal.Text)
                Text(summary, color = Pal.Sub)
                Text("Export protégé par clés publiques", style = MaterialTheme.typography.titleSmall, color = Pal.Text)
                Text("Une clé privée RSA non exportable est créée sur cet appareil. Seuls les destinataires autorisés peuvent ouvrir le fichier.", color = Pal.Sub)
                OutlinedTextField(value = recipientKeys, onValueChange = { recipientKeys = it },
                    label = { Text("Clés publiques destinataires (une par ligne)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = {
                    val key = SecureExport.publicKey()
                    clipboard.setText(AnnotatedString(key))
                    Toast.makeText(context, "Clé publique copiée · empreinte ${SecureExport.fingerprint()}", Toast.LENGTH_LONG).show()
                }) { Text("Copier ma clé publique") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = openToAll, onCheckedChange = { openToAll = it })
                    Column {
                        Text("Accessible à toute personne ayant l’application", color = Pal.Text)
                        Text("Ce choix n’est pas un partage privé.", color = Pal.Amber)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowSelf, onCheckedChange = { allowSelf = it })
                    Text("Autoriser cet appareil à ouvrir l’export", color = Pal.Text)
                }
                OutlinedTextField(value = exportPassphrase, onValueChange = { exportPassphrase = it },
                    label = { Text("Passphrase supplémentaire (facultative)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    try {
                        val cfg = lastConfig ?: throw IllegalStateException("Génère d’abord la configuration")
                        val keys = recipientKeys.lines().map { it.trim() }.filter { it.isNotBlank() }
                        output = SecureExport.encode(cfg, keys, allowSelf, exportPassphrase, openToAll)
                        summary += "\nExport chiffré dylanx:// · ouverture réservée aux clés ajoutées"
                        error = ""
                    } catch (e: Exception) { error = e.message ?: "Impossible de créer l’export" }
                }) { Text("Créer l’export chiffré dylanx://") }
                OutlinedTextField(
                    value = output,
                    onValueChange = {},
                    readOnly = true,
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(value = exportFileName, onValueChange = { exportFileName = it },
                    label = { Text("Nom du fichier exporté") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(onClick = {
                        val file = exportFileName.trim().ifBlank { "DYLAN-config.dylanx" }
                        exportFileLauncher.launch(if (file.contains('.')) file else "$file.dylanx")
                    }) { Text("Enregistrer le fichier") }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(output))
                        Toast.makeText(context, "Config copiée", Toast.LENGTH_SHORT).show()
                    }) { Text("Copier") }
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                        send.type = "text/plain"
                        send.putExtra(Intent.EXTRA_TEXT, output)
                        context.startActivity(Intent.createChooser(send, "Partager la config"))
                    }) { Text("Partager") }
                }
                Text("Envoyer dans le dépôt Git", color = Pal.Text)
                Text("Catégories", color = Pal.Sub)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (cat in listOf("basic", "pro", "admin")) {
                        FilterChip(
                            selected = cat in selectedCats,
                            onClick = { selectedCats = if (cat in selectedCats) selectedCats - cat else selectedCats + cat },
                            label = { Text(categoryLabel(cat)) }
                        )
                    }
                }
                Text("Utilisateurs précis", color = Pal.Sub)
                if (userChoices.isEmpty()) {
                    Text("Aucun utilisateur : crées-en dans l'onglet Utilisateurs.", color = Pal.Sub)
                }
                for (u in userChoices) {
                    val checked = u in selectedUsers
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedUsers = if (checked) selectedUsers - u else selectedUsers + u },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { selectedUsers = if (checked) selectedUsers - u else selectedUsers + u }
                        )
                        Text(u, color = Pal.Text)
                    }
                }
                Button(
                    enabled = !output.startsWith(DylanFormat.SECURE_PREFIX) && !publishing && (selectedCats.isNotEmpty() || selectedUsers.isNotEmpty()),
                    onClick = {
                        publishing = true
                        val line = output
                        val cats = selectedCats.toList()
                        val users = selectedUsers.toList()
                        scope.launch {
                            val message = try {
                                val parts = ArrayList<String>()
                                withContext(Dispatchers.IO) {
                                    for (c in cats) {
                                        val added = Repository.publishConfig(c, line)
                                        parts.add(categoryLabel(c) + if (added) "" else " (déjà présente)")
                                    }
                                    if (users.isNotEmpty()) {
                                        val n = Repository.publishToUsers(users, line)
                                        parts.add("$n utilisateur(s)")
                                    }
                                }
                                "Envoyé : " + parts.joinToString(", ")
                            } catch (e: Exception) {
                                e.message ?: "Erreur de publication"
                            }
                            publishing = false
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    }
                ) {
                    Text(
                        if (publishing) "Envoi..."
                        else "Envoyer (" + selectedCats.size + " catégorie(s), " + selectedUsers.size + " utilisateur(s))"
                    )
                }
            }
        }

    }
}

@Composable
private fun AdminCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Pal.Card)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

/** Résumé lisible d'un lien collé (texte, valide ?), ou null si rien n'est collé. */
private fun describeLink(text: String): Pair<String, Boolean>? {
    val line = text.trim()
    if (line.isEmpty()) return null
    if (DylanFormat.isDylan(line)) return Pair("Ce lien est déjà au format dylan://", false)
    return try {
        val l = LinkParser.parse(line)
        val p = l.params
        val details = ArrayList<String>()
        details.add(l.protocol.uppercase() + " · " + l.host + ":" + l.port)
        details.add("Transport : " + ((p["type"] ?: "tcp").ifBlank { "tcp" }))
        details.add("Sécurité : " + ((p["security"] ?: "aucune").ifBlank { "aucune" }))
        val sni = p["sni"] ?: p["peer"] ?: ""
        if (sni.isNotBlank()) details.add("SNI : $sni")
        val host = p["host"] ?: ""
        if (host.isNotBlank()) details.add("Host : $host")
        details.add("Nom : " + l.name)
        Pair("Lien lu avec succès\n" + details.joinToString("\n"), true)
    } catch (e: IllegalArgumentException) {
        Pair(e.message ?: "Lien invalide", false)
    }
}

@Composable
fun AdminScreen() {
    val session by Account.session.collectAsStateWithLifecycle()
    if (session?.category != "admin") {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Espace réservé à l'administrateur.", color = Pal.Sub)
        }
    } else {
        var tab by remember { mutableStateOf(0) }
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val labels = listOf("Générer", "Catégories", "Utilisateurs", "Dépôt Git")
                for (i in labels.indices) {
                    FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(labels[i]) })
                }
            }
            when (tab) {
                0 -> GenerateTab()
                1 -> CategoriesTab()
                2 -> UsersTab()
                else -> GitTab()
            }
        }
    }
}

/** Résumé d'une ligne dylan:// : (titre, détails). */
private fun describeConfig(line: String): Pair<String, String> {
    return try {
        val c = DylanFormat.decode(line)
        val parts = ArrayList<String>()
        try {
            val l = LinkParser.parse(c.link)
            parts.add(l.protocol.uppercase() + " · " + l.host + ":" + l.port)
        } catch (e: IllegalArgumentException) {
            parts.add("lien illisible")
        }
        parts.add(if (c.locked) "verrouillée" else "non verrouillée")
        parts.add(if (c.deviceId.isBlank()) "tous appareils" else "appareil " + c.deviceId)
        parts.add(
            when {
                c.expiresAt > 0 -> "jusqu'au " + Format.dateTime(c.expiresAt)
                c.durationMs > 0 -> "durée " + Format.remaining(c.durationMs)
                else -> "sans expiration"
            }
        )
        if (c.quotaBytes > 0) {
            parts.add("quota " + Format.bytes(c.quotaBytes) + (if (c.quotaScope == "user") " par utilisateur" else " pour le fichier"))
        }
        if (c.maxSyncs > 0) parts.add(c.maxSyncs.toString() + " synchro max")
        if (c.downBps > 0 || c.upBps > 0 || c.connBps > 0) {
            parts.add("↓ " + Format.limit(c.downBps) + " ↑ " + Format.limit(c.upBps) + " fichier " + Format.limit(c.connBps))
        }
        Pair(c.name.ifBlank { "Sans nom" }, parts.joinToString(" · "))
    } catch (e: IllegalArgumentException) {
        Pair("Config illisible", "")
    }
}

@Composable
private fun CategoriesTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf("basic") } // basic, pro, admin ou user
    var selectedUser by remember { mutableStateOf("") }
    var userChoices by remember { mutableStateOf<List<String>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(target) {
        if (target == "user" && userChoices.isEmpty()) {
            try {
                userChoices = withContext(Dispatchers.IO) { Repository.listUsers().map { it.login } }
            } catch (e: Exception) {
                error = e.message ?: "Erreur de lecture"
            }
        }
    }
    LaunchedEffect(target, selectedUser, refresh) {
        loading = true
        error = ""
        try {
            lines = withContext(Dispatchers.IO) {
                if (target == "user") {
                    if (selectedUser.isEmpty()) emptyList<String>() else Repository.readUserConfigs(selectedUser)
                } else {
                    Repository.readCategory(target)
                }
            }
        } catch (e: Exception) {
            lines = emptyList()
            error = e.message ?: "Erreur de lecture"
        }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (cat in listOf("basic", "pro", "admin")) {
                FilterChip(selected = target == cat, onClick = { target = cat }, label = { Text(categoryLabel(cat)) })
            }
            FilterChip(selected = target == "user", onClick = { target = "user" }, label = { Text("Par utilisateur") })
        }
        if (target == "user") {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (u in userChoices) {
                    FilterChip(selected = selectedUser == u, onClick = { selectedUser = u }, label = { Text(u) })
                }
            }
            if (selectedUser.isEmpty()) Text("Choisis un utilisateur.", color = Pal.Sub)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { refresh = refresh + 1 }) { Text("Actualiser") }
            if (loading) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        }
        if (error.isNotEmpty()) Text(error, color = Pal.Red)
        if (!loading && error.isEmpty() && lines.isEmpty() && (target != "user" || selectedUser.isNotEmpty())) {
            Text("Aucune config ici.", color = Pal.Sub)
        }
        for (index in lines.indices) {
            val info = describeConfig(lines[index])
            AdminCard {
                Text(info.first, style = MaterialTheme.typography.titleMedium, color = Pal.Text)
                Text(info.second, color = Pal.Sub)
                TextButton(onClick = { toDelete = index }) { Text("Supprimer") }
            }
        }
    }

    val index = toDelete
    if (index != null) {
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Supprimer cette config ?") },
            text = { Text("Elle disparaîtra des appareils concernés à leur prochaine synchronisation.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    val current = target
                    val user = selectedUser
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                if (current == "user") Repository.removeUserConfig(user, index) else Repository.removeConfig(current, index)
                            }
                            Toast.makeText(context, "Config supprimée", Toast.LENGTH_SHORT).show()
                            refresh = refresh + 1
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: "Erreur", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } }
        )
    }
}

@Composable
private fun UsersTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by Account.session.collectAsStateWithLifecycle()
    var users by remember { mutableStateOf<List<Keyring.UserLine>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("basic") }
    var busy by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Keyring.UserLine?>(null) }

    LaunchedEffect(refresh) {
        loading = true
        try {
            users = withContext(Dispatchers.IO) { Repository.listUsers() }
            error = ""
        } catch (e: Exception) {
            users = emptyList()
            error = e.message ?: "Erreur de lecture"
        }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AdminCard {
            Text("Créer un utilisateur", style = MaterialTheme.typography.titleMedium, color = Pal.Text)
            OutlinedTextField(
                value = login,
                onValueChange = { login = it },
                label = { Text("Identifiant") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Mot de passe (6 caractères minimum)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (cat in listOf("basic", "pro", "admin")) {
                    FilterChip(selected = category == cat, onClick = { category = cat }, label = { Text(categoryLabel(cat)) })
                }
            }
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { Repository.addUser(login, password, category) }
                            Toast.makeText(context, "Utilisateur créé", Toast.LENGTH_SHORT).show()
                            login = ""
                            password = ""
                            refresh = refresh + 1
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: "Erreur", Toast.LENGTH_LONG).show()
                        }
                        busy = false
                    }
                }
            ) { Text(if (busy) "Création..." else "Créer") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Utilisateurs (" + users.size + ")", style = MaterialTheme.typography.titleMedium, color = Pal.Text, modifier = Modifier.weight(1f))
            TextButton(onClick = { refresh = refresh + 1 }) { Text("Actualiser") }
            if (loading) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        }
        if (error.isNotEmpty()) Text(error, color = Pal.Red)
        for (u in users) {
            AdminCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(u.login, color = Pal.Text)
                        Text(categoryLabel(u.category), color = Pal.Sub)
                    }
                    TextButton(enabled = u.login != session?.login, onClick = { toDelete = u }) { Text("Supprimer") }
                }
            }
        }
        Text(
            "Un utilisateur se connecte avec ces identifiants : l'application les vérifie dans le dépôt Git. Seul le compte admin est intégré à l'application.",
            color = Pal.Sub
        )
    }

    val target = toDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Supprimer " + target.login + " ?") },
            text = { Text("Il sera déconnecté à sa prochaine synchronisation.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { Repository.removeUser(target.login) }
                            refresh = refresh + 1
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: "Erreur", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } }
        )
    }
}

@Composable
private fun GitTab() {
    val scope = rememberCoroutineScope()
    val status by Repository.status.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf(Repository.hasWriteToken()) }
    var info by remember { mutableStateOf("") }
    var showInit by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AdminCard {
            Text("Dépôt", style = MaterialTheme.typography.titleMedium, color = Pal.Text)
            Text(Repository.OWNER + "/" + Repository.REPO + " · branche " + Repository.BRANCH, color = Pal.Sub)
            Text(if (status.running) "Synchronisation en cours..." else status.message.ifEmpty { "Pas encore synchronisé" }, color = Pal.Sub)
            Row {
                TextButton(onClick = { Repository.syncAsync() }) { Text("Synchroniser") }
                TextButton(onClick = {
                    info = "Test en cours..."
                    scope.launch {
                        info = try {
                            "Accès OK : " + withContext(Dispatchers.IO) { Repository.checkAccess() }
                        } catch (e: Exception) {
                            e.message ?: "Erreur"
                        }
                    }
                }) { Text("Tester l'accès") }
            }
            if (info.isNotEmpty()) Text(info, color = if (info.startsWith("Accès OK") && !info.contains("PUBLIC")) Pal.Green else Pal.Amber)
        }

        AdminCard {
            Text("Jeton GitHub d'écriture", style = MaterialTheme.typography.titleMedium, color = Pal.Text)
            Text(
                if (hasToken) "Jeton enregistré (chiffré sur cet appareil)" else "Aucun jeton enregistré",
                color = if (hasToken) Pal.Green else Pal.Amber
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Colle le jeton ici") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Row {
                TextButton(onClick = {
                    Repository.setWriteToken(token)
                    token = ""
                    hasToken = Repository.hasWriteToken()
                }) { Text("Enregistrer") }
                TextButton(onClick = {
                    Repository.setWriteToken(null)
                    hasToken = false
                }) { Text("Effacer") }
            }
            Text(
                "Jeton « fine-grained » limité au dépôt Vpndylan, permission Contents : lecture et écriture. " +
                    "Il n'est jamais envoyé ailleurs que vers GitHub.",
                color = Pal.Sub
            )
        }

        AdminCard {
            Text("Initialisation", style = MaterialTheme.typography.titleMedium, color = Pal.Text)
            Text(
                "À faire une seule fois : crée la branche de données, le compte admin et les trois fichiers de configs. " +
                    "Refaire l'initialisation remplace les clés et efface les utilisateurs : ils devront être recréés.",
                color = Pal.Sub
            )
            Button(onClick = { showInit = true }) { Text("Initialiser le dépôt") }
        }
    }

    if (showInit) {
        InitDialog(
            onDismiss = { showInit = false },
            onDone = { message ->
                showInit = false
                info = message
            }
        )
    }
}

@Composable
private fun InitDialog(onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var admin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Initialiser le dépôt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Confirme le mot de passe du compte admin.", color = Pal.Sub)
                OutlinedTextField(
                    value = admin,
                    onValueChange = { admin = it },
                    label = { Text("Mot de passe admin") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error.isNotEmpty()) Text(error, color = Pal.Red)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = ""
                    scope.launch {
                        try {
                            val message = withContext(Dispatchers.IO) { Repository.initialize(admin) }
                            onDone(message)
                        } catch (e: Exception) {
                            error = e.message ?: "Erreur"
                        }
                        busy = false
                    }
                }
            ) { Text(if (busy) "Initialisation..." else "Initialiser") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
