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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.onFocusChanged
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
import com.lumo.tv.Screen
import com.lumo.tv.data.LiveStream
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

    /** Téléphone : glisser le doigt verticalement sur la moitié gauche = luminosité. */
    var brightGesture = false
    var onBrightness: ((Float) -> Unit)? = null
    private var bStartY = 0f
    private var bStartLevel = 0.5f
    private var bTracking = false
    private var bRight = false
    var onVolume: ((Float) -> Unit)? = null
    private val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
    private fun currentVolume(): Float {
        val am = audio ?: return 0.5f
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) / max.toFloat()
    }
    private var bActive = false
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop * 2

    private fun window(): android.view.Window? {
        var c = context
        while (c is android.content.ContextWrapper) { if (c is android.app.Activity) return c.window; c = c.baseContext }
        return null
    }

    private fun currentBrightness(): Float {
        val w = window()?.attributes?.screenBrightness ?: -1f
        if (w >= 0f) return w
        return try { android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f } catch (_: Exception) { 0.5f }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (swipeZap) swipe.onTouchEvent(ev)
        if (brightGesture) {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // Pas sur le haut de l'écran (barre de notifications) ni sur la moitié droite
                    bTracking = ev.y > height * 0.15f && ev.y < height * 0.9f
                    bRight = ev.x >= width / 2f
                    bActive = false; bStartY = ev.y
                }
                android.view.MotionEvent.ACTION_MOVE -> if (bTracking) {
                    val dy = bStartY - ev.y
                    if (!bActive && Math.abs(dy) > slop) {
                        bActive = true; bStartY = ev.y; bStartLevel = if (bRight) currentVolume() else currentBrightness()
                        val cancel = android.view.MotionEvent.obtain(ev).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel); cancel.recycle()
                    }
                    if (bActive) {
                        val level = (bStartLevel + (bStartY - ev.y) / (height * 0.8f)).coerceIn(0.02f, 1f)
                        if (bRight) {
                            val lv = (bStartLevel + (bStartY - ev.y) / (height * 0.8f)).coerceIn(0f, 1f)
                            audio?.let { am -> am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.round(lv * am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)), 0) }
                            onVolume?.invoke(lv)
                        } else {
                            window()?.let { w -> val a = w.attributes; a.screenBrightness = level; w.attributes = a }
                            onBrightness?.invoke(level)
                        }
                        return true
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val was = bActive
                    bTracking = false; bActive = false
                    if (was) { if (bRight) onVolume?.invoke(-1f) else onBrightness?.invoke(-1f); return true }
                }
            }
        }
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
    // Faux au départ : les infos ne s'affichent que si le lecteur montre vraiment ses commandes (et s'effacent avec elles)
    var controlsVisible by remember { mutableStateOf(false) }
    // TV : commandes maison pilotées par la télécommande
    var showUi by remember { mutableStateOf(true) }
    var uiTick by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var posMs by remember { mutableLongStateOf(0L) }
    var durMs by remember { mutableLongStateOf(0L) }
    val rootFr = remember { androidx.compose.ui.focus.FocusRequester() }
    var banner by remember { mutableStateOf<String?>(null) }
    var chanList by remember { mutableStateOf(false) }
    var subsPanel by remember { mutableStateOf(false) }
    // Panneau de choix : sous-titres ou langue audio (même panneau)
    var pickAudio by remember { mutableStateOf(false) }
    var tvMenu by remember { mutableStateOf(false) }
    val chanFr = remember { androidx.compose.ui.focus.FocusRequester() }
    val subsFr = remember { androidx.compose.ui.focus.FocusRequester() }
    val menuFr = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(chanList) { if (chanList && m.isTv) { delay(200); try { chanFr.requestFocus() } catch (_: Exception) { } } }
    LaunchedEffect(subsPanel) { if (subsPanel && m.isTv) { delay(200); try { subsFr.requestFocus() } catch (_: Exception) { } } }
    // Icônes TV : la sélection va dessus (on réessaie tant qu'elle n'y est pas) et elles s'effacent seules après 7 s sans appui
    var menuHasFocus by remember { mutableStateOf(false) }
    var menuTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tvMenu, subsPanel, chanList) {
        if (tvMenu && !subsPanel && !chanList) {
            repeat(8) {
                delay(150)
                if (menuHasFocus) return@LaunchedEffect
                try { menuFr.requestFocus() } catch (_: Exception) { }
            }
        }
    }
    LaunchedEffect(tvMenu, menuTick, subsPanel, chanList, panel) {
        if (tvMenu && !subsPanel && !chanList && !panel) { delay(7000); tvMenu = false }
    }
    // TV : quand tout est fermé (menu d'icônes, sous-titres/audio, chaînes), la télécommande revient sur la vidéo
    LaunchedEffect(tvMenu, subsPanel, chanList) {
        if (m.isTv && !tvMenu && !subsPanel && !chanList) { delay(120); try { rootFr.requestFocus() } catch (_: Exception) { } }
    }
    // Qualité automatique (direct) : si ça coupe, on passe sur une version plus légère de la même chaîne
    var down by remember { mutableStateOf<Pair<Playable, LiveStream>?>(null) }
    val stalls = remember { mutableListOf<Long>() }
    var stallStart by remember { mutableLongStateOf(0L) }
    var readyOnce by remember { mutableStateOf(false) }
    var autoNextAt by remember { mutableLongStateOf(0L) }
    // Épisode suivant : compte à rebours (OK = lancer tout de suite, Retour = annuler)
    var nextIn by remember { mutableIntStateOf(0) }
    val nextFr = remember { androidx.compose.ui.focus.FocusRequester() }
    var tracksTick by remember { mutableIntStateOf(0) }
    var brightShown by remember { mutableFloatStateOf(-1f) }
    var brightHideAt by remember { mutableLongStateOf(0L) }
    var volShown by remember { mutableFloatStateOf(-1f) }
    var volHideAt by remember { mutableLongStateOf(0L) }
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
            val s = down?.takeIf { it.first == p }?.second ?: p.list[p.index]
            Resolved(client.liveUrl(s.id, if (useHls) "m3u8" else "ts"), s.name, "En direct", null, true)
        }
        is Playable.Movie -> Resolved(client.vodUrl(p.vod.id, p.vod.ext), p.vod.name, "", "vod_${p.vod.id}", false)
        is Playable.Ep -> {
            val e = p.episodes[p.index]
            Resolved(client.episodeUrl(e.id, e.ext), p.series.name, "S${e.season} É${e.num}${com.lumo.tv.data.cleanEpisodeTitle(e.title, p.series.name).let { t -> if (t.isNotBlank()) " · " + t else "" }}", "ep_${e.id}", false)
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

    fun playNextEp() {
        val it = item as? Playable.Ep ?: return
        nextIn = 0
        if (it.index + 1 < it.episodes.size) {
            item = it.copy(index = it.index + 1, fromStart = true)
            autoNextAt = System.currentTimeMillis()
        }
    }
    LaunchedEffect(nextIn > 0) {
        if (nextIn <= 0) return@LaunchedEffect
        if (m.isTv) { delay(150); try { nextFr.requestFocus() } catch (_: Exception) { } }
        while (nextIn > 0) { delay(1000); if (nextIn > 0) { nextIn--; if (nextIn == 0) playNextEp() } }
    }
    androidx.activity.compose.BackHandler(enabled = nextIn > 0) { nextIn = 0 }
    androidx.activity.compose.BackHandler(enabled = panel) { panel = false }
    androidx.activity.compose.BackHandler(enabled = chanList) { chanList = false }
    androidx.activity.compose.BackHandler(enabled = subsPanel) { subsPanel = false }
    androidx.activity.compose.BackHandler(enabled = tvMenu && !subsPanel && !chanList && !panel) { tvMenu = false }

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

    /** Passe sur la version plus légère suivante de la chaîne en cours. Faux s'il n'y en a pas. */
    fun stepDown(): Boolean {
        val it = item as? Playable.Live ?: return false
        val cur = down?.takeIf { d -> d.first == it }?.second ?: it.list[it.index]
        val next = m.lighterVariants(cur).firstOrNull() ?: return false
        stalls.clear(); stallStart = 0L
        down = it to next
        m.showToast("Connexion faible : passage sur ${next.name}")
        return true
    }
    // Surveillance des coupures : une coupure de plus de 7 s, ou 3 coupures en 90 s → qualité inférieure
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            if (item !is Playable.Live || errorMsg != null) continue
            val now = System.currentTimeMillis()
            stalls.removeAll { now - it > 90_000 }
            val longStall = player.playbackState == Player.STATE_BUFFERING && stallStart > 0 && now - stallStart > 7000
            if (longStall || stalls.size >= 3) stepDown()
        }
    }

    var loadedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(item, useHls, down) {
        readyOnce = false; stallStart = 0L; stalls.clear()
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
            override fun onIsPlayingChanged(isPlaying: Boolean) { m.updatePip(player.playWhenReady, m.pipCanStep) }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { tracksTick++ }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                paused = !playWhenReady
                m.updatePip(playWhenReady, m.pipCanStep)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { retries = 0; busyRetry = 0; busyCountdown = 0; busySince = 0L; readyOnce = true; stallStart = 0L }
                if (state == Player.STATE_BUFFERING && readyOnce && item is Playable.Live) {
                    val now = System.currentTimeMillis(); stallStart = now; stalls.add(now)
                }
                if (state == Player.STATE_ENDED) {
                    saveProgress(finished = true)
                    val it = item
                    if (it is Playable.Ep && it.index + 1 < it.episodes.size) nextIn = 8
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
                    errorMsg = if (System.currentTimeMillis() - autoNextAt < 60_000)
                        "Épisode suivant : ton fournisseur n'a pas encore libéré l'écran de l'épisode précédent (code $code). Il démarre tout seul dès que c'est bon, rien à faire."
                    else "Ton abonnement est déjà utilisé sur un autre écran (code $code), ou la connexion précédente n'est pas encore libérée."
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
                    // Direct : avant d'abandonner, on essaie une version plus légère de la chaîne
                    if (isLive && stepDown()) { retries = 0; return }
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
    // Reprendre le programme en cours depuis le début (chaînes avec rattrapage chez le fournisseur)
    val canStartOver = liveStream?.archive == true && cur != null
    fun startOver() {
        val st = liveStream ?: return
        val c = cur ?: return
        tvMenu = false
        player.stop()
        m.closeMediaConnections()
        m.go(Screen.Player(Playable.Replay(st, c)))
    }

    fun poke() { showUi = true; uiTick++ }
    fun zap(dir: Int): Boolean {
        val it = item
        if (it !is Playable.Live || it.list.size < 2) return false
        val n = it.list.size
        item = it.copy(index = ((it.index + dir) % n + n) % n)
        poke()
        return true
    }
    /** Série : épisode précédent / suivant sans quitter le lecteur (reprend où on s'était arrêté). */
    fun epStep(dir: Int): Boolean {
        val it = item
        if (it !is Playable.Ep) return false
        val ni = it.index + dir
        if (ni !in it.episodes.indices) { m.showToast(if (dir > 0) "Dernier épisode de la saison" else "Premier épisode de la saison"); return false }
        saveProgress()
        val e = it.episodes[ni]
        item = it.copy(index = ni, fromStart = false)
        m.showToast("S${e.season} É${e.num}")
        poke()
        return true
    }
    // Boutons du mini-lecteur : ⏮ ⏯ ⏭
    LaunchedEffect(item) {
        val it = item
        m.updatePip(player.playWhenReady, (it is Playable.Live && it.list.size > 1) || (it is Playable.Ep && it.episodes.size > 1))
    }
    LaunchedEffect(Unit) {
        m.pipEvents.collect { a ->
            when (a) {
                "toggle" -> if (player.playWhenReady) player.pause() else player.play()
                "prev" -> if (item is Playable.Live) zap(-1) else epStep(-1)
                "next" -> if (item is Playable.Live) zap(1) else epStep(1)
            }
        }
    }
    fun seekBy(ms: Long) {
        player.seekTo((player.currentPosition + ms).coerceAtLeast(0))
        posMs = player.currentPosition
        poke()
    }
    fun tvKey(e: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (!m.isTv || panel || tvMenu || subsPanel || chanList || errorMsg != null || nextIn > 0) return false
        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return false
        val live = item is Playable.Live
        return when (e.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                if (e.nativeKeyEvent.repeatCount > 0) { if (e.nativeKeyEvent.repeatCount == 4) tvMenu = true; true }
                else { if (player.playWhenReady) player.pause() else player.play(); poke(); true }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); poke(); true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); poke(); true }
            // ▲▼ : ouvre les icônes (chaînes, son, image…) ; touches CH+/CH- : chaîne suivante/précédente
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { poke(); tvMenu = true; true }
            KeyEvent.KEYCODE_CHANNEL_UP -> { if (!(live && zap(-1))) poke(); true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (!(live && zap(1))) poke(); true }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_PAGE_DOWN -> { if (!epStep(1)) poke(); true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_PAGE_UP -> { if (!epStep(-1)) poke(); true }
            // ◀▶ : en direct, chaîne précédente/suivante ; en film/série, recul/avance
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { if (live) { if (!zap(-1)) poke() } else seekBy(-10_000); true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (live) { if (!zap(1)) poke() } else seekBy(30_000); true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_INFO -> { tvMenu = true; true }
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
                    setShowSubtitleButton(false)
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
                v.brightGesture = !m.isTv
                v.onBrightness = { lvl -> if (lvl >= 0f) { brightShown = lvl; brightHideAt = Long.MAX_VALUE } else brightHideAt = System.currentTimeMillis() + 800 }
                v.onVolume = { lvl -> if (lvl >= 0f) { volShown = lvl; volHideAt = Long.MAX_VALUE } else volHideAt = System.currentTimeMillis() + 800 }
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
                    if (sub.isNotBlank()) Text(sub, color = C.Sub, fontSize = 14.sp, maxLines = 2, lineHeight = 17.sp, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                // Téléphone : boutons ronds avec icônes (Chaînes tout à droite)
                // (en direct, elles sont en bas à droite pour ne pas cacher le logo de la chaîne)
                if (!m.isTv && item !is Playable.Live) {
                    IconPill("Langue audio", onClick = { pickAudio = true; subsPanel = !subsPanel }) { drawAudioLang() }
                    IconPill("Sous-titres", onClick = { pickAudio = false; subsPanel = !subsPanel }) { drawSubtitles() }
                    IconPill("Image : " + modeNames[videoMode], active = videoMode != 0, onClick = { nextVideoMode() }) { drawAspect() }
                    IconPill("Synchro du son", active = avDelay != 0, onClick = { panel = !panel }) { drawSync() }
                }
            }
        }

        // Téléphone, en direct : icônes en bas à droite (le logo de la chaîne est souvent en haut à droite)
        if (!m.isTv && !m.pip && item is Playable.Live && uiVisible && errorMsg == null && !chanList) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (canStartOver) IconPill("Reprendre au début", onClick = { startOver() }) { drawRestart() }
                IconPill("Image : " + modeNames[videoMode], active = videoMode != 0, onClick = { nextVideoMode() }) { drawAspect() }
                IconPill("Synchro du son", active = avDelay != 0, onClick = { panel = !panel }) { drawSync() }
                if ((item as Playable.Live).list.size > 1) IconPill("Chaînes", active = chanList, onClick = { chanList = !chanList }) { drawChannels() }
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

        if (!m.pip && item is Playable.Live && m.isTv && !tvMenu && (uiVisible || banner != null)) {
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

        // Téléphone : épisode précédent / suivant (séries), mêmes boutons que pour les chaînes
        if (!m.isTv && !m.pip && !subsPanel && !panel && errorMsg == null && uiVisible && item is Playable.Ep) {
            val ep = item as Playable.Ep
            listOf(-1 to Alignment.CenterStart, 1 to Alignment.CenterEnd).forEach { (dir, al) ->
                if (ep.index + dir in ep.episodes.indices) Column(
                    Modifier.align(al).padding(horizontal = 22.dp).clickable { epStep(dir) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.size(56.dp).background(Color(0x66000000), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                        Text(if (dir < 0) "‹" else "›", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Text("É${ep.episodes[ep.index + dir].num}", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp).background(Color(0x66000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
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

        LaunchedEffect(brightHideAt) {
            if (brightHideAt != Long.MAX_VALUE && brightHideAt > 0) { delay((brightHideAt - System.currentTimeMillis()).coerceAtLeast(0)); brightShown = -1f }
        }
        if (brightShown >= 0f && !m.pip) {
            Column(
                Modifier.align(Alignment.CenterStart).padding(start = 40.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("☀", color = Color.White, fontSize = 20.sp)
                Box(Modifier.width(6.dp).height(120.dp).background(Color(0x55FFFFFF), RoundedCornerShape(3.dp)), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(brightShown).background(Color.White, RoundedCornerShape(3.dp)))
                }
                Text("${(brightShown * 100).toInt()} %", color = Color.White, fontSize = 12.sp)
            }
        }

        LaunchedEffect(volHideAt) {
            if (volHideAt != Long.MAX_VALUE && volHideAt > 0) { delay((volHideAt - System.currentTimeMillis()).coerceAtLeast(0)); volShown = -1f }
        }
        if (volShown >= 0f && !m.pip) {
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 40.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(if (volShown == 0f) "🔇" else "🔊", color = Color.White, fontSize = 18.sp)
                Box(Modifier.width(6.dp).height(120.dp).background(Color(0x55FFFFFF), RoundedCornerShape(3.dp)), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(volShown).background(Color.White, RoundedCornerShape(3.dp)))
                }
                Text("${(volShown * 100).toInt()} %", color = Color.White, fontSize = 12.sp)
            }
        }

        if (subsPanel && !m.pip) {
            tracksTick
            val tType = if (pickAudio) MC.TRACK_TYPE_AUDIO else MC.TRACK_TYPE_TEXT
            val textGroups = player.currentTracks.groups.filter { it.type == tType }
            val off = !pickAudio && (MC.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes || textGroups.none { it.isSelected })
            fun choose(g: androidx.media3.common.Tracks.Group?, i: Int) {
                val b = player.trackSelectionParameters.buildUpon().clearOverridesOfType(tType)
                if (g == null) b.setTrackTypeDisabled(tType, true)
                else b.setTrackTypeDisabled(tType, false).setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i))
                player.trackSelectionParameters = b.build()
                subsPanel = false
            }
            Box(Modifier.fillMaxSize().background(Color(0x66000000)).tapToClose { subsPanel = false })
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(300.dp).background(Color(0xF0141414)),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                item { Text(if (pickAudio) "Langue audio" else "Sous-titres", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) }
                if (!pickAudio) item {
                    Text(
                        (if (off) "✓  " else "    ") + "Aucun", color = Color.White, fontSize = 16.sp, fontWeight = if (off) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().focusRequester(subsFr).tvClickable { choose(null, 0) }.padding(horizontal = 18.dp, vertical = 14.dp)
                    )
                }
                var n = 0
                textGroups.forEach { g ->
                    for (i in 0 until g.length) {
                        val f = g.getTrackFormat(i)
                        n++
                        val langName = f.language?.takeIf { it != "und" }?.let { java.util.Locale(it).getDisplayLanguage(java.util.Locale.FRENCH).replaceFirstChar { c -> c.uppercase() } }
                        val base = f.label ?: langName ?: (if (pickAudio) "Audio $n" else "Piste $n")
                        val extra = if (pickAudio && f.channelCount >= 6) " · 5.1" else ""
                        val name = base + extra
                        val selected = !off && g.isTrackSelected(i)
                        // Audio : la sélection de la télécommande arrive directement sur la langue en cours
                        val firstAudio = pickAudio && (selected || (n == 1 && textGroups.none { it.isSelected }))
                        item {
                            Text(
                                (if (selected) "✓  " else "    ") + name, color = if (selected) Color.White else C.Sub, fontSize = 16.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.fillMaxWidth().then(if (firstAudio) Modifier.focusRequester(subsFr) else Modifier)
                                    .tvClickable { choose(g, i) }.padding(horizontal = 18.dp, vertical = 14.dp)
                            )
                        }
                    }
                }
                if (pickAudio && n <= 1) item { Text("Une seule langue audio est disponible pour ce programme.", color = C.Sub, fontSize = 14.sp, modifier = Modifier.padding(18.dp)) }
                if (textGroups.isEmpty() && !pickAudio) item { Text("Pas de sous-titres pour ce film. S'ils restent affichés, ils sont incrustés dans l'image et ne peuvent pas être enlevés.", color = C.Sub, fontSize = 14.sp, modifier = Modifier.padding(18.dp)) }
            }
        }

        if (chanList && !m.pip && item is Playable.Live) {
            val live = item as Playable.Live
            val listState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (live.index - 2).coerceAtLeast(0))
            Box(Modifier.fillMaxSize().background(Color(0x66000000)).tapToClose { chanList = false })
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(if (m.isTv) 420.dp else 360.dp).background(Color(0xF0141414)),
                state = listState,
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(live.list.size) { i ->
                    val ch = live.list[i]
                    val sel = i == live.index
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (sel) Color(0x33E11D2E) else Color.Transparent)
                            .then(if (sel) Modifier.focusRequester(chanFr) else Modifier)
                            .tvClickable { item = live.copy(index = i); chanList = false }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("${i + 1}", color = C.Dim, fontSize = 12.sp, modifier = Modifier.width(28.dp))
                        if (ch.icon.isNotBlank()) coil.compose.AsyncImage(model = ch.icon, contentDescription = null, modifier = Modifier.size(width = 44.dp, height = 28.dp))
                        // Nom + programme en cours
                        LaunchedEffect(ch.id) { m.loadNow(ch.id) }
                        Column(Modifier.weight(1f)) {
                            Text(ch.name, color = if (sel) Color.White else C.Sub, fontSize = 14.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                            val now = m.nowPlaying[ch.id]
                            if (!now.isNullOrBlank()) Text(now, color = if (sel) Color(0xFFE0E0E0) else C.Dim, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
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

        // TV : barre d'icônes (mêmes icônes que sur le téléphone), ouverte avec Menu ou un appui long sur OK
        if (m.isTv && tvMenu && !panel && !subsPanel && !chanList && !m.pip) {
            val ep = item as? Playable.Ep
            val live = item as? Playable.Live
            Row(
                Modifier.align(Alignment.BottomEnd).padding(bottom = 28.dp, end = 32.dp)
                    // ▲ ▼ ferment les icônes (au lieu de perdre la sélection)
                    .onFocusChanged { menuHasFocus = it.hasFocus }
                    .onPreviewKeyEvent { e ->
                        menuTick++
                        val c = e.nativeKeyEvent.keyCode
                        if ((c == KeyEvent.KEYCODE_DPAD_UP || c == KeyEvent.KEYCODE_DPAD_DOWN) && e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) { tvMenu = false; true }
                        else false
                    }
                    .background(Color(0xE6141414), RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                var first = true
                @Composable fun entry(label: String, active: Boolean = false, onClick: () -> Unit, icon: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
                    val fr = if (first) menuFr else null
                    first = false
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconPill(label, active = active, requester = fr, size = 56.dp, onClick = onClick, icon = icon)
                        Text(label, color = C.Sub, fontSize = 12.sp)
                    }
                }
                if (ep != null) {
                    if (ep.index > 0) entry("Épisode préc.", onClick = { epStep(-1); tvMenu = false }) { drawArrow(-1) }
                    if (ep.index < ep.episodes.lastIndex) entry("Épisode suiv.", onClick = { epStep(1); tvMenu = false }) { drawArrow(1) }
                }
                if (canStartOver) entry("Au début", onClick = { startOver() }) { drawRestart() }
                entry("Langue audio", onClick = { pickAudio = true; tvMenu = false; subsPanel = true }) { drawAudioLang() }
                if (live == null) entry("Sous-titres", onClick = { pickAudio = false; tvMenu = false; subsPanel = true }) { drawSubtitles() }
                entry(modeNames[videoMode], active = videoMode != 0, onClick = { nextVideoMode() }) { drawAspect() }
                entry("Synchro son", active = avDelay != 0, onClick = { tvMenu = false; panel = true }) { drawSync() }
                if (live != null && live.list.size > 1) entry("Chaînes", onClick = { tvMenu = false; chanList = true }) { drawChannels() }
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
                Text("Mémorisé pour cette chaîne, ce film ou cette série.", color = C.Dim, fontSize = 12.sp)
            }
        }

        // Épisode suivant : compte à rebours en haut à droite
        if (nextIn > 0 && !m.pip) {
            val nx = (item as? Playable.Ep)?.let { it.episodes.getOrNull(it.index + 1) }
            Column(
                Modifier.align(Alignment.TopEnd).padding(24.dp).background(Color(0xEE141418), RoundedCornerShape(14.dp)).padding(18.dp).widthIn(max = 340.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Épisode suivant dans $nextIn s", color = C.Sub, fontSize = 14.sp)
                if (nx != null) Text("S${nx.season} É${nx.num}" + com.lumo.tv.data.cleanEpisodeTitle(nx.title, (item as Playable.Ep).series.name).let { t -> if (t.isNotBlank()) " · $t" else "" },
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LumoButton("Lancer", requester = nextFr) { playNextEp() }
                    LumoButton("Annuler", primary = false) { nextIn = 0 }
                }
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


/** Bouton rond du lecteur (téléphone) avec une icône dessinée ; petit chiffre en option. */
@Composable
private fun IconPill(
    label: String, active: Boolean = false, badge: String? = null,
    requester: androidx.compose.ui.focus.FocusRequester? = null, size: androidx.compose.ui.unit.Dp = 46.dp,
    onClick: () -> Unit, icon: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit
) {
    com.lumo.tv.ui.FocusBox(shape = androidx.compose.foundation.shape.CircleShape, scale = 1.12f, requester = requester, onClick = onClick) { _ ->
        Box(
            Modifier.size(size).background(if (active) C.Red.copy(alpha = 0.85f) else Color(0x55FFFFFF), androidx.compose.foundation.shape.CircleShape)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(size * 0.52f)) { icon() }
            if (badge != null) Text(
                badge, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp)
            )
        }
    }
}


private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawChannels() {
    val w = size.width; val h = size.height
    for (i in 0..2) {
        val y = h * (0.25f + i * 0.25f)
        drawCircle(Color.White, radius = w * 0.06f, center = androidx.compose.ui.geometry.Offset(w * 0.14f, y))
        drawLine(Color.White, androidx.compose.ui.geometry.Offset(w * 0.32f, y), androidx.compose.ui.geometry.Offset(w * 0.9f, y), strokeWidth = w * 0.09f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTimer() {
    val w = size.width
    val c = androidx.compose.ui.geometry.Offset(w / 2, size.height * 0.55f)
    drawCircle(Color.White, radius = w * 0.38f, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.09f))
    drawLine(Color.White, c, androidx.compose.ui.geometry.Offset(c.x, c.y - w * 0.22f), strokeWidth = w * 0.09f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(Color.White, c, androidx.compose.ui.geometry.Offset(c.x + w * 0.15f, c.y + w * 0.08f), strokeWidth = w * 0.09f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(Color.White, androidx.compose.ui.geometry.Offset(w * 0.38f, size.height * 0.06f), androidx.compose.ui.geometry.Offset(w * 0.62f, size.height * 0.06f), strokeWidth = w * 0.09f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
}

/** Synchro du son : barres d'égaliseur. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSync() {
    val w = size.width; val h = size.height
    val hs = listOf(0.35f, 0.75f, 0.5f, 0.9f, 0.45f)
    hs.forEachIndexed { i, f ->
        val x = w * (0.12f + i * 0.19f)
        drawLine(Color.White, androidx.compose.ui.geometry.Offset(x, h * (0.5f - f / 2)), androidx.compose.ui.geometry.Offset(x, h * (0.5f + f / 2)), strokeWidth = w * 0.1f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** Format de l'image : cadre avec coins. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAspect() {
    val w = size.width; val h = size.height
    val sw = w * 0.09f
    drawRoundRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(w * 0.05f, h * 0.2f), size = androidx.compose.ui.geometry.Size(w * 0.9f, h * 0.6f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.1f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
    // flèches dans les coins
    drawLine(Color.White, androidx.compose.ui.geometry.Offset(w * 0.25f, h * 0.36f), androidx.compose.ui.geometry.Offset(w * 0.25f, h * 0.5f), strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(Color.White, androidx.compose.ui.geometry.Offset(w * 0.25f, h * 0.36f), androidx.compose.ui.geometry.Offset(w * 0.4f, h * 0.36f), strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(Color.White, androidx.compose.ui.geometry.Offset(w * 0.75f, h * 0.64f), androidx.compose.ui.geometry.Offset(w * 0.75f, h * 0.5f), strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(Color.White, androidx.compose.ui.geometry.Offset(w * 0.75f, h * 0.64f), androidx.compose.ui.geometry.Offset(w * 0.6f, h * 0.64f), strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
}

/** Sous-titres : bulle rectangulaire avec deux lignes de texte. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSubtitles() {
    val w = size.width; val h = size.height
    val sw = w * 0.09f
    drawRoundRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(w * 0.05f, h * 0.18f), size = androidx.compose.ui.geometry.Size(w * 0.9f, h * 0.64f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.1f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
    drawLine(Color.White, androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.45f), androidx.compose.ui.geometry.Offset(w * 0.78f, h * 0.45f), strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(Color.White, androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.62f), androidx.compose.ui.geometry.Offset(w * 0.6f, h * 0.62f), strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
}


/** Reprendre au début : flèche circulaire vers l'arrière avec une barre verticale (début). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRestart() {
    val w = size.width; val h = size.height
    val sw = w * 0.09f
    drawArc(Color.White, startAngle = 200f, sweepAngle = 280f, useCenter = false,
        topLeft = androidx.compose.ui.geometry.Offset(w * 0.2f, h * 0.2f), size = androidx.compose.ui.geometry.Size(w * 0.6f, h * 0.6f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    val tip = androidx.compose.ui.graphics.Path().apply {
        moveTo(w * 0.12f, h * 0.30f); lineTo(w * 0.30f, h * 0.24f); lineTo(w * 0.24f, h * 0.44f); close()
    }
    drawPath(tip, Color.White)
    drawLine(Color.White, androidx.compose.ui.geometry.Offset(w * 0.44f, h * 0.38f), androidx.compose.ui.geometry.Offset(w * 0.44f, h * 0.62f), strokeWidth = sw * 0.8f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    val tri = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.62f, h * 0.38f); lineTo(w * 0.48f, h * 0.50f); lineTo(w * 0.62f, h * 0.62f); close() }
    drawPath(tri, Color.White)
}

/** Langue audio : bulle de dialogue avec une onde sonore. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAudioLang() {
    val w = size.width; val h = size.height
    val sw = w * 0.09f
    val st = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(w * 0.15f, h * 0.15f); lineTo(w * 0.85f, h * 0.15f); lineTo(w * 0.85f, h * 0.68f)
        lineTo(w * 0.45f, h * 0.68f); lineTo(w * 0.25f, h * 0.88f); lineTo(w * 0.27f, h * 0.68f); lineTo(w * 0.15f, h * 0.68f); close()
    }
    drawPath(path, Color.White, style = st)
    val xs = listOf(0.33f to 0.12f, 0.44f to 0.2f, 0.56f to 0.14f, 0.67f to 0.08f)
    for ((x, hh) in xs) drawLine(Color.White, androidx.compose.ui.geometry.Offset(w * x, h * (0.415f - hh)), androidx.compose.ui.geometry.Offset(w * x, h * (0.415f + hh)), strokeWidth = sw * 0.8f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
}

/** Ligne cliquable qui se surligne quand elle a le focus (télécommande). */
private fun Modifier.tvClickable(onClick: () -> Unit): Modifier = composed {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    this.background(if (focused) Color(0x44FFFFFF) else Color.Transparent)
        .clickable(interactionSource = src, indication = null, onClick = onClick)
}

/** Voile sombre derrière un panneau : se ferme au toucher, sans prendre le focus de la télécommande. */
private fun Modifier.tapToClose(onClose: () -> Unit): Modifier =
    this.pointerInput(Unit) { detectTapGestures { onClose() } }

/** Flèche gauche (-1) ou droite (1). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrow(dir: Int) {
    val w = size.width; val h = size.height
    val sw = w * 0.11f
    val tipX = if (dir > 0) w * 0.68f else w * 0.32f
    val backX = if (dir > 0) w * 0.36f else w * 0.64f
    drawLine(Color.White, androidx.compose.ui.geometry.Offset(backX, h * 0.2f), androidx.compose.ui.geometry.Offset(tipX, h * 0.5f), strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(Color.White, androidx.compose.ui.geometry.Offset(tipX, h * 0.5f), androidx.compose.ui.geometry.Offset(backX, h * 0.8f), strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
}

/** Icône ronde d'affichage seul (TV, hors menu). */
@Composable
private fun StaticPill(active: Boolean = false, icon: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    Box(Modifier.size(42.dp).background(if (active) C.Red.copy(alpha = 0.85f) else Color(0x55FFFFFF), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(22.dp)) { icon() }
    }
}
