package com.lumo.tv.ui

import androidx.compose.ui.graphics.graphicsLayer

import java.util.Locale

import java.util.Date

import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.horizontalScroll

import androidx.compose.ui.graphics.Brush

import androidx.compose.ui.focus.onFocusChanged

import androidx.compose.ui.draw.clip

import androidx.compose.foundation.clickable

import androidx.compose.foundation.Canvas

import androidx.compose.ui.focus.focusRestorer

import androidx.compose.ui.focus.focusRequester

import androidx.compose.foundation.focusGroup

import androidx.compose.ui.focus.focusProperties

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lumo.tv.AppModel
import com.lumo.tv.Playable
import com.lumo.tv.Screen
import com.lumo.tv.data.EpgEntry
import com.lumo.tv.data.LiveStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private const val FAV = "__fav"
private const val ALL = "__all"
private const val REPLAY = "__replay"
private const val AUTO = "__auto"
private const val GUIDE = "__guide"
private const val SPORT = "__sport"

@Composable
fun LiveScreen(m: AppModel) {
    if (m.live.isEmpty()) {
        if (m.loading) Loading("Chargement des chaînes…") else Empty("Aucune chaîne")
        return
    }
    val cats = m.visibleCats("live")
    val favs = run { m.store.version; m.store.favs(m.pid, "live") }
    // On revient sur la dernière catégorie ouverte (au retour d'une chaîne)
    var sel by remember { mutableStateOf(m.lastCat["live"]?.takeIf { id -> id == FAV || id == AUTO || id == REPLAY || cats.any { it.id == id } } ?: if (favs.isNotEmpty()) FAV else (cats.firstOrNull()?.id ?: ALL)) }
    LaunchedEffect(sel) { m.lastCat["live"] = sel }
    val visibleIds = remember(cats) { cats.map { it.id }.toSet() }
    val sortMode = m.liveSort
    // Dans « Favoris », une chaîne retirée reste affichée (étoile grise) jusqu'à ce qu'on change de catégorie :
    // le curseur ne saute pas, et on peut en retirer plusieurs d'affilée ou se rattraper.
    var favShown by remember(sel) { mutableStateOf(favs.toSet()) }
    LaunchedEffect(favs) { if (sel == FAV) favShown = favShown + favs }
    val autos = run { m.store.version; m.store.favs(m.pid, "auto") }
    val list = remember(sel, m.live, visibleIds, favShown, sortMode, autos) {
        val raw = when (sel) {
            FAV -> m.live.filter { it.id in favShown }
            AUTO -> m.live.filter { it.id in autos }
            ALL -> m.live.filter { it.catId in visibleIds }
            REPLAY -> m.live.filter { it.archive && it.catId in visibleIds }
            else -> m.live.filter { it.catId == sel }
        }
        m.sortLive(raw, sortMode)
    }
    val sortLabels = linkedMapOf("tnt" to "Ordre TNT", "watched" to "Plus regardées", "az" to "A-Z", "num" to "Numéro")
    val nextSort = { val keys = sortLabels.keys.toList(); m.setLiveSort(keys[(keys.indexOf(sortMode) + 1) % keys.size]) }
    val sortLabel = "⇅ " + (sortLabels[sortMode] ?: "Ordre TNT")
    val hasReplay = remember(m.live, visibleIds) { m.live.any { it.archive && it.catId in visibleIds } }
    // Téléphone : liste « Voiture » (chaînes légères pour Fermata Auto)
    val catItems = listOf(GUIDE to "📅 Guide TV", SPORT to "⚽ Sport", FAV to "★ Favoris") + (if (!m.isTv) listOf(AUTO to "🚗 Voiture") else emptyList()) + (if (hasReplay) listOf(REPLAY to "⟲ Rattrapage") else emptyList()) + cats.map { it.id to it.name }
    // « Guide TV » : ouvre la grille (favoris, sinon la catégorie en cours) puis revient sur la catégorie d'avant
    var prevSel by remember { mutableStateOf(sel) }
    LaunchedEffect(sel) {
        if (sel == SPORT) { sel = prevSel; m.go(Screen.Sport); return@LaunchedEffect }
        if (sel == GUIDE) {
            val base = when (prevSel) {
                FAV, GUIDE -> m.live.filter { it.id in favs }
                AUTO -> m.live.filter { it.id in m.store.favs(m.pid, "auto") }
                ALL, REPLAY -> m.live.filter { it.catId in visibleIds }
                else -> m.live.filter { it.catId == prevSel }
            }.ifEmpty { m.live.filter { it.catId == cats.firstOrNull()?.id } }
            val title = catItems.firstOrNull { it.first == prevSel }?.second ?: "Guide TV"
            sel = prevSel
            m.go(Screen.TvGuide(m.sortLive(base, m.liveSort).take(80), title))
        } else prevSel = sel
    }

    if (m.isTv) {
        // En descendant depuis les onglets, on arrive d'abord sur les chaînes (les catégories sont à gauche)
        val catFr = remember { androidx.compose.ui.focus.FocusRequester() }
        val chanFr = remember { androidx.compose.ui.focus.FocusRequester() }
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        Row(
            Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 8.dp)
                .focusProperties { enter = { if (list.isEmpty()) catFr else chanFr } }.focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
            LazyColumn(Modifier.width(175.dp).fillMaxHeight().focusRequester(catFr).focusGroup(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                item(key = "__sort") { Chip(sortLabel, false) { nextSort() } }
                items(catItems, key = { it.first }) { (id, name) ->
                    FocusBox(modifier = Modifier.fillMaxWidth(), scale = 1.03f, onClick = { sel = id }) { f ->
                        Text(
                            name,
                            color = if (sel == id || f) Color.White else C.Sub,
                            fontWeight = if (sel == id) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().background(if (sel == id) Color(0x1FFFFFFF) else Color.Transparent).padding(horizontal = 14.dp, vertical = 11.dp)
                        )
                    }
                }
            }
            // Aperçu à droite (grand) : la chaîne sélectionnée, son programme et la suite
            var focusedCh by remember(sel) { mutableStateOf<LiveStream?>(null) }
            @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
            ChannelList(m, list, Modifier.width(270.dp).focusRequester(chanFr).focusGroup()) { focusedCh = it }
            // Aperçu : toute la place restante (plus grand)
            ChannelPreview(m, focusedCh ?: list.firstOrNull(), Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        // Téléphone : les catégories en bas, près du pouce
        Column(Modifier.fillMaxSize()) {
            if (sel == AUTO) AutoHeader(m, autos.size)
            if (sel == AUTO && list.isEmpty()) Box(Modifier.weight(1f)) {
                Empty("Ajoute ici des chaînes légères (versions SD/LQ/HD plutôt que 4K) avec l'icône voiture, ou touche « Versions légères de mes favoris ». Puis exporte-les depuis Réglages → Exporter en liste M3U.")
            } else
            ChannelList(m, list, Modifier.weight(1f).padding(horizontal = 12.dp))
            LazyRow(Modifier.fillMaxWidth().background(Color(0xFF0E0E0E)), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "__sort") { Chip(sortLabel, false) { nextSort() } }
                items(catItems, key = { it.first }) { (id, name) -> Chip(name, sel == id) { sel = id } }
            }
        }
    }
}

@Composable
private fun ChannelList(m: AppModel, list: List<LiveStream>, modifier: Modifier, onFocus: ((LiveStream) -> Unit)? = null) {
    if (list.isEmpty()) { Box(modifier) { Empty("Rien ici pour l'instant. Ajoute des chaînes en favori avec l'étoile, ou en restant appuyé sur une chaîne.") }; return }
    LazyColumn(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(list, key = { _, s -> s.id }) { i, s -> ChannelRow(m, s, list, i, onFocus) }
    }
}

@Composable
private fun ChannelRow(m: AppModel, s: LiveStream, list: List<LiveStream>, index: Int, onFocus: ((LiveStream) -> Unit)? = null) {
    LaunchedEffect(s.id) { delay(250); m.loadNow(s.id) }
    val fav = m.isFav("live", s.id)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusBox(
            modifier = Modifier.weight(1f).onFocusChanged { if (it.hasFocus) onFocus?.invoke(s) }, scale = 1.02f,
            onLongClick = { m.toggleFav("live", s.id); m.showToast(if (fav) "Retirée des favoris" else "Ajoutée aux favoris ★") },
            onClick = { if (m.isTv) m.previewReleasedAt = System.currentTimeMillis(); m.go(Screen.Player(Playable.Live(list, index))) }
        ) { f ->
            Row(
                Modifier.fillMaxWidth().background(if (f) Color(0xFF2A2A2A) else C.Card).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.size(52.dp).background(Color(0xFF1F1F1F), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    Text(s.name.take(2).uppercase(), color = C.Dim, fontWeight = FontWeight.Bold)
                    if (s.icon.isNotBlank()) AsyncImage(model = s.icon, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(s.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (s.archive) Text("REPLAY", color = C.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    val now = m.nowPlaying[s.id]
                    if (!now.isNullOrBlank()) Text(now, color = C.Sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        IconFocus(Icons.Default.Star, if (fav) "Retirer des favoris" else "Ajouter aux favoris", tint = if (fav) Color(0xFFF2B33D) else Color(0xFF5A5A5A)) {
            m.toggleFav("live", s.id)
        }
        if (!m.isTv) {
            val car = run { m.store.version; s.id in m.store.favs(m.pid, "auto") }
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable {
                    m.toggleFav("auto", s.id); m.showToast(if (car) "Retirée de « Voiture »" else "Ajoutée à « Voiture » 🚗")
                },
                contentAlignment = Alignment.Center
            ) { Canvas(Modifier.size(24.dp)) { drawCar(if (car) Color(0xFF4FC3F7) else Color(0xFF5A5A5A)) } }
        }
        if (s.archive) {
            IconFocus(Icons.Default.DateRange, "Rattrapage") { m.go(Screen.ChannelGuide(s, list)) }
        } else {
            Spacer(Modifier.size(44.dp))
        }
    }
}

private val hourFmt = SimpleDateFormat("HH:mm", Locale.FRANCE)
private val dayFmt = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE)

@Composable
fun ChannelGuideScreen(m: AppModel, s: LiveStream, list: List<LiveStream>) {
    var epg by remember(s.id) { mutableStateOf<List<EpgEntry>?>(null) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(s.id) {
        epg = withContext(Dispatchers.IO) {
            try { m.client?.epgFor(s, full = true) ?: emptyList() } catch (_: Exception) { error = true; emptyList() }
        }
    }
    val now = System.currentTimeMillis()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pad = if (m.isTv) 48.dp else 16.dp
    Column(Modifier.fillMaxSize().padding(horizontal = pad, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (s.icon.isNotBlank()) AsyncImage(model = s.icon, contentDescription = null, modifier = Modifier.size(64.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(if (s.archive) "Rattrapage disponible (${s.archiveDays} jours)" else "Pas de rattrapage sur cette chaîne", color = C.Sub, fontSize = 14.sp)
            }
            LumoButton("Direct", requester = rememberAutoFocus(m.isTv), icon = { Icon(Icons.Default.PlayArrow, null, tint = Color.Black) }) {
                m.go(Screen.Player(Playable.Live(list, list.indexOfFirst { it.id == s.id }.coerceAtLeast(0))))
            }
            if (!m.isTv) SendToTvButton(m) { org.json.JSONObject().put("type", "play_live").put("id", s.id) }
        }
        val e = epg
        when {
            e == null -> Loading("Chargement du guide…")
            e.isEmpty() -> Empty(if (error) "Guide indisponible" else "Pas de programme pour cette chaîne")
            else -> {
                val minStart = now - s.archiveDays.coerceAtLeast(1) * 86_400_000L
                val shown = e.filter { it.end > minStart }
                val currentIndex = shown.indexOfFirst { it.start <= now && it.end > now }.coerceAtLeast(0)
                val state = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
                LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(shown, key = { _, it -> it.start }) { i, p ->
                      Column {
                        val prevDay = if (i > 0) dayFmt.format(Date(shown[i - 1].start)) else ""
                        val day = dayFmt.format(Date(p.start))
                        if (day != prevDay) Text(day.replaceFirstChar { it.uppercase() }, color = C.Dim, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                        val live = p.start <= now && p.end > now
                        val past = p.end <= now
                        val canReplay = past && s.archive && (p.hasArchive || p.start > now - s.archiveDays * 86_400_000L)
                        FocusBox(modifier = Modifier.fillMaxWidth(), scale = 1.02f, onClick = {
                            when {
                                live -> m.go(Screen.Player(Playable.Live(list, list.indexOfFirst { it.id == s.id }.coerceAtLeast(0))))
                                canReplay -> m.go(Screen.Player(Playable.Replay(s, p)))
                                !past && !m.isTv -> { askNotificationPermission(ctx); m.toggleReminder(s, p) }
                                else -> m.showToast(if (past) "Pas disponible en rattrapage" else "Pas encore diffusé")
                            }
                        }) { f ->
                            Row(
                                Modifier.fillMaxWidth().background(if (f) Color(0xFF2A2A2A) else if (live) Color(0xFF3A1216) else C.Card).padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Text("${hourFmt.format(Date(p.start))} – ${hourFmt.format(Date(p.end))}", color = C.Dim, fontSize = 13.sp, modifier = Modifier.width(110.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.title, color = if (past && !canReplay) C.Sub else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (p.desc.isNotBlank() && (f || live)) Text(p.desc, color = C.Sub, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                when {
                                    !past && !live && m.hasReminder(s.id, p.start) -> Text("Rappel ✓", color = C.Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    !past && !live && !m.isTv -> Text("Me rappeler", color = C.Dim, fontSize = 12.sp)
                                    live -> Text("EN DIRECT", color = C.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    canReplay -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Default.Refresh, null, tint = C.Red, modifier = Modifier.size(16.dp))
                                        Text("Revoir", color = C.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                      }
                    }
                }
            }
        }
    }
}

fun askNotificationPermission(ctx: android.content.Context) {
    val act = ctx as? android.app.Activity ?: return
    if (android.os.Build.VERSION.SDK_INT >= 33 &&
        androidx.core.content.ContextCompat.checkSelfPermission(act, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        androidx.core.app.ActivityCompat.requestPermissions(act, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
    }
}


/** En-tête de la liste « Voiture » : remplissage automatique avec les versions légères des favoris. */
@Composable
private fun AutoHeader(m: AppModel, count: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("$count chaîne${if (count > 1) "s" else ""} pour la voiture", color = C.Sub, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Chip("⚡ Versions légères de mes favoris", false) {
            val n = m.fillAutoFromFavs()
            m.showToast(if (n > 0) "$n chaîne${if (n > 1) "s" else ""} ajoutée${if (n > 1) "s" else ""}" else "Rien à ajouter (ajoute d'abord des favoris ★)")
        }
    }
}

/** Petite voiture vue de côté. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCar(c: Color) {
    val w = size.width; val h = size.height
    val body = androidx.compose.ui.graphics.Path().apply {
        moveTo(w * 0.06f, h * 0.68f); lineTo(w * 0.06f, h * 0.52f); lineTo(w * 0.22f, h * 0.48f)
        lineTo(w * 0.34f, h * 0.30f); lineTo(w * 0.66f, h * 0.30f); lineTo(w * 0.80f, h * 0.48f)
        lineTo(w * 0.94f, h * 0.53f); lineTo(w * 0.94f, h * 0.68f); close()
    }
    drawPath(body, c)
    drawCircle(Color(0xFF151515), w * 0.11f, androidx.compose.ui.geometry.Offset(w * 0.27f, h * 0.70f))
    drawCircle(Color(0xFF151515), w * 0.11f, androidx.compose.ui.geometry.Offset(w * 0.73f, h * 0.70f))
    drawCircle(c, w * 0.075f, androidx.compose.ui.geometry.Offset(w * 0.27f, h * 0.70f))
    drawCircle(c, w * 0.075f, androidx.compose.ui.geometry.Offset(w * 0.73f, h * 0.70f))
}


/** TV : grand aperçu de la chaîne sélectionnée (logo, programme en cours avec avancement, description, programme suivant). */
@Composable
private fun ChannelPreview(m: AppModel, s: LiveStream?, modifier: Modifier) {
    if (s == null) { Box(modifier); return }
    var epg by remember(s.id) { mutableStateOf<List<EpgEntry>>(emptyList()) }
    LaunchedEffect(s.id) {
        delay(300)
        epg = withContext(Dispatchers.IO) { try { m.client?.epgFor(s, full = false) ?: emptyList() } catch (_: Exception) { emptyList() } }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val cur = epg.firstOrNull { it.start <= now && it.end > now }
    val next = epg.firstOrNull { it.start >= (cur?.end ?: now) }
    Column(modifier.padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF1B2433), Color(0xFF0C0F16)))),
            contentAlignment = Alignment.Center
        ) {
            if (s.icon.isNotBlank()) AsyncImage(model = s.icon, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(0.45f))
            else Text(s.name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            // Vraie image de la chaîne (avec le son) quand on reste un instant dessus
            LivePreviewVideo(m, s)
            Text("EN DIRECT", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp).background(C.Red, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp))
        }
        Text(s.name, color = C.Sub, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (cur != null) {
            Text(cur.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 26.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${hourFmt.format(java.util.Date(cur.start))} – ${hourFmt.format(java.util.Date(cur.end))}", color = C.Sub, fontSize = 14.sp)
                val frac = ((now - cur.start).toFloat() / (cur.end - cur.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(Modifier.weight(1f).height(4.dp).background(Color(0x33FFFFFF), RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(C.Red, RoundedCornerShape(2.dp)))
                }
            }
            if (cur.desc.isNotBlank()) Text(cur.desc, color = Color(0xFFCFCFCF), fontSize = 14.sp, lineHeight = 20.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        } else Text(m.nowPlaying[s.id] ?: "Programme non disponible", color = Color.White, fontSize = 18.sp, maxLines = 2)
        if (next != null) Text("Ensuite · ${hourFmt.format(java.util.Date(next.start))}  ${next.title}", color = C.Sub, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


/** Aperçu vidéo du Direct : démarre vite sur la chaîne sélectionnée ; le logo reste affiché jusqu'à la 1re image. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun LivePreviewVideo(m: AppModel, s: LiveStream) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var shown by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val player = remember {
        val ds = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(m.mediaHttp).setUserAgent(com.lumo.tv.data.USER_AGENT)
        // Petit tampon : l'image démarre plus vite (c'est un aperçu)
        val lc = androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(2000, 15000, 400, 1000).build()
        androidx.media3.exoplayer.ExoPlayer.Builder(context)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(ds))
            .setLoadControl(lc)
            .build().apply { playWhenReady = true }
    }
    DisposableEffect(Unit) {
        val l = object : androidx.media3.common.Player.Listener {
            override fun onRenderedFirstFrame() { shown = true; loading = false }
        }
        player.addListener(l)
        onDispose {
            player.removeListener(l)
            player.release()
            m.previewReleasedAt = System.currentTimeMillis()
            m.closeMediaConnections()
        }
    }
    LaunchedEffect(s.id, m.appStopped) {
        shown = false
        player.stop()
        if (m.appStopped) return@LaunchedEffect
        delay(650)
        val c = m.client ?: return@LaunchedEffect
        val fmt = if (m.store.liveFormat == "m3u8") "m3u8" else "ts"
        val mi = androidx.media3.common.MediaItem.Builder().setUri(c.liveUrl(s.id, fmt)).apply {
            if (fmt == "m3u8") setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
        }.build()
        loading = true
        player.setMediaItem(mi)
        player.prepare()
    }
    // TextureView : invisible tant qu'aucune image n'est arrivée (plus d'écran noir, le logo reste visible)
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx -> android.view.TextureView(ctx).also { player.setVideoTextureView(it) } },
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (shown) 1f else 0f }
    )
    if (loading && !shown) androidx.compose.material3.CircularProgressIndicator(
        color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp).offset(y = 60.dp)
    )
}

// ---------------- Guide TV en grille ----------------

/** Grille chaînes × heures : programme en cours = regarder, passé (rattrapage) = revoir, à venir = rappel. */
@Composable
fun GuideGridScreen(m: AppModel, list: List<LiveStream>, title: String) {
    val now = remember { System.currentTimeMillis() }
    // Fenêtre : 30 min avant maintenant → 8 h après (calée sur la demi-heure)
    val start = remember { (now - 30 * 60_000L) / (30 * 60_000L) * (30 * 60_000L) }
    val end = start + 8 * 3600_000L
    val dpPerMin = if (m.isTv) 4.5f else 3.2f
    fun w(ms: Long) = (ms / 60_000f * dpPerMin).dp
    val chanW = if (m.isTv) 150.dp else 96.dp
    val rowH = if (m.isTv) 64.dp else 58.dp
    val hScroll = rememberScrollState()
    val epg = remember { mutableStateMapOf<String, List<EpgEntry>>() }
    var clock by remember { mutableLongStateOf(now) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); clock = System.currentTimeMillis() } }
    // Défilement horizontal calé sur « maintenant » au départ
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(Unit) { with(density) { hScroll.scrollTo((w(now - start - 15 * 60_000L)).roundToPx().coerceAtLeast(0)) } }
    val hhmm = remember { SimpleDateFormat("HH:mm", Locale.FRANCE) }
    val firstFr = remember { androidx.compose.ui.focus.FocusRequester() }

    Column(Modifier.fillMaxSize().padding(top = if (m.isTv) 20.dp else 8.dp)) {
        Row(Modifier.padding(horizontal = if (m.isTv) 40.dp else 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Guide TV", color = Color.White, fontSize = if (m.isTv) 26.sp else 22.sp, fontWeight = FontWeight.Bold)
            Text("  ·  " + title.removePrefix("★ ").removePrefix("📅 "), color = C.Sub, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Frise des heures
        Row(Modifier.fillMaxWidth().height(28.dp)) {
            Spacer(Modifier.width(chanW + (if (m.isTv) 40.dp else 8.dp)))
            Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
                Box(Modifier.width(w(end - start)).fillMaxHeight()) {
                    var t = start
                    while (t < end) {
                        Text(hhmm.format(Date(t)), color = C.Sub, fontSize = 13.sp, modifier = Modifier.offset(x = w(t - start) + 4.dp, y = 4.dp))
                        t += 30 * 60_000L
                    }
                    Box(Modifier.offset(x = w(clock - start)).width(2.dp).fillMaxHeight().background(C.Red))
                }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
                LaunchedEffect(s.id) {
                    if (!epg.containsKey(s.id)) epg[s.id] = withContext(Dispatchers.IO) { try { m.client?.epgWindow(s) ?: emptyList() } catch (_: Exception) { emptyList() } }
                }
                Row(Modifier.fillMaxWidth().height(rowH).padding(start = if (m.isTv) 40.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Chaîne (logo + nom)
                    Row(Modifier.width(chanW).fillMaxHeight().background(C.Card, RoundedCornerShape(8.dp)).padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(rowH - 22.dp), contentAlignment = Alignment.Center) {
                            if (s.icon.isNotBlank()) AsyncImage(model = s.icon, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                            else Text(s.name.take(3), color = C.Sub, fontSize = 11.sp)
                        }
                        if (m.isTv) Text(s.name.substringAfter("|").trim(), color = Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
                    }
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
                        Box(Modifier.width(w(end - start)).fillMaxHeight()) {
                            val progs = epg[s.id]
                            if (progs == null) Text("…", color = C.Dim, modifier = Modifier.padding(start = w(now - start), top = 18.dp))
                            else if (progs.none { it.end > start && it.start < end }) {
                                // Pas de guide : une seule case « En direct »
                                GuideCell(m, "En direct · ${s.name.substringAfter("|").trim()}", "", Modifier.offset(x = w((now - start).coerceAtLeast(0))).width(w(2 * 3600_000L)), live = true,
                                    requester = if (i == 0) firstFr else null) { openLive(m, list, s) }
                            } else progs.filter { it.end > start && it.start < end }.forEach { p ->
                                val x0 = (p.start - start).coerceAtLeast(0)
                                val x1 = (p.end - start).coerceAtMost(end - start)
                                val isNow = p.start <= clock && p.end > clock
                                val past = p.end <= clock
                                val reminder = m.hasReminder(s.id, p.start)
                                GuideCell(
                                    m, p.title,
                                    when { isNow -> "En cours · jusqu'à ${hhmm.format(Date(p.end))}"; past -> if (s.archive) "Revoir" else hhmm.format(Date(p.start)); else -> hhmm.format(Date(p.start)) + if (reminder) "  🔔" else "" },
                                    Modifier.offset(x = w(x0)).width((w(x1 - x0) - 3.dp).coerceAtLeast(24.dp)),
                                    live = isNow, dim = past && !s.archive,
                                    requester = if (i == 0 && isNow) firstFr else null
                                ) {
                                    when {
                                        isNow -> openLive(m, list, s)
                                        past && s.archive -> m.go(Screen.Player(Playable.Replay(s, p)))
                                        past -> m.showToast("Pas de rattrapage sur cette chaîne")
                                        else -> m.toggleReminder(s, p)
                                    }
                                }
                            }
                            Box(Modifier.offset(x = w(clock - start)).width(2.dp).fillMaxHeight().background(C.Red.copy(alpha = 0.8f)))
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { if (m.isTv) { delay(900); try { firstFr.requestFocus() } catch (_: Exception) { } } }
}

private fun openLive(m: AppModel, list: List<LiveStream>, s: LiveStream) {
    m.previewReleasedAt = System.currentTimeMillis()
    m.go(Screen.Player(Playable.Live(list, list.indexOfFirst { it.id == s.id }.coerceAtLeast(0))))
}

@Composable
private fun GuideCell(m: AppModel, title: String, sub: String, modifier: Modifier, live: Boolean = false, dim: Boolean = false,
                      requester: androidx.compose.ui.focus.FocusRequester? = null, onClick: () -> Unit) {
    FocusBox(modifier = modifier.fillMaxHeight(), shape = RoundedCornerShape(8.dp), scale = 1.0f, requester = requester, onClick = onClick) { f ->
        Column(
            Modifier.fillMaxSize().background(
                when { f -> Color(0xFFEDEDED); live -> Color(0xFF2B2F3A); else -> Color(0xFF1A1B20) }
            ).padding(horizontal = 8.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, color = when { f -> Color.Black; dim -> C.Dim; else -> Color.White }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, color = if (f) Color(0xFF333333) else C.Sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}


// ---------------- Sport ----------------

/** Matchs du jour : en direct (on lance la chaîne) et à venir (rappel 5 min avant). */
@Composable
fun SportScreen(m: AppModel) {
    val events by produceState<List<AppModel.SportEvent>?>(initialValue = null) { value = m.sportEvents() }
    val hhmm = remember { SimpleDateFormat("HH:mm", Locale.FRANCE) }
    val day = remember { SimpleDateFormat("EEEE", Locale.FRANCE) }
    val pad = if (m.isTv) 48.dp else 16.dp
    val now = System.currentTimeMillis()
    Column(Modifier.fillMaxSize().padding(top = if (m.isTv) 24.dp else 10.dp)) {
        Text("⚽ Sport", color = Color.White, fontSize = if (m.isTv) 28.sp else 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = pad))
        Text("Matchs en direct et à venir sur les chaînes sport de ton abonnement", color = C.Sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = pad, vertical = 4.dp))
        val ev = events
        when {
            ev == null -> Loading("Recherche des matchs dans le guide…")
            ev.isEmpty() -> Empty("Aucun match trouvé dans le guide pour les prochaines 24 h.")
            else -> {
                val fr = rememberAutoFocus(m.isTv)
                LazyColumn(contentPadding = PaddingValues(horizontal = pad, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(ev, key = { _, e -> e.stream.id + e.entry.start }) { i, e ->
                        val live = e.entry.start <= now && e.entry.end > now
                        val reminder = m.hasReminder(e.stream.id, e.entry.start)
                        FocusBox(modifier = Modifier.fillMaxWidth(), scale = 1.02f, requester = if (i == 0) fr else null, onClick = {
                            if (live) {
                                val list = m.live.filter { it.catId == e.stream.catId }
                                m.go(Screen.Player(Playable.Live(list, list.indexOfFirst { it.id == e.stream.id }.coerceAtLeast(0))))
                            } else m.toggleReminder(e.stream, e.entry)
                        }) { f ->
                            Row(
                                Modifier.fillMaxWidth().background(if (f) Color(0xFF2A2A2A) else C.Card).padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Column(Modifier.width(if (m.isTv) 92.dp else 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (live) Text("EN DIRECT", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.background(C.Red, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp))
                                    else {
                                        Text(hhmm.format(Date(e.entry.start)), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        val sameDay = java.util.Calendar.getInstance().apply { timeInMillis = e.entry.start }.get(java.util.Calendar.DAY_OF_YEAR) == java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
                                        Text(if (sameDay) "aujourd'hui" else day.format(Date(e.entry.start)), color = C.Sub, fontSize = 11.sp)
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(e.entry.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(e.stream.name.substringAfter("|").trim() + if (reminder) "   🔔 rappel" else "", color = C.Sub, fontSize = 13.sp, maxLines = 1)
                                }
                                Box(Modifier.size(width = 64.dp, height = 40.dp), contentAlignment = Alignment.Center) {
                                    if (e.stream.icon.isNotBlank()) AsyncImage(model = e.stream.icon, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
