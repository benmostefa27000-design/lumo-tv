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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.media3.common.C as MC
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionParameters
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
import com.lumo.tv.data.EpgEntry
import com.lumo.tv.data.Progress
import com.lumo.tv.data.USER_AGENT
import com.lumo.tv.ui.C
import com.lumo.tv.ui.IconFocus
import com.lumo.tv.ui.LumoButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** PlayerView qui laisse changer de chaîne avec haut/bas quand les commandes sont cachées. */
@OptIn(UnstableApi::class)
class ZapPlayerView(context: Context) : PlayerView(context) {
    var onZap: ((Int) -> Boolean)? = null
    var onMenu: (() -> Unit)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val menuKey = event.keyCode in listOf(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_INFO)
        val longOk = (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) && event.repeatCount == 4
        if (event.action == KeyEvent.ACTION_DOWN && (menuKey && event.repeatCount == 0 || longOk)) {
            onMenu?.let { it(); return true }
        }
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

    /** Téléphone : glisser vers le haut / le bas sur l'image pour changer de chaîne. */
    var swipeZap = false
    private val swipe = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
            val start = e1 ?: return false
            val dy = e2.y - start.y
            val dx = e2.x - start.x
            if (!swipeZap || Math.abs(dy) < height * 0.15f || Math.abs(dy) < Math.abs(dx) * 1.5f) return false
            return onZap?.invoke(if (dy < 0) 1 else -1) ?: false
        }
    })

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (swipeZap) swipe.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
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
    // Écran déjà utilisé : nouvel essai automatique (la connexion précédente met parfois ~1 min à se libérer)
    val uiScope = rememberCoroutineScope()
    var consInfo by remember { mutableStateOf<String?>(null) }
    var busySince by remember { mutableLongStateOf(0L) }
    var busyRetry by remember { mutableIntStateOf(0) }
    var busyCountdown by remember { mutableIntStateOf(0) }
    val errorFr = remember { androidx.compose.ui.focus.FocusRequester() }
    var panel by remember { mutableStateOf(false) }
    val panelFr = remember { androidx.compose.ui.focus.FocusRequester() }
    var avDelay by remember { mutableIntStateOf(0) }
    var retries by remember { mutableIntStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }
    // TV : commandes maison pilotées par la télécommande
    var showUi by remember { mutableStateOf(true) }
    var uiTick by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var posMs by remember { mutableLongStateOf(0L) }
    var durMs by remember { mutableLongStateOf(0L) }
    val rootFr = remember { androidx.compose.ui.focus.FocusRequester() }
    var banner by remember { mutableStateOf<String?>(null) }
    var chanList by remember { mutableStateOf(false) }
    var videoMode by remember { mutableIntStateOf(m.store.videoMode) }
    val modeNames = listOf("Adapté", "Plein écran", "Étiré", "Zoom 120 %", "Zoom 135 %")
    fun nextVideoMode() {
        videoMode = (videoMode + 1) % modeNames.size
        m.store.videoMode = videoMode
        m.showToast("Image : " + modeNames[videoMode])
    }
    var epgNow by remember { mutableStateOf<List<EpgEntry>>(emptyList()) }
    var epgLoading by remember { mutableStateOf(true) }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val client = m.client ?: return

    // Programme en cours de la chaîne (rafraîchi chaque minute)
    val liveStream = (item as? Playable.Live)?.let { it.list[it.index] }
    LaunchedEffect(liveStream?.id) {
        epgNow = emptyList()
        epgLoading = true
        val st = liveStream ?: return@LaunchedEffect
        while (true) {
            val list = withContext(Dispatchers.IO) { try { client.epgFor(st, full = false) } catch (_: Exception) { emptyList() } }
            if (list.isNotEmpty()) epgNow = list
            epgLoading = false
            clock = System.currentTimeMillis()
            repeat(4) { delay(15_000); clock = System.currentTimeMillis() }
        }
    }

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
        val ds = OkHttpDataSource.Factory(m.mediaHttp).setUserAgent(USER_AGENT)
        ExoPlayer.Builder(context, SyncRenderersFactory(context))
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .build()
            .apply {
                playWhenReady = true
                val b = trackSelectionParameters.buildUpon()
                m.store.prefAudio.takeIf { it.isNotBlank() }?.let { b.setPreferredAudioLanguage(it) }
                when (val t = m.store.prefText) {
                    "" -> {}
                    "off" -> b.setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, true)
                    else -> b.setPreferredTextLanguage(t)
                }
                trackSelectionParameters = b.build()
            }
    }

    // Minuteur de sommeil
    var sleepAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(sleepAt) {
        val at = sleepAt ?: return@LaunchedEffect
        delay((at - System.currentTimeMillis()).coerceAtLeast(0))
        player.pause()
        m.back()
    }

    // Arrière-plan : on coupe complètement le flux (sinon l'abonnement reste « occupé » → code 458 sur l'autre écran)
    var stoppedInBg by remember { mutableStateOf(false) }
    var bgPos by remember { mutableLongStateOf(0L) }

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
        // Position de reprise donnée AVANT prepare : une seule connexion au lieu de deux (0 puis reprise)
        val key = r.progressKey
        val prog = if (key != null && !fromStart) m.progressFor(key) else null
        val startAt = if (prog != null && !prog.finished && prog.pos > 10_000) prog.pos else 0L
        if (startAt > 0) player.setMediaItem(mi, startAt) else player.setMediaItem(mi)
        player.prepare()
        player.play()
    }

    LaunchedEffect(m.appStopped, m.pip) {
        if (m.appStopped && !m.pip && !stoppedInBg) {
            saveProgress()
            bgPos = if (item is Playable.Live) 0L else player.currentPosition
            player.stop()
            m.closeMediaConnections()
            stoppedInBg = true
        } else if (!m.appStopped && stoppedInBg) {
            stoppedInBg = false
            val cur = player.currentMediaItem
            if (bgPos > 0 && cur != null) { player.setMediaItem(cur, bgPos); player.prepare(); player.play() }
            else load(item, true)
        }
    }

    // Lancement et changements (chaîne, épisode suivant)
    fun syncKey(p: Playable): String = when (p) {
        is Playable.Live -> "live_" + p.list[p.index].id
        is Playable.Replay -> "live_" + p.stream.id
        is Playable.Movie -> "vod_" + p.vod.id
        is Playable.Ep -> "series_" + p.series.id
    }
    LaunchedEffect(item) {
        (item as? Playable.Live)?.let { m.store.addWatch(m.pid, it.list[it.index].id) }
        avDelay = m.store.avDelay(syncKey(item))
        AvSync.delayMs = avDelay
    }
    fun setDelay(ms: Int) {
        avDelay = ms.coerceIn(-5000, 5000)
        AvSync.delayMs = avDelay
        m.store.setAvDelay(syncKey(item), avDelay)
    }
    LaunchedEffect(panel) { if (panel) { delay(150); try { panelFr.requestFocus() } catch (_: Exception) { } } }

    androidx.activity.compose.BackHandler(enabled = panel) { panel = false }
    androidx.activity.compose.BackHandler(enabled = chanList) { chanList = false }

    // TV : masquer l'affichage après quelques secondes (sauf en pause)
    LaunchedEffect(uiTick, paused, panel, errorMsg) {
        if (m.isTv && !paused && !panel && errorMsg == null) { delay(4500); showUi = false }
    }
    // TV : position du film rafraîchie quand l'affichage est visible
    LaunchedEffect(showUi) {
        while (m.isTv && showUi) { posMs = player.currentPosition; durMs = player.duration.coerceAtLeast(0); delay(1000) }
    }
    // TV : la télécommande revient sur la vidéo quand un panneau se ferme
    LaunchedEffect(panel, errorMsg) {
        if (m.isTv && !panel && errorMsg == null) { delay(120); try { rootFr.requestFocus() } catch (_: Exception) { } }
    }

    var loadedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(item, useHls) {
        val fromStart = when (val it = item) { is Playable.Movie -> it.fromStart; is Playable.Ep -> it.fromStart; else -> false }
        if (loadedOnce) {
            // Changement de chaîne : on ferme d'abord l'ancien flux, sinon le fournisseur voit 2 écrans (code 458).
            // Le délai regroupe aussi les appuis rapides : seule la dernière chaîne choisie est ouverte.
            player.stop()
            m.closeMediaConnections()
            errorMsg = null; busyRetry = 0; busyCountdown = 0; busySince = 0L
            delay(900)
        }
        loadedOnce = true
        load(item, fromStart)
        if (item is Playable.Live) {
            banner = resolve(item).title
            delay(3000)
            banner = null
        }
    }

    // Ordres envoyés depuis la télécommande du téléphone
    LaunchedEffect(Unit) {
        m.remoteEvents.collect { a ->
            when (a) {
                "playpause" -> if (player.isPlaying) player.pause() else player.play()
                "fwd" -> player.seekTo(player.currentPosition + 30_000)
                "rew" -> player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                "next", "prev" -> {
                    val dir = if (a == "next") 1 else -1
                    when (val it = item) {
                        is Playable.Live -> { val n = it.list.size; item = it.copy(index = ((it.index + dir) % n + n) % n) }
                        is Playable.Ep -> { val ni = it.index + dir; if (ni in it.episodes.indices) item = it.copy(index = ni, fromStart = true) }
                        else -> {}
                    }
                }
            }
        }
    }

    // Écran occupé : on surveille le compteur de connexions du fournisseur et on relance dès qu'une place se libère
    LaunchedEffect(busyRetry) {
        if (busyRetry == 0) { consInfo = null; return@LaunchedEffect }
        val t0 = System.currentTimeMillis()
        if (busySince == 0L) busySince = t0
        while (errorMsg != null) {
            val a = withContext(Dispatchers.IO) { try { m.client?.auth() } catch (_: Exception) { null } }
            val elapsed = System.currentTimeMillis() - t0
            if (a != null && a.maxConnections > 0) {
                consInfo = "Écrans utilisés sur ton abonnement : ${a.activeCons}/${a.maxConnections}"
                if (a.activeCons < a.maxConnections && elapsed >= 3000) { errorMsg = null; load(item, false); return@LaunchedEffect }
            } else if (elapsed >= 10_000) { errorMsg = null; load(item, false); return@LaunchedEffect }
            busyCountdown = ((System.currentTimeMillis() - busySince) / 1000).toInt().coerceAtLeast(1)
            delay(5000)
        }
    }

    // La télécommande va sur les boutons quand un message s'affiche
    LaunchedEffect(errorMsg) {
        if (errorMsg != null) { delay(150); try { errorFr.requestFocus() } catch (_: Exception) { } }
    }

    // Sauvegarde régulière de la position
    LaunchedEffect(Unit) {
        while (true) { delay(10_000); if (player.isPlaying) saveProgress() }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                paused = !playWhenReady
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { retries = 0; busyRetry = 0; busyCountdown = 0; busySince = 0L }
                if (state == Player.STATE_ENDED) {
                    saveProgress(finished = true)
                    val it = item
                    if (it is Playable.Ep && it.index + 1 < it.episodes.size) {
                        item = it.copy(index = it.index + 1, fromStart = true)
                        m.showToast("Épisode suivant")
                    }
                }
            }

            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                // Retenir la langue choisie par l'utilisateur dans le lecteur
                for (o in parameters.overrides.values) {
                    val g = o.mediaTrackGroup
                    val lang = o.trackIndices.firstOrNull()?.let { g.getFormat(it).language } ?: continue
                    if (g.type == MC.TRACK_TYPE_AUDIO) m.store.prefAudio = lang
                    if (g.type == MC.TRACK_TYPE_TEXT) m.store.prefText = lang
                }
                if (MC.TRACK_TYPE_TEXT in parameters.disabledTrackTypes) m.store.prefText = "off"
                else if (m.store.prefText == "off") m.store.prefText = ""
            }

            override fun onPlayerError(error: PlaybackException) {
                val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                if (code != null && code in listOf(401, 403, 405, 456, 458, 509, 429)) {
                    errorMsg = "Ton abonnement est déjà utilisé sur un autre écran (code $code), ou la connexion précédente n'est pas encore libérée."
                    busyRetry++; busyCountdown = 1
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
            AvSync.delayMs = 0
            saveProgress()
            player.removeListener(listener)
            player.release()
            m.closeMediaConnections()
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!m.isTv) {
                activity?.requestedOrientation = oldOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                activity?.window?.let { w -> WindowCompat.getInsetsController(w, w.decorView).show(WindowInsetsCompat.Type.systemBars()) }
            }
        }
    }

    val r = resolve(item)
    val now = clock
    val cur = epgNow.firstOrNull { it.start <= now && it.end > now }
    val next = epgNow.firstOrNull { it.start >= (cur?.end ?: now) }
    val hhmm = remember { SimpleDateFormat("HH:mm", Locale.FRANCE) }
    val uiVisible = if (m.isTv) showUi else controlsVisible

    fun poke() { showUi = true; uiTick++ }
    fun zap(dir: Int): Boolean {
        val it = item
        if (it !is Playable.Live || it.list.size < 2) return false
        val n = it.list.size
        item = it.copy(index = ((it.index + dir) % n + n) % n)
        poke()
        return true
    }
    fun seekBy(ms: Long) {
        player.seekTo((player.currentPosition + ms).coerceAtLeast(0))
        posMs = player.currentPosition
        poke()
    }
    fun tvKey(e: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (!m.isTv || panel || errorMsg != null) return false
        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return false
        val live = item is Playable.Live
        return when (e.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                if (e.nativeKeyEvent.repeatCount > 0) { if (e.nativeKeyEvent.repeatCount == 4) panel = true; true }
                else { if (player.playWhenReady) player.pause() else player.play(); poke(); true }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); poke(); true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); poke(); true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { if (!(live && zap(-1))) poke(); true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (!(live && zap(1))) poke(); true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { if (!live) seekBy(-10_000) else poke(); true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (!live) seekBy(30_000) else poke(); true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_INFO -> { panel = true; true }
            else -> false
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .then(
                if (m.isTv) Modifier
                    .onPreviewKeyEvent { tvKey(it) }
                    .focusRequester(rootFr)
                    .focusable()
                else Modifier
            )
    ) {
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
                    if (!m.isTv) post { requestFocus() }
                }
            },
            update = { v ->
                v.resizeMode = when (videoMode) {
                    1, 3, 4 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    2 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                val scale = when (videoMode) { 3 -> 1.2f; 4 -> 1.35f; else -> 1f }
                v.clipChildren = true
                v.resources.getIdentifier("exo_content_frame", "id", v.context.packageName).takeIf { id -> id != 0 }
                    ?.let { id -> v.findViewById<android.view.View>(id) }
                    ?.let { f -> f.scaleX = scale; f.scaleY = scale }
                val blocked = errorMsg != null || panel || m.isTv
                v.onMenu = { panel = !panel }
                v.useController = !m.pip && !blocked
                v.isFocusable = !blocked
                v.isFocusableInTouchMode = !blocked
                if (blocked) v.clearFocus() else if (!v.hasFocus()) v.post { v.requestFocus() }
                val isLive = item is Playable.Live
                v.setShowRewindButton(!isLive)
                v.setShowFastForwardButton(!isLive)
                val hide = if (isLive) android.view.View.GONE else android.view.View.VISIBLE
                // Pas de voile sombre sur toute l'image quand on touche l'écran, barre du bas transparente
                fun exo(n: String) = v.resources.getIdentifier(n, "id", v.context.packageName).takeIf { id -> id != 0 }?.let { id -> v.findViewById<android.view.View>(id) }
                exo("exo_controls_background")?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                exo("exo_bottom_bar")?.let { b ->
                    b.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    b.visibility = if (isLive) android.view.View.GONE else android.view.View.VISIBLE
                    b.alpha = if (isLive) 0f else 1f
                }
                listOf("exo_progress", "exo_position", "exo_duration", "exo_time")
                    .map { n -> v.resources.getIdentifier(n, "id", v.context.packageName) }
                    .filter { id -> id != 0 }
                    .forEach { id -> v.findViewById<android.view.View>(id)?.visibility = hide }
                val it = item
                v.swipeZap = false
                v.onZap = if (it is Playable.Live && it.list.size > 1) { dir ->
                    val n = it.list.size
                    item = it.copy(index = ((it.index + dir) % n + n) % n)
                    true
                } else null
            },
            modifier = Modifier.fillMaxSize()
        )

        if (!m.pip && (uiVisible || errorMsg != null)) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!m.isTv) IconFocus(Icons.AutoMirrored.Filled.ArrowBack, "Retour") { m.back() }
                Column(Modifier.weight(1f)) {
                    Text(r.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    val sub = if (item is Playable.Live && cur != null) "En direct · ${cur.title}" else r.subtitle
                    if (sub.isNotBlank()) Text(sub, color = C.Sub, fontSize = 14.sp, maxLines = 1)
                }
                if (m.isTv) {
                    Text(
                        "OK : pause · Menu ou OK long : son, image et minuteur" + if (item is Playable.Live) " · ▲▼ : chaînes" else " · ◀▶ : −10 s / +30 s",
                        color = C.Sub, fontSize = 13.sp
                    )
                } else if (item is Playable.Live && (item as Playable.Live).list.size > 1) {
                    com.lumo.tv.ui.FocusBox(shape = RoundedCornerShape(50), scale = 1.06f, onClick = { chanList = !chanList }) { _ ->
                        Text(
                            "☰ Chaînes", color = Color.White, fontSize = 14.sp,
                            modifier = Modifier.background(Color(0x55FFFFFF), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
                if (!m.isTv) com.lumo.tv.ui.FocusBox(shape = RoundedCornerShape(50), scale = 1.06f, onClick = { nextVideoMode() }) { _ ->
                    Text(
                        "⤢ " + modeNames[videoMode], color = Color.White, fontSize = 14.sp,
                        modifier = Modifier.background(Color(0x55FFFFFF), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
                if (!m.isTv) com.lumo.tv.ui.FocusBox(shape = RoundedCornerShape(50), scale = 1.06f, onClick = { panel = !panel }) { _ ->
                    Text(
                        if (avDelay == 0) "Synchro son" else "Son ${if (avDelay > 0) "+" else ""}$avDelay ms",
                        color = Color.White, fontSize = 14.sp,
                        modifier = Modifier.background(Color(0x55FFFFFF), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
                val left = sleepAt?.let { ((it - clock) / 60000).coerceAtLeast(0) }
                if (!m.isTv) com.lumo.tv.ui.FocusBox(shape = RoundedCornerShape(50), scale = 1.06f, onClick = {
                    val steps = listOf<Long?>(30, 60, 90, null)
                    val cur = left?.let { l -> steps.firstOrNull { st -> st != null && st >= l + 1 } }
                    val idx = if (sleepAt == null) 0 else (steps.indexOf(cur) + 1).coerceAtMost(steps.lastIndex)
                    sleepAt = steps[idx]?.let { min -> System.currentTimeMillis() + min * 60_000 }
                    clock = System.currentTimeMillis()
                    m.showToast(if (sleepAt == null) "Minuteur coupé" else "Arrêt dans ${steps[idx]} min")
                }) { _ ->
                    Text(
                        if (left == null) "Minuteur" else "Arrêt dans $left min",
                        color = Color.White, fontSize = 14.sp,
                        modifier = Modifier.background(Color(0x55FFFFFF), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        if (!m.pip && item is Playable.Live && !m.isTv && !chanList && (uiVisible || banner != null)) {
            // Téléphone : bandeau compact en haut, pour laisser libre le bouton pause au centre
            val it = item as Playable.Live
            Column(
                Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 70.dp).widthIn(max = 380.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DIRECT", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(C.Red, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
                    Text("${it.index + 1}/${it.list.size}  ${it.list[it.index].name}", color = C.Sub, fontSize = 11.sp, maxLines = 1)
                }
                if (cur != null) {
                    Text(cur.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, lineHeight = 21.sp)
                    Text("${hhmm.format(Date(cur.start))} – ${hhmm.format(Date(cur.end))}", color = C.Sub, fontSize = 11.sp)
                    val frac = ((now - cur.start).toFloat() / (cur.end - cur.start)).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0x55FFFFFF))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(C.Red))
                    }
                    if (next != null) Text("Ensuite ${hhmm.format(Date(next.start))}  ${next.title}", color = C.Sub, fontSize = 11.sp, maxLines = 1)
                } else if (epgLoading) Text("Chargement du programme…", color = C.Sub, fontSize = 11.sp)
            }
        }

        if (!m.pip && item is Playable.Live && m.isTv && (uiVisible || banner != null)) {
            Column(
                Modifier.align(Alignment.BottomStart).padding(start = 24.dp, end = 24.dp, bottom = 28.dp)
                    .widthIn(max = 640.dp).fillMaxWidth()
                    .background(Color(0xCC000000), RoundedCornerShape(10.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val it = item as Playable.Live
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("EN DIRECT", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(C.Red, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                    Text("${it.index + 1}/${it.list.size}  ${it.list[it.index].name}", color = C.Sub, fontSize = 13.sp, maxLines = 1)
                }
                if (cur != null) {
                    Text(cur.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    val frac = ((now - cur.start).toFloat() / (cur.end - cur.start)).coerceIn(0f, 1f)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(hhmm.format(Date(cur.start)), color = C.Sub, fontSize = 13.sp)
                        Box(Modifier.weight(1f).height(4.dp).background(Color(0x55FFFFFF), RoundedCornerShape(2.dp))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(C.Red, RoundedCornerShape(2.dp)))
                        }
                        Text(hhmm.format(Date(cur.end)), color = C.Sub, fontSize = 13.sp)
                    }
                    val left = ((cur.end - now) / 60000).coerceAtLeast(0)
                    Text("Encore $left min", color = C.Dim, fontSize = 12.sp)
                } else {
                    Text(
                        when { epgLoading -> "Chargement du programme…"; epgNow.isEmpty() -> "Programme non disponible pour cette chaîne"; else -> "—" },
                        color = C.Sub, fontSize = 14.sp
                    )
                }
                if (next != null) Text("Ensuite : ${hhmm.format(Date(next.start))}  ${next.title}", color = C.Sub, fontSize = 13.sp, maxLines = 1)
            }
        }

        // Téléphone : boutons chaîne précédente / suivante, qui apparaissent et s'effacent avec le bouton pause
        if (!m.isTv && !m.pip && !chanList && !panel && errorMsg == null && uiVisible && item is Playable.Live && (item as Playable.Live).list.size > 1) {
            listOf(-1 to Alignment.CenterStart, 1 to Alignment.CenterEnd).forEach { (dir, al) ->
                Box(
                    Modifier.align(al).padding(horizontal = 28.dp).size(56.dp)
                        .background(Color(0x66000000), androidx.compose.foundation.shape.CircleShape)
                        .clickable { zap(dir) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (dir < 0) "‹" else "›", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
        }

        if (chanList && !m.pip && item is Playable.Live) {
            val live = item as Playable.Live
            val listState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (live.index - 2).coerceAtLeast(0))
            Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable { chanList = false })
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(300.dp).background(Color(0xF0141414)),
                state = listState,
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(live.list.size) { i ->
                    val ch = live.list[i]
                    val sel = i == live.index
                    Row(
                        Modifier.fillMaxWidth().clickable { item = live.copy(index = i); chanList = false }
                            .background(if (sel) Color(0x33E11D2E) else Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("${i + 1}", color = C.Dim, fontSize = 12.sp, modifier = Modifier.width(28.dp))
                        if (ch.icon.isNotBlank()) coil.compose.AsyncImage(model = ch.icon, contentDescription = null, modifier = Modifier.size(width = 44.dp, height = 28.dp))
                        Text(ch.name, color = if (sel) Color.White else C.Sub, fontSize = 14.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                    }
                }
            }
        }

        if (item !is Playable.Live) banner?.let { b ->
            Box(Modifier.align(Alignment.BottomStart).padding(32.dp).background(Color(0xDD141414), RoundedCornerShape(8.dp)).padding(horizontal = 20.dp, vertical = 14.dp)) {
                val it = item
                val num = if (it is Playable.Live) "${it.index + 1}/${it.list.size}  " else ""
                Text(num + b, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (m.isTv && !m.pip && paused && errorMsg == null && !panel) {
            Box(Modifier.align(Alignment.Center).background(Color(0xAA000000), RoundedCornerShape(50)).padding(horizontal = 28.dp, vertical = 14.dp)) {
                Text("Pause", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (m.isTv && !m.pip && uiVisible && item !is Playable.Live && errorMsg == null) {
            fun t(ms: Long): String { val s2 = ms / 1000; val h = s2 / 3600; val mi = (s2 % 3600) / 60; val se = s2 % 60; return if (h > 0) "%d:%02d:%02d".format(h, mi, se) else "%02d:%02d".format(mi, se) }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(horizontal = 40.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val frac = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(5.dp).background(Color(0x55FFFFFF), RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(C.Red, RoundedCornerShape(3.dp)))
                }
                Row(Modifier.fillMaxWidth()) {
                    Text(t(posMs), color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Text(if (durMs > 0) "-" + t(durMs - posMs) else "", color = C.Sub, fontSize = 14.sp)
                }
            }
        }

        if (panel && !m.pip) {
            Column(
                Modifier.align(Alignment.CenterEnd).padding(24.dp).widthIn(max = 420.dp)
                    .background(Color(0xF0141414), RoundedCornerShape(12.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Synchro du son", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        avDelay == 0 -> "Réglage d'origine"
                        avDelay > 0 -> "Son retardé de $avDelay ms"
                        else -> "Son avancé de ${-avDelay} ms"
                    },
                    color = C.Sub, fontSize = 15.sp
                )
                Text("Les lèvres bougent avant le son : avance le son. Le son arrive avant les lèvres : retarde-le.", color = C.Dim, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LumoButton("Avancer le son", requester = panelFr) { setDelay(avDelay - 100) }
                    LumoButton("Retarder", primary = false) { setDelay(avDelay + 100) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LumoButton("Remettre à 0", primary = false) { setDelay(0) }
                    LumoButton("Fermer", primary = false) { panel = false }
                }
                if (m.isTv) LumoButton("Image : " + modeNames[videoMode], primary = false) { nextVideoMode() }
                if (m.isTv) {
                    val l = sleepAt?.let { ((it - clock) / 60000).coerceAtLeast(0) }
                    LumoButton(if (l == null) "Minuteur : aucun" else "Minuteur : arrêt dans $l min", primary = false) {
                        val steps = listOf<Long?>(30, 60, 90, null)
                        val cur = l?.let { x -> steps.firstOrNull { st -> st != null && st >= x + 1 } }
                        val idx = if (sleepAt == null) 0 else (steps.indexOf(cur) + 1).coerceAtMost(steps.lastIndex)
                        sleepAt = steps[idx]?.let { min -> System.currentTimeMillis() + min * 60_000 }
                        clock = System.currentTimeMillis()
                    }
                }
                Text("Mémorisé pour cette chaîne, ce film ou cette série.", color = C.Dim, fontSize = 12.sp)
            }
        }

        errorMsg?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).background(Color(0xEE141414), RoundedCornerShape(10.dp)).padding(24.dp).widthIn(max = 520.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(msg, color = Color.White, fontSize = 16.sp)
                consInfo?.let { Text(it, color = Color.White, fontSize = 15.sp) }
                if (busyCountdown > 0) Text("Relance automatique dès qu'un écran se libère (attente ${busyCountdown / 60} min ${busyCountdown % 60} s). Ton fournisseur peut mettre jusqu'à 5 min.", color = C.Sub, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LumoButton("Réessayer", requester = errorFr) { retries = 0; busyRetry = 0; busyCountdown = 0; errorMsg = null; load(item, false) }
                    LumoButton("Retour", primary = false) { m.back() }
                }
                if (busyRetry > 0) {
                    LumoButton(if (m.isTv) "Couper la lecture sur le téléphone" else "Couper la lecture sur la TV", primary = false) {
                        uiScope.launch {
                            val n = m.stopOtherScreens()
                            m.showToast(if (n > 0) "Ordre d'arrêt envoyé à l'autre écran" else "Aucun autre écran trouvé")
                            if (busyCountdown == 0) busyRetry++
                        }
                    }
                }
            }
        }
    }
}
