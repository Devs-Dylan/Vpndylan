package com.devsdylan.dylanvpn.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devsdylan.dylanvpn.core.AdminChatUser
import com.devsdylan.dylanvpn.core.ChatMessage
import com.devsdylan.dylanvpn.core.ChatRepository
import com.devsdylan.dylanvpn.data.Account
import com.devsdylan.dylanvpn.ui.Pal
import com.devsdylan.dylanvpn.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Chat : salon commun et conversations privées. L'admin sans compte de chat voit seulement les infos des comptes. */
@Composable
fun ChatScreen() {
    val hasChat = remember { ChatRepository.hasChat() }
    when {
        hasChat -> UserChat()
        Account.isAdmin -> AdminChatPanel()
        else -> Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Chat indisponible", style = MaterialTheme.typography.titleMedium, color = Pal.Text)
            Spacer(Modifier.height(8.dp))
            Text(
                "Le chat est réservé aux comptes créés depuis l'application (bouton « Créer un compte »). " +
                    "Déconnecte-toi puis reconnecte-toi si tu viens de créer le tien.",
                color = Pal.Sub
            )
        }
    }
}

@Composable
private fun UserChat() {
    var tab by remember { mutableStateOf(0) }
    var peer by remember { mutableStateOf<String?>(null) }
    val general by ChatRepository.general.collectAsStateWithLifecycle()
    val direct by ChatRepository.direct.collectAsStateWithLifecycle()
    val users by ChatRepository.users.collectAsStateWithLifecycle()
    val status by ChatRepository.status.collectAsStateWithLifecycle()
    val me = ChatRepository.me()

    // interrogation du serveur tant que l'écran est ouvert
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) { ChatRepository.poll() }
            delay(3_000)
        }
    }
    LaunchedEffect(tab) {
        if (tab == 1) withContext(Dispatchers.IO) { ChatRepository.refreshUsers() }
    }

    Column(Modifier.fillMaxSize().background(Pal.Bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            TextButton(onClick = { tab = 0 }) { Text("Salon", color = if (tab == 0) Pal.Accent else Pal.Sub) }
            TextButton(onClick = {
                tab = 1
                peer = null
            }) { Text("Privé", color = if (tab == 1) Pal.Accent else Pal.Sub) }
        }
        if (status.isNotEmpty()) Text(status, color = Pal.Amber, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

        if (tab == 0) {
            MessagesPane(general, me, true) { ChatRepository.send("general", it) }
        } else {
            val current = peer
            if (current == null) {
                val talkedTo = direct.map { if (it.from == me) it.to ?: "" else it.from }.filter { it.isNotEmpty() }
                val names = (talkedTo + users).distinct().sorted()
                PeerList(names) { peer = it }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { peer = null }) { Text("‹ Retour", color = Pal.Accent) }
                    Text(current, color = Pal.Text, style = MaterialTheme.typography.titleMedium)
                }
                val conversation = direct.filter { (it.from == me && it.to == current) || (it.from == current && it.to == me) }
                MessagesPane(conversation, me, false) { ChatRepository.send(current, it) }
            }
        }
    }
}

@Composable
private fun PeerList(names: List<String>, onPick: (String) -> Unit) {
    if (names.isEmpty()) {
        Text(
            "Personne d'autre n'a encore ouvert le chat. Les conversations privées apparaîtront ici.",
            color = Pal.Sub,
            modifier = Modifier.padding(16.dp)
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(names, key = { it }) { name ->
            TextButton(onClick = { onPick(name) }, modifier = Modifier.fillMaxWidth()) {
                Text(name, color = Pal.Text, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun MessagesPane(messages: List<ChatMessage>, me: String, showSender: Boolean, onSend: (String) -> String?) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { message -> Bubble(message, message.from == me, showSender) }
        }
        if (error.isNotEmpty()) Text(error, color = Pal.Red, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    error = ""
                },
                label = { Text("Message") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = !busy && input.isNotBlank(),
                onClick = {
                    val text = input
                    busy = true
                    scope.launch {
                        val problem = withContext(Dispatchers.IO) {
                            val result = onSend(text)
                            if (result == null) ChatRepository.poll()
                            result
                        }
                        busy = false
                        if (problem == null) input = "" else error = problem
                    }
                }
            ) { Text("Envoyer") }
        }
    }
}

@Composable
private fun Bubble(message: ChatMessage, mine: Boolean, showSender: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Column(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (mine) Pal.CardHi else Pal.Card)
                .padding(10.dp)
        ) {
            if (showSender && !mine) Text(message.from, color = Pal.Accent, style = MaterialTheme.typography.labelMedium)
            Text(message.text, color = Pal.Text)
            Text(Format.dateTime(message.ts), color = Pal.Sub, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Panneau admin : liste des comptes du chat et leurs informations (jamais le contenu des messages). */
@Composable
private fun AdminChatPanel() {
    val scope = rememberCoroutineScope()
    var hasKey by remember { mutableStateOf(ChatRepository.adminKey() != null) }
    var keyInput by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<AdminChatUser>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(hasKey) {
        if (hasKey) {
            message = "Chargement..."
            val loaded = withContext(Dispatchers.IO) { ChatRepository.adminUsers() }
            if (loaded == null) {
                message = "Impossible de lire la liste : vérifie la clé admin et l'adresse du serveur"
            } else {
                users = loaded
                message = if (loaded.isEmpty()) "Aucun compte n'a encore ouvert le chat" else ""
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Pal.Bg).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Comptes du chat", style = MaterialTheme.typography.titleMedium, color = Pal.Text)
        Text(
            "Tu vois les informations des comptes, pas le contenu des messages : les conversations privées sont chiffrées entre les appareils.",
            color = Pal.Sub
        )
        if (!hasKey) {
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text("Clé admin du serveur (ADMIN_KEY)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = {
                ChatRepository.setAdminKey(keyInput)
                keyInput = ""
                hasKey = ChatRepository.adminKey() != null
            }) { Text("Enregistrer") }
        } else {
            if (message.isNotEmpty()) Text(message, color = Pal.Amber)
            for (user in users) {
                TextButton(
                    onClick = {
                        info = "Chargement..."
                        scope.launch { info = withContext(Dispatchers.IO) { ChatRepository.adminUserInfo(user.name) } }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        (if (user.online) "● " else "○ ") + user.name,
                        color = if (user.online) Pal.Green else Pal.Text,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            TextButton(onClick = {
                ChatRepository.setAdminKey(null)
                hasKey = false
                users = emptyList()
            }) { Text("Effacer la clé admin", color = Pal.Sub) }
        }
    }

    info?.let { text ->
        AlertDialog(
            onDismissRequest = { info = null },
            confirmButton = { TextButton(onClick = { info = null }) { Text("Fermer") } },
            title = { Text("Informations du compte") },
            text = { Text(text) }
        )
    }
}
