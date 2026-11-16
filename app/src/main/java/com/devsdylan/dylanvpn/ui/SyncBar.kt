package com.devsdylan.dylanvpn.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devsdylan.dylanvpn.data.Repository

/** Barre d'état de la synchronisation avec le dépôt Git. */
@Composable
fun SyncBar() {
    val status by Repository.status.collectAsStateWithLifecycle()
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (status.running) "Synchronisation en cours..." else status.message.ifEmpty { "Pas encore synchronisé" },
                color = Pal.Sub
            )
        }
        TextButton(onClick = { Repository.syncAsync() }, enabled = !status.running) { Text("Synchroniser") }
    }
}
