package com.nikhil.livetv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF69E0FF)
val Bg = Color(0xFF070B14)
val Panel = Color(0xFF121B2A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Panel)) {
                App()
            }
        }
    }
}

@Composable
fun App() {
    var playing by remember { mutableStateOf<Pair<List<Channel>, Int>?>(null) }
    Box(Modifier.fillMaxSize().background(Bg)) {
        val p = playing
        // Keep Home composed behind the player so category/search/scroll state survives Back.
        HomeScreen { list, i -> playing = list to i }
        if (p != null) {
            BackHandler { playing = null }
            PlayerScreen(p.first, p.second)
        }
    }
}
