package com.devsdylan.dylanvpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.devsdylan.dylanvpn.BuildConfig
import com.devsdylan.dylanvpn.data.Account
import com.devsdylan.dylanvpn.data.AppLog
import com.devsdylan.dylanvpn.screens.AboutScreen
import com.devsdylan.dylanvpn.screens.AdminScreen
import com.devsdylan.dylanvpn.screens.AppsScreen
import com.devsdylan.dylanvpn.screens.ConfigEditorScreen
import com.devsdylan.dylanvpn.screens.ConfigsScreen
import com.devsdylan.dylanvpn.screens.EditorHolder
import com.devsdylan.dylanvpn.screens.GuideScreen
import com.devsdylan.dylanvpn.screens.HomeScreen
import com.devsdylan.dylanvpn.screens.LogsScreen
import com.devsdylan.dylanvpn.screens.MoreScreen
import com.devsdylan.dylanvpn.screens.SettingsScreen
import com.devsdylan.dylanvpn.screens.ShareScreen
import com.devsdylan.dylanvpn.screens.UpdateScreen
import com.devsdylan.dylanvpn.util.Format

@Composable
fun MainNavigation() {
    val session by Account.session.collectAsStateWithLifecycle()
    if (session == null) {
        LoginScreen()
    } else {
        MainContent()
    }
}

@Composable
private fun MainContent() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val route = navBackStackEntry?.destination?.route

    val go: (String) -> Unit = { target -> navController.navigate(target) { launchSingleTop = true } }
    val mainTabs = listOf("home", "configs", "logs", "more")
    val isMain = route == null || route in mainTabs

    val subtitle = when (route) {
        "settings" -> "Réglages"
        "apps" -> "Proxy par application"
        "share" -> "Partage de connexion"
        "about" -> "Informations de version"
        "guide" -> "Guide"
        "admin" -> "Administration"
        "update" -> "Mise à jour"
        "editor" -> "Éditeur de config"
        else -> "v" + BuildConfig.VERSION_NAME + " · " + Format.dateTime(BuildConfig.BUILD_TIME)
    }
    // l'onglet mis en avant dans la barre du bas pour les écrans secondaires
    val highlighted = when (route) {
        "editor" -> "configs"
        "settings", "apps", "share", "about", "guide", "admin", "update" -> "more"
        else -> route
    }

    Scaffold(
        containerColor = Pal.Bg,
        topBar = {
            NpvHeader(
                subtitle = subtitle,
                onBack = if (isMain) null else ({ navController.popBackStack() }),
                trailing = {
                    when (route) {
                        "home" -> {
                            IconButton(onClick = { go("more") }) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(Pal.CardHi, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Person, contentDescription = "Compte", tint = Pal.Amber, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                        "logs" -> {
                            IconButton(onClick = { AppLog.clear() }) {
                                Icon(Icons.Default.Delete, contentDescription = "Effacer les journaux", tint = Pal.Sub)
                            }
                        }
                        else -> {}
                    }
                }
            )
        },
        bottomBar = {
            NpvBottomBar(current = highlighted, onSelect = { tab ->
                navController.navigate(tab) {
                    launchSingleTop = true
                    popUpTo("home")
                }
            })
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") {
                HomeScreen(
                    onOpenConfigs = { go("configs") },
                    onOpenMore = { go("more") },
                    onOpenUpdate = { go("update") }
                )
            }
            composable("configs") {
                ConfigsScreen(onEdit = { id ->
                    EditorHolder.profileId = id
                    go("editor")
                })
            }
            composable("logs") { LogsScreen() }
            composable("more") { MoreScreen(onNavigate = go) }
            composable("settings") { SettingsScreen() }
            composable("apps") { AppsScreen() }
            composable("share") { ShareScreen() }
            composable("about") { AboutScreen() }
            composable("guide") { GuideScreen() }
            composable("admin") { AdminScreen() }
            composable("update") { UpdateScreen() }
            composable("editor") { ConfigEditorScreen(onDone = { navController.popBackStack() }) }
        }
    }
}
