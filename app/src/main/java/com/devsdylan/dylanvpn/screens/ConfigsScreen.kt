package com.devsdylan.dylanvpn.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devsdylan.dylanvpn.core.ConfigBuilder
import com.devsdylan.dylanvpn.core.CoreRuntime
import com.devsdylan.dylanvpn.core.LinkParser
import com.devsdylan.dylanvpn.core.Subscription
import com.devsdylan.dylanvpn.data.Account
import com.devsdylan.dylanvpn.data.Profile
import com.devsdylan.dylanvpn.data.Repository
import com.devsdylan.dylanvpn.data.Store
import com.devsdylan.dylanvpn.data.Usage
import com.devsdylan.dylanvpn.ui.NpvCard
import com.devsdylan.dylanvpn.ui.Pal
import com.devsdylan.dylanvpn.ui.QrCode
import com.devsdylan.dylanvpn.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Importe du texte (un ou plusieurs liens) et affiche le résultat. */
fun importAndToast(context: android.content.Context, text: String, passphrase: String = "") {
    if (text.isBlank()) {
        Toast.makeText(context, "Rien à importer", Toast.LENGTH_SHORT).show()
        return
    }
    val result = Store.addProfiles(text, passphrase = passphrase)
    val message = if (result.added > 0) {
        result.added.toString() + " config(s) ajoutée(s)" + if (result.errors.isEmpty()) "" else " (" + result.errors.size + " ignorée(s))"
    } else {
        result.errors.firstOrNull() ?: "Aucun lien trouvé"
    }
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

@Composable
fun ConfigsScreen(onEdit: (String?) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val profiles by Store.profiles.collectAsStateWithLifecycle()
    val selectedId by Store.selectedId.collectAsStateWithLifecycle()
    val settings by Store.settings.collectAsStateWithLifecycle()
    val session by Account.session.collectAsStateWithLifecycle()
    val pings by CoreRuntime.pings.collectAsStateWithLifecycle()
    val pinging by CoreRuntime.pinging.collectAsStateWithLifecycle()
    val sync by Repository.status.collectAsStateWithLifecycle()
    val isAdmin = session?.category == "admin"

    var expanded by remember { mutableStateOf(true) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sortByPing by remember { mutableStateOf(false) }
    var selectMode by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(setOf<String>()) }
    var showAdd by remember { mutableStateOf(false) }
    var showSub by remember { mutableStateOf(false) }
    var pendingSecureImport by remember { mutableStateOf("") }
    var importPassphrase by remember { mutableStateOf("") }
    var showUnlock by remember { mutableStateOf(false) }
    var subUrl by remember { mutableStateOf("") }
    var shareTarget by remember { mutableStateOf<Profile?>(null) }
    var qrTarget by remember { mutableStateOf<Profile?>(null) }
    var deleteIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }
    fun importOrUnlock(text: String) {
        if (text.lineSequence().any { it.trim().startsWith("dylanx://", true) }) {
            pendingSecureImport = text
            importPassphrase = ""
            showUnlock = true
        } else importAndToast(context, text)
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = try {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (e: Exception) {
                ""
            }
            importOrUnlock(text)
        }
    }

    val selected = profiles.firstOrNull { it.id == selectedId }
    fun matches(p: Profile) = query.isBlank() || p.name.contains(query, true) || (!p.locked && p.host.contains(query, true))
    val others = profiles
        .filter { it.id != selectedId && matches(it) }
        .let { list ->
            if (sortByPing) list.sortedBy { pings[it.id]?.ms?.takeIf { ms -> ms >= 0 } ?: Long.MAX_VALUE } else list
        }
    val lastPing = pings.values.maxOfOrNull { it.at }

    fun requestDelete(ids: List<String>) {
        if (ids.isEmpty()) return
        if (settings.confirmDelete) {
            deleteIds = ids
        } else {
            for (id in ids) Store.deleteProfile(id)
            checked = emptySet()
            selectMode = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Ping · " + (if (lastPing == null) "jamais" else Format.dateTime(lastPing)),
                color = Pal.Sub,
                fontFamily = Pal.Mono,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (sync.running) "Synchronisation en cours..." else sync.message.ifEmpty { "Pas encore synchronisé" },
                    color = Pal.Sub,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                )
            }
            if (searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Rechercher une config") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (selectMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(checked.size.toString() + " sélectionnée(s)", color = Pal.Accent, fontFamily = Pal.Mono, modifier = Modifier.weight(1f))
                    IconButton(onClick = { requestDelete(checked.toList()) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Supprimer", tint = Pal.Red)
                    }
                    TextButton(onClick = {
                        selectMode = false
                        checked = emptySet()
                    }) { Text("Fermer", color = Pal.Sub) }
                }
            }

            if (selected != null && matches(selected)) {
                ConfigCard(
                    profile = selected,
                    active = true,
                    isAdmin = isAdmin,
                    ping = pings[selected.id],
                    pinging = selected.id in pinging,
                    selectMode = selectMode,
                    checked = selected.id in checked,
                    onClick = {
                        if (selectMode) checked = if (selected.id in checked) checked - selected.id else checked + selected.id
                    },
                    onEdit = { onEdit(selected.id) },
                    onShare = { shareTarget = selected },
                    onDelete = { requestDelete(listOf(selected.id)) }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Pal.CardHi, RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.UnfoldMore, contentDescription = null, tint = Pal.Sub, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Enregistrées (" + others.size + ")", color = Pal.Text, fontFamily = Pal.Mono, fontSize = 12.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { CoreRuntime.pingAll(context, profiles) }) {
                    Icon(Icons.Default.Speed, contentDescription = "Tester toutes les configs", tint = Pal.Sub)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Pal.Sub)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Synchroniser avec le dépôt") }, onClick = {
                            menuOpen = false
                            Repository.syncAsync()
                        })
                        DropdownMenuItem(text = { Text(if (sortByPing) "Ne plus trier par ping" else "Trier par ping") }, onClick = {
                            menuOpen = false
                            sortByPing = !sortByPing
                        })
                        DropdownMenuItem(text = { Text("Supprimer les configs importées") }, onClick = {
                            menuOpen = false
                            requestDelete(profiles.filter { !it.synced }.map { it.id })
                        })
                    }
                }
            }

            if (expanded) {
                for (p in others) {
                    ConfigCard(
                        profile = p,
                        active = false,
                        isAdmin = isAdmin,
                        ping = pings[p.id],
                        pinging = p.id in pinging,
                        selectMode = selectMode,
                        checked = p.id in checked,
                        onClick = {
                            if (selectMode) checked = if (p.id in checked) checked - p.id else checked + p.id
                            else Store.select(p.id)
                        },
                        onEdit = { onEdit(p.id) },
                        onShare = { shareTarget = p },
                        onDelete = { requestDelete(listOf(p.id)) }
                    )
                }
            }
            if (profiles.isEmpty()) {
                Text(
                    "Aucune config. Appuie sur + pour en ajouter, ou synchronise avec le dépôt.",
                    color = Pal.Sub,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        // barre d'outils flottante façon NPV Tunnel
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
                .background(Pal.CardHi, RoundedCornerShape(30.dp))
                .border(1.dp, Pal.Line, RoundedCornerShape(30.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolIcon(Icons.Default.UnfoldMore) { expanded = true }
            ToolIcon(Icons.Default.UnfoldLess) { expanded = false }
            ToolIcon(Icons.Default.Refresh) {
                Toast.makeText(context, "Synchronisation...", Toast.LENGTH_SHORT).show()
                Repository.syncAsync()
            }
            ToolIcon(Icons.Default.Search) {
                searching = !searching
                if (!searching) query = ""
            }
            ToolIcon(Icons.Default.DoneAll) {
                selectMode = !selectMode
                checked = emptySet()
            }
            ToolIcon(Icons.Default.Tune) {
                sortByPing = !sortByPing
                Toast.makeText(context, if (sortByPing) "Tri par ping" else "Ordre d'origine", Toast.LENGTH_SHORT).show()
            }
            Box(
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(46.dp)
                    .background(Pal.Accent, CircleShape)
                    .clickable { showAdd = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Add, contentDescription = "Ajouter", tint = Color(0xFF002111))
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Ajouter une config") },
            text = {
                Column {
                    AddRow(Icons.Default.RssFeed, "Ajouter un abonnement") {
                        showAdd = false
                        showSub = true
                    }
                    AddRow(Icons.Default.ContentPaste, "Importer depuis le presse-papiers") {
                        showAdd = false
                        importOrUnlock(clipboard.getText()?.text ?: "")
                    }
                    AddRow(Icons.Default.FileDownload, "Importer un fichier") {
                        showAdd = false
                        fileLauncher.launch(arrayOf("*/*"))
                    }
                    AddRow(Icons.Default.Add, "Ajouter manuellement") {
                        showAdd = false
                        onEdit(null)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAdd = false }) { Text("Annuler") } }
        )
    }

    if (showUnlock) {
        AlertDialog(
            onDismissRequest = { showUnlock = false },
            title = { Text("Ouvrir l’export chiffré") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Le fichier sera déverrouillé avec la clé privée de cet appareil, si elle est autorisée, ou avec la passphrase fournie.")
                    OutlinedTextField(value = importPassphrase, onValueChange = { importPassphrase = it },
                        label = { Text("Passphrase (laisser vide pour utiliser la clé de cet appareil)") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showUnlock = false
                    importAndToast(context, pendingSecureImport, importPassphrase)
                }) { Text("Déverrouiller et importer") }
            },
            dismissButton = { TextButton(onClick = { showUnlock = false }) { Text("Annuler") } }
        )
    }

    if (showSub) {
        AlertDialog(
            onDismissRequest = { showSub = false },
            title = { Text("Ajouter un abonnement") },
            text = {
                OutlinedTextField(
                    value = subUrl,
                    onValueChange = { subUrl = it },
                    label = { Text("Adresse de l'abonnement (https://...)") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = subUrl
                    showSub = false
                    scope.launch {
                        try {
                            val text = withContext(Dispatchers.IO) { Subscription.fetch(url) }
                            importAndToast(context, text)
                        } catch (e: IllegalArgumentException) {
                            Toast.makeText(context, e.message ?: "Abonnement illisible", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Importer") }
            },
            dismissButton = { TextButton(onClick = { showSub = false }) { Text("Annuler") } }
        )
    }

    val share = shareTarget
    if (share != null) {
        AlertDialog(
            onDismissRequest = { shareTarget = null },
            title = { Text("Partager la configuration") },
            text = {
                Column {
                    AddRow(Icons.Default.Share, "Exporter la config") {
                        shareTarget = null
                        val send = Intent(Intent.ACTION_SEND)
                        send.type = "text/plain"
                        send.putExtra(Intent.EXTRA_TEXT, share.link)
                        context.startActivity(Intent.createChooser(send, "Exporter la config"))
                    }
                    AddRow(Icons.Default.ContentPaste, "Copier le lien") {
                        shareTarget = null
                        clipboard.setText(AnnotatedString(share.link))
                        Toast.makeText(context, "Lien copié", Toast.LENGTH_SHORT).show()
                    }
                    AddRow(Icons.Default.Edit, "Copier le JSON Xray") {
                        shareTarget = null
                        val json = try {
                            ConfigBuilder.build(LinkParser.parse(share.link), null, settings.mtu, "warning")
                        } catch (e: IllegalArgumentException) {
                            e.message ?: "Erreur"
                        }
                        clipboard.setText(AnnotatedString(json))
                        Toast.makeText(context, "JSON copié", Toast.LENGTH_SHORT).show()
                    }
                    AddRow(Icons.Default.Search, "Afficher le QR code") {
                        shareTarget = null
                        qrTarget = share
                    }
                }
            },
            confirmButton = { TextButton(onClick = { shareTarget = null }) { Text("Annuler") } }
        )
    }

    val qr = qrTarget
    if (qr != null) {
        AlertDialog(
            onDismissRequest = { qrTarget = null },
            title = { Text(qr.name) },
            text = { Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(qr.link) } },
            confirmButton = { TextButton(onClick = { qrTarget = null }) { Text("Fermer") } }
        )
    }

    if (deleteIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { deleteIds = emptyList() },
            title = { Text("Supprimer ?") },
            text = { Text(deleteIds.size.toString() + " config(s) seront supprimées.") },
            confirmButton = {
                TextButton(onClick = {
                    for (id in deleteIds) Store.deleteProfile(id)
                    deleteIds = emptyList()
                    checked = emptySet()
                    selectMode = false
                }) { Text("Supprimer", color = Pal.Red) }
            },
            dismissButton = { TextButton(onClick = { deleteIds = emptyList() }) { Text("Annuler") } }
        )
    }
}

@Composable
private fun ToolIcon(icon: ImageVector, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(42.dp)) {
        Icon(icon, contentDescription = null, tint = Pal.Text, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun AddRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Pal.Sub, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, color = Pal.Text)
    }
}

@Composable
private fun ConfigCard(
    profile: Profile,
    active: Boolean,
    isAdmin: Boolean,
    ping: CoreRuntime.PingResult?,
    pinging: Boolean,
    selectMode: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    NpvCard(
        modifier = Modifier.clickable(onClick = onClick),
        border = if (active) Pal.Accent else Pal.Line,
        container = if (active) Pal.CardHi else Pal.Card
    ) {
        Row(modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectMode) {
                Checkbox(checked = checked, onCheckedChange = { onClick() })
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    profile.name,
                    color = Pal.Text,
                    fontFamily = Pal.Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (profile.locked) "Config verrouillée" else profile.host + ":" + profile.port,
                    color = Pal.Sub,
                    fontFamily = Pal.Mono,
                    fontSize = 10.sp,
                    maxLines = 1
                )
                if (profile.expiresAt > 0) {
                    val left = profile.expiresAt - Store.now()
                    Text(
                        if (left <= 0) "Expirée" else "Expire dans " + Format.remaining(left),
                        color = if (left <= 0) Pal.Red else Pal.Amber,
                        fontFamily = Pal.Mono,
                        fontSize = 10.sp
                    )
                }
                for (line in Usage.lines(profile)) {
                    Text(
                        line.text,
                        color = when (line.level) {
                            2 -> Pal.Red
                            1 -> Pal.Amber
                            else -> Pal.Sub
                        },
                        fontFamily = Pal.Mono,
                        fontSize = 10.sp
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Row {
                    if (!profile.locked) {
                        IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "Modifier", tint = Pal.Text, modifier = Modifier.size(18.dp))
                        }
                    }
                    if (isAdmin) {
                        IconButton(onClick = onShare, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Default.Share, contentDescription = "Partager", tint = Pal.Text, modifier = Modifier.size(18.dp))
                        }
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Supprimer", tint = Pal.Sub, modifier = Modifier.size(18.dp))
                    }
                }
                Row {
                    Text(profile.protocol, color = Pal.Sub, fontFamily = Pal.Mono, fontSize = 10.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("Ping ", color = Pal.Amber, fontFamily = Pal.Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            pinging -> "..."
                            ping == null -> "—"
                            ping.ms >= 0 -> ping.ms.toString() + "ms"
                            else -> "échec"
                        },
                        color = if (ping != null && ping.ms >= 0) Pal.Green else if (ping == null) Pal.Sub else Pal.Red,
                        fontFamily = Pal.Mono,
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}
