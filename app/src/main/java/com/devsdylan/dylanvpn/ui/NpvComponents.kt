package com.devsdylan.dylanvpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** En-tête centré façon NPV Tunnel : titre espacé en vert, sous-titre, flèche de retour et action à droite. */
@Composable
fun NpvHeader(
    subtitle: String,
    onBack: (() -> Unit)?,
    trailing: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Pal.Bg)
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Retour", tint = Pal.Text)
            }
        }
        Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "DYLAN VPN",
                color = Pal.Accent,
                fontFamily = Pal.Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                letterSpacing = 4.sp
            )
            Text(subtitle, color = Pal.Sub, fontFamily = Pal.Mono, fontSize = 10.sp)
        }
        Box(modifier = Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

private class NavTab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    NavTab("home", "ACCUEIL", Icons.Outlined.Home),
    NavTab("configs", "CONFIGS", Icons.Outlined.Cloud),
    NavTab("logs", "JOURNAUX", Icons.Outlined.Info),
    NavTab("more", "PLUS", Icons.Default.MoreHoriz)
)

/** Barre du bas façon NPV Tunnel : quatre onglets, l'onglet actif est entouré d'une pastille. */
@Composable
fun NpvBottomBar(current: String?, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Pal.Bar)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        for (tab in TABS) {
            val selected = current == tab.route
            Column(
                modifier = Modifier.clickable { onSelect(tab.route) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .background(if (selected) Pal.Teal else Color.Transparent, RoundedCornerShape(50))
                        .padding(horizontal = 18.dp, vertical = 5.dp)
                ) {
                    Icon(tab.icon, contentDescription = tab.label, tint = if (selected) Pal.Text else Pal.Sub, modifier = Modifier.size(20.dp))
                }
                Text(
                    tab.label,
                    color = if (selected) Pal.Accent else Pal.Sub,
                    fontFamily = Pal.Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }
        }
    }
}

/** Carte sombre à fine bordure, comme dans NPV Tunnel. */
@Composable
fun NpvCard(
    modifier: Modifier = Modifier,
    border: Color = Pal.Line,
    container: Color = Pal.Card,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, border)
    ) {
        content()
    }
}

/** Petit titre de section en capitales espacées (« APPLICATION », « À PROPOS »...). */
@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        color = Pal.Sub,
        fontFamily = Pal.Mono,
        fontSize = 11.sp,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp)
    )
}

/** QR code dessiné avec ZXing. */
@Composable
fun QrCode(text: String, size: androidx.compose.ui.unit.Dp = 220.dp) {
    // largeur 0 : ZXing renvoie la matrice minimale, redessinée ensuite à la taille voulue
    val grid = try {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
    } catch (e: Exception) {
        null
    }
    Box(modifier = Modifier.size(size).background(Color.White)) {
        if (grid != null) {
            Canvas(modifier = Modifier.size(size)) {
                val n = grid.width
                val cell = this.size.width / n
                for (x in 0 until n) {
                    for (y in 0 until grid.height) {
                        if (grid.get(x, y)) {
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(x * cell, y * cell),
                                size = Size(cell + 0.5f, cell + 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}
