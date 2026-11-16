package com.devsdylan.dylanvpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devsdylan.dylanvpn.core.AuthClient
import com.devsdylan.dylanvpn.data.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Écran de connexion : obligatoire tant qu'aucun compte valide n'est enregistré. */
@Composable
fun LoginScreen() {
    val scope = rememberCoroutineScope()
    val notice by Account.notice.collectAsStateWithLifecycle()
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var registering by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Pal.Bg)
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Pal.Accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = Pal.Accent, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("DYLAN VPN", style = MaterialTheme.typography.headlineMedium, color = Pal.Text)
        Text(
            if (registering) "Crée ton compte pour utiliser le VPN." else "Connecte-toi pour charger tes configurations.",
            color = Pal.Sub
        )
        if (notice.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(notice, color = Pal.Amber)
        }
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = login,
            onValueChange = {
                login = it
                error = ""
            },
            label = { Text("Identifiant") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                error = ""
            },
            label = { Text("Mot de passe") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        if (registering) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = confirm,
                onValueChange = {
                    confirm = it
                    error = ""
                },
                label = { Text("Confirme le mot de passe") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = phone,
                onValueChange = {
                    phone = it
                    error = ""
                },
                label = { Text("Numéro de téléphone") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "En créant un compte, tu acceptes que ton nom, l'identifiant de cet appareil et une empreinte de ton numéro " +
                    "soient enregistrés. L'administrateur voit ton nom, ton appareil, ta dernière connexion et les 2 derniers " +
                    "chiffres de ton numéro, jamais ton mot de passe. Identifiant : 3 à 20 caractères (a-z, 0-9, _), " +
                    "mot de passe : 8 caractères minimum.",
                color = Pal.Sub
            )
        }
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(error, color = Pal.Red)
        }
        Spacer(Modifier.height(18.dp))
        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                error = ""
                scope.launch {
                    val problem = withContext(Dispatchers.IO) {
                        if (!registering) {
                            Account.login(login, password)
                        } else if (password != confirm) {
                            "Les deux mots de passe ne correspondent pas"
                        } else {
                            val created = AuthClient.register(login, password, phone)
                            if (!created.ok) {
                                created.message
                            } else {
                                // GitHub peut mettre un instant à publier la nouvelle ligne : on réessaie quelques fois
                                var result: String? = Account.login(login, password)
                                var attempts = 0
                                while (result != null && attempts < 4) {
                                    Thread.sleep(2_000)
                                    result = Account.login(login, password)
                                    attempts++
                                }
                                result
                            }
                        }
                    }
                    busy = false
                    if (problem != null) error = problem
                }
            }
        ) {
            if (busy) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                Text(if (registering) "Créer mon compte" else "Se connecter")
            }
        }
        if (AuthClient.configured) {
            TextButton(
                enabled = !busy,
                onClick = {
                    registering = !registering
                    error = ""
                }
            ) {
                Text(if (registering) "J'ai déjà un compte" else "Créer un compte", color = Pal.Accent)
            }
        }
    }
}
