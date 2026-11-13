package com.lumo.tv.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.lumo.tv.AppModel
import com.lumo.tv.Playable
import com.lumo.tv.data.Progress
import com.lumo.tv.data.USER_AGENT
import com.lumo.tv.ui.C
import com.lumo.tv.ui.IconFocus
import com.lumo.tv.ui.LumoButton
import kotlinx.coroutines.delay

/** PlayerView qui laisse changer de chaîne avec haut/bas quand les commandes sont cachées. */
@OptIn(UnstableApi::class)
class ZapPlayerView(context: Context) : PlayerView(context) {
    var onZap: ((Int) -> Boolean)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val zap = onZap
        if (zap != null && event.action == KeyEvent.ACTION_DOWN && !isControllerFullyVisible) {
            val dir = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> -1
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> 1
                else -> 0
            }
            if (dir != 0 && zap(dir)) return true
        }
        return super.dispatchKeyEvent(event)
    }
}

private data class Resolved(
    val url: String,
    val title: String,
    val subtitle: String,
    val progressKey: String?,
    val live: Boolean
)

@SuppressLint("SourceLockedOrientationActivity")
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(m: AppModel, start: Playable) {
    val context = LocalContext.current
    val activity = context as? Activity
    var item by remember { mutableStateOf(start) }
    var useHls by remember { mutableStateOf(m.store.liveFormat == "m3u8") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var retries by remember { mutableIntStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }
    var banner by remember { mutableStateOf<String?>(null) }
    val client = m.client ?: return

    fun resolve(p: Playable): Resolved = when (p) {
        is Playable.Live -> {
            val s = p.list[p.index]
            Resolved(client.liveUrl(s.id, if (useHls) "m3u8" else "ts"), s.name, "En direct", null, true)
        }
        is Playable.Movie -> Resolved(client.vodUrl(p.vod.id, p.vod.ext), p.vod.name, "", "vod_${p.vod.id}", false)
        is Playable.Ep -> {
            val e = p.episodes[p.index]
            Resolved(client.episodeUrl(e.id, e.ext), p.series.name, "S${e.season} É${e.num}${if (e.title.isNotBlank()) " · " + e.title else ""}", "ep_${e.id}", false)
        }
        is Playable.Replay -> Resolved(client.timeshiftUrl(p.stream.id, p.entry), p.entry.title, "${p.stream.name} · Rattrapage", null, false)
    }

    val player = remember {
        val ds = OkHttpDataSource.Factory(m.http).setUserAgent(USER_AGENT)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .build()
            .apply { playWhenReady = true }
    }

    fun saveProgress(finished: Boolean = false) {
        val r = resolve(item)
        val key = r.progressKey ?: return
        val dur = player.duration.takeIf { it > 0 } ?: return
        val pos = if (finished) dur else player.currentPosition
        if (pos < 5_000 && !finished) return
        val p = when (val it = item) {
            is Playable.Movie -> Progress(key, "vod", it.vod.id, "", it.vod.name, "", it.vod.icon, it.vod.ext, pos, dur, System.currentTimeMillis())
            is Playable.Ep -> {
                val e = it.episodes[it.index]
                Progress(key, "ep", e.id, it.series.id, it.series.name, "S${e.season} É${e.num}", it.series.cover, e.ext, pos, dur, System.currentTimeMillis())
            }
            else -> return
        }
        m.saveProgress(p)
    }

    fun load(p: Playable, fromStart: Boolean) {
        val r = resolve(p)
        errorMsg = null
        val mi = MediaItem.Builder().setUri(r.url).apply {
            if (r.url.endsWith(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player.setMediaItem(mi)
        player.prepare()
        val key = r.progressKey
        if (key != null && !fromStart) {
            val prog = m.progressFor(key)
            if (prog != null && !prog.finished && prog.pos > 10_000) player.seekTo(prog.pos)
        }
        player.play()
    }

    // Lancement et changements (chaîne, épisode suivant)
    LaunchedEffect(item, useHls) {
        val fromStart = when (val it = item) { is Playable.Movie -> it.fromStart; is Playable.Ep -> it.fromStart; else -> false }
        load(item, fromStart)
        if (item is Playable.Live) {
            banner = resolve(item).title
            delay(3000)
            banner = null
        }
    }

    // Sauvegarde régulière de la position
    LaunchedEffect(Unit) {
        while (true) { delay(10_000); if (player.isPlaying) saveProgress() }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) retries = 0
                if (state == Player.STATE_ENDED) {
                    saveProgress(finished = true)
                    val it = item
                    if (it is Playable.Ep && it.index + 1 < it.episodes.size) {
                        item = it.copy(index = it.index + 1, fromStart = true)
                        m.showToast("Épisode suivant")
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                if (code != null && code in listOf(401, 403, 405, 456, 458, 509, 429)) {
                    errorMsg = "Le serveur refuse la lecture (code $code).\nTon abonnement est peut-être déjà utilisé sur un autre écran."
                    return
                }
                val isLive = item is Playable.Live
                if (isLive && !useHls && retries == 1) { useHls = true; retries++; return }
                if (retries < 4) {
                    retries++
                    val pos = player.currentPosition
                    player.prepare()
                    if (!isLive && pos > 0) player.seekTo(pos)
                    player.play()
                } else {
                    errorMsg = "Lecture impossible : ${error.errorCodeName}"
                }
            }
        }
        player.addListener(listener)

        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val oldOrientation = activity?.requestedOrientation
        if (!m.isTv) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            activity?.window?.let { w ->
                val c = WindowCompat.getInsetsController(w, w.decorView)
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            saveProgress()
            player.removeListener(listener)
            player.release()
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!m.isTv) {
                activity?.requestedOrientation = oldOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                activity?.window?.let { w -> WindowCompat.getInsetsController(w, w.decorView).show(WindowInsetsCompat.Type.systemBars()) }
            }
        }
    }

    val r = resolve(item)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                ZapPlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    this.player = player
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowSubtitleButton(true)
                    controllerShowTimeoutMs = 4000
                    keepScreenOn = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controlsVisible = v == android.view.View.VISIBLE })
                    post { requestFocus() }
                }
            },
            update = { v ->
                val it = item
                v.onZap = if (it is Playable.Live && it.list.size > 1) { dir ->
                    val n = it.list.size
                    item = it.copy(index = ((it.index + dir) % n + n) % n)
                    true
                } else null
            },
            modifier = Modifier.fillMaxSize()
        )

        if (controlsVisible || errorMsg != null) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconFocus(Icons.AutoMirrored.Filled.ArrowBack, "Retour") { m.back() }
                Column {
                    Text(r.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (r.subtitle.isNotBlank()) Text(r.subtitle, color = C.Sub, fontSize = 14.sp, maxLines = 1)
                }
            }
        }

        banner?.let { b ->
            Box(Modifier.align(Alignment.BottomStart).padding(32.dp).background(Color(0xDD141414), RoundedCornerShape(8.dp)).padding(horizontal = 20.dp, vertical = 14.dp)) {
                val it = item
                val num = if (it is Playable.Live) "${it.index + 1}/${it.list.size}  " else ""
                Text(num + b, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }

        errorMsg?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).background(Color(0xEE141414), RoundedCornerShape(10.dp)).padding(24.dp).widthIn(max = 520.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(msg, color = Color.White, fontSize = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LumoButton("Réessayer") { retries = 0; errorMsg = null; load(item, false) }
                    LumoButton("Retour", primary = false) { m.back() }
                }
            }
        }
    }
}
