package com.nikhil.livetv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

sealed interface Ui {
    data object Loading : Ui
    data class Ok(val list: List<Channel>) : Ui
    data class Err(val msg: String) : Ui
}

@Composable
fun Focusable(
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable BoxScope.(Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "scale")
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                when {
                    focused -> Color(0xFF2A2A40)
                    selected -> Color(0xFF22222F)
                    else -> Panel
                }
            )
            .border(3.dp, if (focused) Accent else Color.Transparent, shape)
            .clickable(onClick = onClick)
    ) {
        content(focused)
    }
}

@Composable
fun HomeScreen(onPlay: (List<Channel>, Int) -> Unit) {
    val ctx = LocalContext.current
    val favs = remember { FavStore(ctx) }
    var sel by rememberSaveable { mutableIntStateOf(2) } // 0 = favourites, 1 = official apps, 2.. = playlists
    var retry by remember { mutableIntStateOf(0) }
    var ui by remember { mutableStateOf<Ui>(Ui.Loading) }
    var progress by remember { mutableStateOf("") }
    val firstFocus = remember { FocusRequester() }
    var query by rememberSaveable { mutableStateOf("") }
    var quality by rememberSaveable { mutableIntStateOf(0) } // 0 all, 1 HD, 2 Full HD, 3 4K
    val cats = remember { listOf("★ Favourites", "Official Apps") + LANGS.map { it.label } }

    LaunchedEffect(sel, retry) {
        if (sel == 1) return@LaunchedEffect
        ui = Ui.Loading
        if (sel == 0) {
            val f = favs.load()
            ui = if (f.isEmpty()) {
                Ui.Err("No favourites yet.\nWhile watching a channel, press OK to add it.")
            } else {
                Ui.Ok(f)
            }
        } else {
            progress = ""
            val r = Repo.loadWorking(ctx, LANGS[sel - 2]) { d, t -> progress = "Checking channels $d / $t" }
            val list = r.getOrNull()
            ui = if (list != null) {
                Ui.Ok(list)
            } else {
                Ui.Err("No working channels found.\nCheck your internet and try again.")
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Live TV", color = Accent, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(24.dp))
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
            ) {
                itemsIndexed(cats) { i, label ->
                    Focusable(
                        onClick = { sel = i },
                        shape = RoundedCornerShape(22.dp),
                        selected = i == sel,
                    ) {
                        Text(
                            label,
                            color = if (i == sel) Accent else Color.White,
                            fontSize = 17.sp,
                            fontWeight = if (i == sel) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = if (sel == 1) null else ui) {
                null -> AppsGrid()
                is Ui.Loading -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = Accent)
                    if (progress.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text(progress, color = Color.White, fontSize = 18.sp)
                        Text("Hiding channels that don't work…", color = Color(0xFF9A9AB0), fontSize = 14.sp)
                    }
                }
                is Ui.Err -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(s.msg, color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    Focusable(onClick = { retry++ }, shape = RoundedCornerShape(12.dp)) {
                        Text("Retry", color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp))
                    }
                }
                is Ui.Ok -> {
                    LaunchedEffect(s.list) {
                        delay(200)
                        runCatching { firstFocus.requestFocus() }
                    }
                    val minQ = when (quality) { 1 -> 720; 2 -> 1080; 3 -> 2160; else -> 0 }
                    val shown = s.list.filter {
                        (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true)) &&
                            (minQ == 0 || qualityOf(it) >= minQ)
                    }
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            listOf("All", "HD", "Full HD", "4K").forEachIndexed { i, label ->
                                Focusable(
                                    onClick = { quality = i },
                                    shape = RoundedCornerShape(20.dp),
                                    selected = i == quality,
                                ) {
                                    Text(
                                        label,
                                        color = if (i == quality) Accent else Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = if (i == quality) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                placeholder = { Text("Search channel") },
                                modifier = Modifier.width(300.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        if (shown.isEmpty()) {
                            Text("No channel found", color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(16.dp))
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(190.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(shown, key = { _, c -> c.url }) { i, c ->
                                ChannelCard(c, if (i == 0) Modifier.focusRequester(firstFocus) else Modifier) { onPlay(shown, i) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChannelCard(c: Channel, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Focusable(onClick = onClick, shape = RoundedCornerShape(14.dp), modifier = modifier.fillMaxWidth()) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF1E1E2C)),
                contentAlignment = Alignment.Center,
            ) {
                if (c.logo != null) {
                    AsyncImage(
                        model = c.logo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(14.dp),
                    )
                } else {
                    Text(c.name.take(1).uppercase(), color = Accent, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                }
                val q = qualityOf(c)
                if (q >= 720) {
                    Text(
                        if (q >= 2160) "4K" else if (q >= 1080) "FHD" else "HD",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Accent, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                c.name,
                color = Color.White,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp).height(42.dp),
            )
        }
    }
}
