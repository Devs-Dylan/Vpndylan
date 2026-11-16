package com.devsdylan.dylanvpn.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devsdylan.dylanvpn.BuildConfig
import com.devsdylan.dylanvpn.data.Account
import com.devsdylan.dylanvpn.data.Store
import com.devsdylan.dylanvpn.ui.NpvCard
import com.devsdylan.dylanvpn.ui.Pal
import com.devsdylan.dylanvpn.util.Format
import libv2ray.Libv2ray

/** Informations de version et identifiant de l'appareil. */
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val coreVersion = remember {
        try {
            Libv2ray.checkVersionX()
        } catch (t: Throwable) {
            "indisponible"
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NpvCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("INFORMATIONS DE VERSION", color = Pal.Sub, fontFamily = Pal.Mono, fontSize = 10.sp, letterSpacing = 1.sp)
                Text("Application " + BuildConfig.VERSION_NAME, color = Pal.Text, fontFamily = Pal.Mono)
                Text("Compilée le " + Format.dateTime(BuildConfig.BUILD_TIME), color = Pal.Sub, fontFamily = Pal.Mono, fontSize = 12.sp)
                Text("Xray-core $coreVersion", color = Pal.Text, fontFamily = Pal.Mono)
                Text("Par Devs-Dylan · 2026", color = Pal.Sub, fontSize = 12.sp)
            }
        }
        NpvCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("MON ID APPAREIL", color = Pal.Sub, fontFamily = Pal.Mono, fontSize = 10.sp, letterSpacing = 1.sp)
                Text(Store.deviceId(), color = Pal.Accent, fontFamily = Pal.Mono)
                Text("Donne cet identifiant à l'administrateur pour recevoir une config liée à cet appareil.", color = Pal.Sub, fontSize = 12.sp)
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(Store.deviceId()))
                    Toast.makeText(context, "ID copié", Toast.LENGTH_SHORT).show()
                }) { Text("Copier l'ID", color = Pal.Accent) }
            }
        }
        NpvCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("COMPTE", color = Pal.Sub, fontFamily = Pal.Mono, fontSize = 10.sp, letterSpacing = 1.sp)
                Text(
                    Account.session.value?.let { it.login + " (" + categoryLabel(it.category) + ")" } ?: "non connecté",
                    color = Pal.Text,
                    fontFamily = Pal.Mono
                )
            }
        }
    }
}
