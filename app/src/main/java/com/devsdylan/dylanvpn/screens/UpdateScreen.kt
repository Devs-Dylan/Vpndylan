package com.devsdylan.dylanvpn.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devsdylan.dylanvpn.BuildConfig
import com.devsdylan.dylanvpn.data.UpdateStatus
import com.devsdylan.dylanvpn.data.Updater
import com.devsdylan.dylanvpn.ui.NpvCard
import com.devsdylan.dylanvpn.ui.Pal
import com.devsdylan.dylanvpn.util.Format

@Composable
fun UpdateScreen() {
    val context = LocalContext.current
    val state by Updater.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NpvCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("VERSION INSTALLÉE", color = Pal.Sub, fontFamily = Pal.Mono, fontSize = 10.sp, letterSpacing = 1.sp)
                Text("DYLAN VPN " + BuildConfig.VERSION_NAME, color = Pal.Text, fontFamily = Pal.Mono)
                Text("Compilée le " + Format.dateTime(BuildConfig.BUILD_TIME), color = Pal.Sub, fontSize = 12.sp)
            }
        }
        NpvCard(border = if (state.status == UpdateStatus.AVAILABLE || state.status == UpdateStatus.READY) Pal.Amber else Pal.Line) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("MISE À JOUR", color = Pal.Sub, fontFamily = Pal.Mono, fontSize = 10.sp, letterSpacing = 1.sp)
                Text(
                    state.message.ifEmpty { "Appuie sur « Vérifier » pour chercher une nouvelle version." },
                    color = when (state.status) {
                        UpdateStatus.ERROR -> Pal.Red
                        UpdateStatus.AVAILABLE, UpdateStatus.READY -> Pal.Amber
                        UpdateStatus.UP_TO_DATE -> Pal.Green
                        else -> Pal.Text
                    }
                )
                if (state.status == UpdateStatus.DOWNLOADING) {
                    Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(Pal.Line)) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(state.progress.coerceIn(0f, 1f))
                                .background(Pal.Accent)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                when (state.status) {
                    UpdateStatus.AVAILABLE -> Button(onClick = { Thread { Updater.download(context) }.start() }) { Text("Télécharger la mise à jour") }
                    UpdateStatus.READY -> Button(onClick = {
                        val problem = Updater.install(context)
                        if (problem != null) Toast.makeText(context, problem, Toast.LENGTH_LONG).show()
                    }) { Text("Installer") }
                    UpdateStatus.CHECKING, UpdateStatus.DOWNLOADING -> Text("Patiente...", color = Pal.Sub)
                    else -> {}
                }
                TextButton(
                    enabled = state.status != UpdateStatus.CHECKING && state.status != UpdateStatus.DOWNLOADING,
                    onClick = { Thread { Updater.check() }.start() }
                ) { Text("Vérifier", color = Pal.Accent) }
            }
        }
        Text(
            "La mise à jour est téléchargée depuis ton dépôt GitHub (dernière publication). " +
                "Android demandera de confirmer l'installation ; tes comptes et configs sont conservés.",
            color = Pal.Sub,
            fontSize = 12.sp
        )
    }
}
