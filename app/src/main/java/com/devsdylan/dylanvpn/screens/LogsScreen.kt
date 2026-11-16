package com.devsdylan.dylanvpn.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devsdylan.dylanvpn.data.AppLog
import com.devsdylan.dylanvpn.ui.Pal

/** Journaux façon NPV Tunnel : heure, puis « > message », le plus récent en haut. */
@Composable
fun LogsScreen() {
    val lines by AppLog.lines.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val newestFirst = lines.asReversed()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(AppLog.text()))
                Toast.makeText(context, "Journaux copiés", Toast.LENGTH_SHORT).show()
            }) { Text("Copier", color = Pal.Accent) }
        }
        if (newestFirst.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucun journal pour l'instant", color = Pal.Sub)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(newestFirst) { line ->
                    val time = line.substringBefore(' ')
                    val rest = line.substringAfter(' ')
                    val close = rest.indexOf(']')
                    val level = if (rest.startsWith("[") && close > 0) rest.substring(1, close) else "INFO"
                    val message = if (close > 0) rest.substring(close + 1).trim() else rest
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(time, color = Pal.Sub, fontFamily = Pal.Mono, fontSize = 11.sp, modifier = Modifier.width(76.dp))
                        Text(
                            "> $message",
                            color = when (level) {
                                "ERREUR" -> Pal.Red
                                "WARN" -> Pal.Amber
                                else -> Pal.Text
                            },
                            fontFamily = Pal.Mono,
                            fontSize = 11.sp
                        )
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Pal.Line))
                }
            }
        }
    }
}
