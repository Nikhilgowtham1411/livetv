package com.nikhil.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.util.Locale

private const val DEFAULT_UA =
    "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(channels: List<Channel>, start: Int) {
    val ctx = LocalContext.current
    val favs = remember { FavStore(ctx) }
    val teluguStore = remember { FavStore(ctx, "telugu_custom") }
    var index by remember { mutableIntStateOf(start) }
    var status by remember { mutableStateOf("Loading…") }
    var note by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }

    val http = remember {
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
    }
    val exo = remember {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setTrackSelector(
                DefaultTrackSelector(ctx).also {
                    it.setParameters(
                        it.buildUponParameters()
                            .setForceHighestSupportedBitrate(true)
                            .setPreferredAudioLanguage("te")
                    )
                }
            )
            .build()
            .apply { playWhenReady = true }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING) status = "Loading…"
                if (playbackState == Player.STATE_READY) status = ""
            }

            override fun onPlayerError(error: PlaybackException) {
                status = "This channel isn't working right now.\nPress ▲ / ▼ to try another channel."
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            exo.release()
        }
    }

    val ch = channels[index]

    LaunchedEffect(index) {
        status = "Loading…"
        note = null
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .build()
        http.setUserAgent(ch.userAgent ?: DEFAULT_UA)
        http.setDefaultRequestProperties(ch.referrer?.let { mapOf("Referer" to it) } ?: emptyMap())
        exo.setMediaItem(MediaItem.fromUri(ch.url))
        exo.prepare()
    }
    LaunchedEffect(index, tick) {
        showInfo = true
        delay(5000)
        showInfo = false
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (e.key) {
                        Key.DirectionUp, Key.ChannelUp -> {
                            index = (index + 1) % channels.size
                            true
                        }
                        Key.DirectionDown, Key.ChannelDown -> {
                            index = (index - 1 + channels.size) % channels.size
                            true
                        }
                        Key.DirectionLeft -> {
                            val options = exo.currentTracks.groups
                                .filter { it.type == C.TRACK_TYPE_AUDIO }
                                .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
                            if (options.size < 2) {
                                note = "No other audio language in this channel"
                            } else {
                                val cur = options.indexOfFirst { (g, i) -> g.isTrackSelected(i) }.coerceAtLeast(0)
                                val (g, i) = options[(cur + 1) % options.size]
                                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                                    .build()
                                val f = g.getTrackFormat(i)
                                val lang = f.language?.let { Locale(it).displayLanguage }?.takeIf { it.isNotBlank() }
                                note = "Audio: " + (lang ?: f.label ?: "Track " + (((cur + 1) % options.size) + 1))
                            }
                            tick++
                            true
                        }
                        Key.DirectionRight -> {
                            val added = teluguStore.toggle(ch)
                            note = if (added) "Added to Telugu list" else "Removed from Telugu list"
                            tick++
                            true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            val added = favs.toggle(ch)
                            note = if (added) "★ Added to favourites" else "Removed from favourites"
                            tick++
                            true
                        }
                        else -> false
                    }
                }
            }
            .focusable()
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                PlayerView(c).apply {
                    player = exo
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    isFocusable = false
                    isFocusableInTouchMode = false
                }
            },
        )

        if (showInfo || status.isNotEmpty()) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                    .padding(start = 48.dp, end = 48.dp, top = 80.dp, bottom = 32.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", color = Accent, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(16.dp))
                    Text(ch.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    if (favs.isFav(ch)) {
                        Spacer(Modifier.width(12.dp))
                        Text("★", color = Accent, fontSize = 28.sp)
                    }
                }
                val line = note ?: status
                if (line.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(line, color = Color.White, fontSize = 20.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "▲ / ▼  change channel     OK  favourite     ◀  audio language     ▶  add to Telugu list     Back  channel list",
                    color = Color(0xFF9A9AB0),
                    fontSize = 15.sp,
                )
            }
        }
    }
}
