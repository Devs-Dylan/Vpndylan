package com.devsdylan.dylanvpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.devsdylan.dylanvpn.ui.MainNavigation
import com.devsdylan.dylanvpn.ui.Pal

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val darkScheme = darkColorScheme(
                primary = Pal.Accent,
                onPrimary = Color(0xFF002111),
                background = Pal.Bg,
                surface = Pal.Card,
                surfaceVariant = Pal.CardHi,
                onBackground = Pal.Text,
                onSurface = Pal.Text
            )
            MaterialTheme(colorScheme = darkScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainNavigation()
                }
            }
        }
    }
}
