package com.devsdylan.dylanvpn.screens

import android.app.Activity
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devsdylan.dylanvpn.core.ConfigBuilder
import com.devsdylan.dylanvpn.core.CoreRuntime
import com.devsdylan.dylanvpn.data.Account
import com.devsdylan.dylanvpn.data.Store
import com.devsdylan.dylanvpn.data.UpdateStatus
import com.devsdylan.dylanvpn.data.Updater
import com.devsdylan.dylanvpn.data.Usage
import com.devsdylan.dylanvpn.ui.NpvCard
import com.devsdylan.dylanvpn.ui.Pal
import com.devsdylan.dylanvpn.util.Format
import com.devsdylan.dylanvpn.vpn.DylanVpnService
import com.devsdylan.dylanvpn.vpn.VpnState
import com.devsdylan.dylanvpn.vpn.VpnStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(onOpenConfigs: () -> Unit, onOpenMore: () -> Unit, onOpenUpdate: () -> Unit) {
    val context = LocalContext.current
    val vpn by VpnState.state.collectAsStateWithLifecycle()
    val profiles by Store.profiles.collectAsStateWithLifecycle()
    val selectedId by Store.selectedId.collectAsStateWithLifecycle()
    val settings by Store.settings.collectAsStateWithLifecycle()
    val session by Account.session.collectAsStateWithLifecycle()
    val update by Updater.state.collectAsStateWithLifecycle()
    val pings by CoreRuntime.pings.collectAsStateWithLifecycle()
    val pinging by CoreRuntime.pinging.collectAsStateWithLifecycle()
    val selected = profiles.firstOrNull { it.id == selectedId }
    val status = vpn.status
    val active = status == VpnStatus.CONNECTING || status == VpnStatus.CHECKING ||
        status == VpnStatus.CONNECTED || status == VpnStatus.WAITING

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status) {
        while (status == VpnStatus.CONNECTED) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) DylanVpnService.start(context)
    }

    val onToggle: () -> Unit = {
        if (active) {
            DylanVpnService.stop(context)
        } else if (selected == null) {
            Toast.makeText(context, "Ajoute d'abord une configuration (onglet Configs)", Toast.LENGTH_LONG).show()
        } else if (Store.isExpired(selected)) {
            Toast.makeText(context, "Cette config a expiré", Toast.LENGTH_LONG).show()
        } else {
            val intent = VpnService.prepare(context)
            if (intent != null) permissionLauncher.launch(intent) else DylanVpnService.start(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(18.dp))
        PowerButton(status, onToggle)
        Spacer(Modifier.height(16.dp))

        StatusCard(status, vpn.message, vpn.warning, now - vpn.connectedAt)
        if (status == VpnStatus.CONNECTED && settings.shareEnabled) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Partage actif · proxy HTTP " + ConfigBuilder.SHARE_HTTP_PORT,
                color = Pal.Accent,
                fontFamily = Pal.Mono,
                fontSize = 11.sp
            )
        }
        Spacer(Modifier.height(12.dp))

        if (update.status == UpdateStatus.AVAILABLE || update.status == UpdateStatus.READY) {
            InfoRow(Icons.Default.SystemUpdate, "Mise à jour disponible", "Installer", Pal.Amber, onOpenUpdate)
        } else {
            val label = session?.let { it.login + " · " + categoryLabel(it.category) } ?: "—"
            InfoRow(Icons.Default.Person, "Compte", label, Pal.Accent, onOpenMore)
        }
        Spacer(Modifier.height(12.dp))

        NpvCard {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "CONFIGURATION ACTIVE",
                    color = Pal.Text,
                    fontFamily = Pal.Mono,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(10.dp))
                if (selected == null) {
                    Text("Aucune configuration", color = Pal.Sub, fontFamily = Pal.Mono)
                    TextButton(onClick = onOpenConfigs) { Text("Ouvrir les configs", color = Pal.Accent, fontFamily = Pal.Mono) }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenConfigs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null, tint = Pal.Accent, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                selected.name,
                                color = Pal.Text,
                                fontFamily = Pal.Mono,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                if (selected.locked) "Config verrouillée" else selected.host + ":" + selected.port,
                                color = Pal.Sub,
                                fontFamily = Pal.Mono,
                                fontSize = 11.sp
                            )
                        }
                        Text(selected.protocol, color = Pal.Sub, fontFamily = Pal.Mono, fontSize = 12.sp)
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Pal.Accent)
                    }
                    if (selected.expiresAt > 0) {
                        val left = selected.expiresAt - Store.now()
                        Text(
                            if (left <= 0) "Config expirée" else "Expire dans " + Format.remaining(left),
                            color = if (left <= 0) Pal.Red else Pal.Amber,
                            fontFamily = Pal.Mono,
                            fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.Start)
                        )
                    }
                    for (line in Usage.lines(selected)) {
                        Text(
                            line.text,
                            color = when (line.level) {
                                2 -> Pal.Red
                                1 -> Pal.Amber
                                else -> Pal.Sub
                            },
                            fontFamily = Pal.Mono,
                            fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.Start)
                        )
                    }
                    val ping = pings[selected.id]
                    Row(modifier = Modifier.align(Alignment.Start), verticalAlignment = Alignment.CenterVertically) {
                        Text("Ping ", color = Pal.Amber, fontFamily = Pal.Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                selected.id in pinging -> "en cours..."
                                ping == null -> "jamais testé"
                                ping.ms >= 0 -> ping.ms.toString() + " ms"
                                else -> "échec"
                            },
                            color = if (ping != null && ping.ms >= 0) Pal.Green else if (ping == null) Pal.Sub else Pal.Red,
                            fontFamily = Pal.Mono,
                            fontSize = 11.sp
                        )
                    }
                    if (ping != null) {
                        Text(
                            "Testé " + SimpleDateFormat("dd-MM HH:mm", Locale.FRANCE).format(Date(ping.at)),
                            color = Pal.Sub,
                            fontFamily = Pal.Mono,
                            fontSize = 9.sp,
                            modifier = Modifier.align(Alignment.Start)
                        )
                    }
                    TextButton(onClick = { CoreRuntime.ping(context, selected) }, enabled = selected.id !in pinging) {
                        Text("Ping", color = if (selected.id in pinging) Pal.Sub else Pal.Accent, fontFamily = Pal.Mono, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Pal.Line))
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    TrafficStat("DOWNLINK", Icons.Default.ArrowDownward, Pal.Green, vpn.downTotal, vpn.downSpeed, settings.speedUnit, status == VpnStatus.CONNECTED)
                    TrafficStat("UPLINK", Icons.Default.ArrowUpward, Pal.Amber, vpn.upTotal, vpn.upSpeed, settings.speedUnit, status == VpnStatus.CONNECTED)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PowerButton(status: VpnStatus, onClick: () -> Unit) {
    val busy = status == VpnStatus.CONNECTING || status == VpnStatus.CHECKING
    val ring = when (status) {
        VpnStatus.IDLE -> Pal.Line
        VpnStatus.CONNECTING, VpnStatus.CHECKING, VpnStatus.WAITING -> Pal.Amber
        VpnStatus.CONNECTED -> Pal.Green
        VpnStatus.ERROR -> Pal.Red
    }
    val iconColor = when (status) {
        VpnStatus.IDLE -> Pal.Sub
        VpnStatus.CONNECTING, VpnStatus.CHECKING, VpnStatus.WAITING -> Pal.Amber
        VpnStatus.CONNECTED -> Pal.Green
        VpnStatus.ERROR -> Pal.Red
    }
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1400, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "angle"
    )
    Box(
        modifier = Modifier
            .size(150.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(150.dp)) {
            val stroke = 3.dp.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val box = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = if (busy) ring.copy(alpha = 0.25f) else ring,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = box,
                style = Stroke(width = stroke)
            )
            if (busy) {
                drawArc(
                    color = ring,
                    startAngle = angle,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = box,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Icon(Icons.Default.PowerSettingsNew, contentDescription = "Connexion", tint = iconColor, modifier = Modifier.size(54.dp))
    }
}

@Composable
private fun StatusCard(status: VpnStatus, message: String, warning: String, elapsedMs: Long) {
    val icon: ImageVector
    val title: String
    val text: String
    val color: Color
    val back: Color
    when (status) {
        VpnStatus.IDLE -> {
            icon = Icons.Outlined.CloudOff
            title = "NON CONNECTÉ"
            text = "Choisis une configuration, puis appuie sur le bouton pour te connecter."
            color = Pal.Sub
            back = Pal.Card
        }
        VpnStatus.CONNECTING -> {
            icon = Icons.Default.Sync
            title = "CONNEXION EN COURS"
            text = "Ouverture du tunnel..."
            color = Pal.Amber
            back = Pal.AmberBg
        }
        VpnStatus.CHECKING -> {
            icon = Icons.Default.Sync
            title = "VÉRIFICATION DU TRAFIC"
            text = "Le tunnel est ouvert. Vérification que le trafic peut passer."
            color = Pal.Amber
            back = Pal.AmberBg
        }
        VpnStatus.CONNECTED -> {
            icon = Icons.Default.CheckCircle
            title = "CONNECTÉ"
            val secs = (elapsedMs / 1000).coerceAtLeast(0)
            text = if (warning.isNotEmpty()) warning else
                "Le trafic passe par le tunnel · " + String.format(Locale.US, "%02d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60)
            color = if (warning.isNotEmpty()) Pal.Amber else Pal.Green
            back = if (warning.isNotEmpty()) Pal.AmberBg else Pal.CardHi
        }
        VpnStatus.WAITING -> {
            icon = Icons.Default.WifiOff
            title = "EN ATTENTE DU RÉSEAU"
            text = "Le tunnel se rétablira automatiquement dès qu'une connexion Internet sera disponible."
            color = Pal.Amber
            back = Pal.AmberBg
        }
        VpnStatus.ERROR -> {
            icon = Icons.Default.Error
            title = "ÉCHEC DE LA CONNEXION"
            text = message.ifEmpty { "Appuie sur le bouton pour réessayer." }
            color = Pal.Red
            back = Pal.RedBg
        }
    }
    NpvCard(border = if (status == VpnStatus.IDLE) Pal.Line else color.copy(alpha = 0.6f), container = back) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = color, fontFamily = Pal.Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                Text(text, color = Pal.Sub, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, value: String, tint: Color, onClick: () -> Unit) {
    NpvCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, color = Pal.Text, modifier = Modifier.weight(1f), fontSize = 13.sp)
            Text(value, color = tint, fontFamily = Pal.Mono, fontSize = 11.sp)
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Pal.Sub)
        }
    }
}

@Composable
private fun TrafficStat(label: String, icon: ImageVector, tint: Color, total: Long, speed: Long, unit: String, live: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Pal.Text, fontFamily = Pal.Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (live) tint else Pal.Sub, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                if (live) Format.bytes(total) else "—",
                color = if (live) tint else Pal.Sub,
                fontFamily = Pal.Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }
        if (live) {
            Text(Format.speed(speed, unit), color = Pal.Sub, fontFamily = Pal.Mono, fontSize = 10.sp, textAlign = TextAlign.Center)
        }
    }
}
