package com.nikhil.livetv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class AppLink(val name: String, val pkg: String, val note: String)

val APPS = listOf(
    AppLink("JioHotstar", "in.startv.hotstar", "Star Maa, Star Vijay, Star Sports"),
    AppLink("ZEE5", "com.graymatrix.did", "Zee Telugu, Zee TV, Zee Cinema"),
    AppLink("SonyLIV", "com.sonyliv", "Sony channels and shows"),
    AppLink("Sun NXT", "com.suntv.sunnxt", "Gemini, Sun TV channels"),
    AppLink("JioTV", "com.jio.jioplay.tv", "Live TV channels (Jio users)"),
    AppLink("YouTube", "com.google.android.youtube.tv", "Kids channels and many live streams"),
    AppLink("Pluto TV", "tv.pluto.android", "Free live channels"),
    AppLink("MX Player", "com.mxtech.videoplayer.ad", "Free shows and movies"),
)

fun openApp(ctx: Context, a: AppLink) {
    try {
        val pm = ctx.packageManager
        val launch = pm.getLeanbackLaunchIntentForPackage(a.pkg) ?: pm.getLaunchIntentForPackage(a.pkg)
        if (launch != null) {
            ctx.startActivity(launch)
        } else {
            val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(a.name) + "&c=apps"))
            store.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(store)
        }
    } catch (e: Exception) {
        Toast.makeText(ctx, "Couldn't open " + a.name, Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun AppsGrid() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Text(
            "Star Maa, Zee Telugu, History TV18 and other paid channels play only inside their official apps. Open them from here:",
            color = Color(0xFFB0B0C5),
            fontSize = 16.sp,
            modifier = Modifier.padding(8.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(240.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(APPS) { a ->
                Focusable(
                    onClick = { openApp(ctx, a) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(a.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(a.note, color = Color(0xFF9A9AB0), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}
