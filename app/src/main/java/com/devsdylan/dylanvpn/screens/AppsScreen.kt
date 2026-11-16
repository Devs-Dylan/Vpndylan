package com.devsdylan.dylanvpn.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devsdylan.dylanvpn.data.Store
import com.devsdylan.dylanvpn.ui.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class AppEntry(val label: String, val pkg: String)

private fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        .filter { it.pkg != context.packageName }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

/** Tunnel par application : toutes, seulement certaines, ou toutes sauf certaines. */
@Composable
fun AppsScreen() {
    val context = LocalContext.current
    val settings by Store.settings.collectAsStateWithLifecycle()
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(context) }
    }

    val shown = apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.appsMode == "all",
                    onClick = { Store.updateSettings { it.copy(appsMode = "all") } },
                    label = { Text("Toutes") }
                )
                FilterChip(
                    selected = settings.appsMode == "only",
                    onClick = { Store.updateSettings { it.copy(appsMode = "only") } },
                    label = { Text("Seulement") }
                )
                FilterChip(
                    selected = settings.appsMode == "except",
                    onClick = { Store.updateSettings { it.copy(appsMode = "except") } },
                    label = { Text("Sauf") }
                )
            }
            Text(
                when (settings.appsMode) {
                    "only" -> "Seules les applications cochées passent par le VPN."
                    "except" -> "Toutes passent par le VPN sauf les applications cochées."
                    else -> "Toutes les applications passent par le VPN."
                },
                color = Pal.Sub
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Rechercher une application") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(shown, key = { it.pkg }) { app ->
                val checked = app.pkg in settings.apps
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            Store.updateSettings {
                                it.copy(apps = if (checked) it.apps - app.pkg else it.apps + app.pkg)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = {
                            Store.updateSettings { s ->
                                s.copy(apps = if (checked) s.apps - app.pkg else s.apps + app.pkg)
                            }
                        }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.label, color = Pal.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(app.pkg, color = Pal.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
