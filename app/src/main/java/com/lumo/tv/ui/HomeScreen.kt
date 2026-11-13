package com.lumo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lumo.tv.AppModel
import com.lumo.tv.Screen
import com.lumo.tv.data.Progress
import com.lumo.tv.data.Series
import com.lumo.tv.data.Vod
import com.lumo.tv.data.VodInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(m: AppModel) {
    if (m.loading && m.vods.isEmpty() && m.series.isEmpty()) { Loading("Chargement du catalogue…"); return }
    if (m.loadError != null && m.vods.isEmpty()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(m.loadError ?: "", color = C.Sub)
            Spacer(Modifier.height(16.dp))
            LumoButton("Réessayer") { m.loadCatalog(true) }
        }
        return
    }
    val cardW = if (m.isTv) 140.dp else 108.dp
    val vodOk = m.allowedCatIds("vod")
    val serOk = m.allowedCatIds("series")
    val vods = remember(m.vods, vodOk) { m.vods.filter { it.catId in vodOk } }
    val series = remember(m.series, serOk) { m.series.filter { it.catId in serOk } }
    val favLive = run { m.store.version; m.store.favs(m.pid, "live") }
    val liveOk = m.allowedCatIds("live")
    val sortMode = m.liveSort
    val myChannels = remember(m.live, favLive, liveOk, sortMode) { m.sortLive(m.live.filter { it.id in favLive && it.catId in liveOk }, sortMode) }
    // Grande affiche et « Nouveautés » : films français en priorité
    val frCats = remember(m.vodCats) {
        val fr = Regex("(^|[^a-z])(fr|vf|vff|vfq|french|france|fran[cç]ais)([^a-z]|$)", RegexOption.IGNORE_CASE)
        m.vodCats.filter { fr.containsMatchIn(it.name) }.map { it.id }.toSet()
    }
    val frenchRecent = remember(vods, frCats) {
        vods.filter { it.catId in frCats || it.name.trim().startsWith("FR", ignoreCase = true) }.sortedByDescending { it.added }.take(40)
    }
    val recentVods = remember(vods, frenchRecent) { frenchRecent.ifEmpty { vods.sortedByDescending { it.added }.take(40) } }
    val recentSeries = remember(series) { series.sortedByDescending { it.modified }.take(40) }
    val vodsById = remember(m.vods) { m.vods.associateBy { it.id } }
    val seriesById = remember(m.series) { m.series.associateBy { it.id } }
    val byCat = remember(vods) { vods.groupBy { it.catId } }
    val serByCat = remember(series) { series.groupBy { it.catId } }
    // Un seul élément par film (même titre) et par série (dernier épisode regardé)
    val progress = m.progressList().filter { !it.finished }
        .distinctBy { if (it.kind == "ep" && it.parentId.isNotBlank()) "s" + it.parentId else "v" + it.title.lowercase().filter { c -> c.isLetterOrDigit() } }
        .take(30)
    val favVod = run { m.store.version; m.store.favs(m.pid, "vod") }
    val favSer = run { m.store.version; m.store.favs(m.pid, "series") }
    val hero = recentVods.firstOrNull { it.icon.isNotBlank() } ?: recentVods.firstOrNull()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        if (hero != null) item { Hero(m, hero) }
        if (progress.isNotEmpty()) item {
            RowSection("Reprendre la lecture") {
                items(progress, key = { it.key }) { p -> ProgressCard(m, p, cardW, vodsById, seriesById) }
            }
        }
        if (myChannels.isNotEmpty()) item(key = "mych") {
            RowSection("Mes chaînes") {
                items(myChannels, key = { "ch" + it.id }) { c -> ChannelCard(m, c, myChannels) }
            }
        }
        val myList = favVod.mapNotNull { vodsById[it] }.map { Triple(it.id, it.name, it.icon) to it } +
            favSer.mapNotNull { seriesById[it] }.map { Triple(it.id, it.name, it.cover) to it }
        if (myList.isNotEmpty()) item {
            RowSection("Ma liste") {
                items(myList, key = { "fav" + it.first.first + it.second.javaClass.simpleName }) { (t, obj) ->
                    PosterCard(t.second, t.third, cardW) { openItem(m, obj) }
                }
            }
        }
        val anim = run { m.store.version; m.store.anim3d }
        if (anim && recentVods.size >= 3) item(key = "flow") {
            val flow = remember(recentVods) {
                recentVods.take(25).map { v ->
                    FlowItem(v.id, v.name, v.icon, listOfNotNull("Film", v.rating.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString(" · "))
                }
            }
            CoverFlow("Nouveautés", flow, m.isTv) { i -> m.go(Screen.MovieDetail(recentVods[i])) }
        }
        if (recentVods.isNotEmpty() && !anim) item {
            RowSection("Films ajoutés récemment") {
                items(recentVods, key = { "rv" + it.id }) { v -> PosterCard(v.name, v.icon, cardW) { m.go(Screen.MovieDetail(v)) } }
            }
        }
        if (recentSeries.isNotEmpty()) item {
            RowSection("Séries mises à jour") {
                items(recentSeries, key = { "rs" + it.id }) { s -> PosterCard(s.name, s.cover, cardW) { m.go(Screen.SeriesDetail(s)) } }
            }
        }
        val cats = m.visibleCats("vod").take(8)
        cats.forEach { c ->
            val list = byCat[c.id].orEmpty().take(40)
            if (list.isNotEmpty()) item(key = "cat" + c.id) {
                RowSection(c.name) {
                    items(list, key = { "c${c.id}_" + it.id }) { v -> PosterCard(v.name, v.icon, cardW) { m.go(Screen.MovieDetail(v)) } }
                }
            }
        }
        val scats = m.visibleCats("series").take(4)
        scats.forEach { c ->
            val list = serByCat[c.id].orEmpty().take(40)
            if (list.isNotEmpty()) item(key = "scat" + c.id) {
                RowSection(c.name) {
                    items(list, key = { "s${c.id}_" + it.id }) { s -> PosterCard(s.name, s.cover, cardW) { m.go(Screen.SeriesDetail(s)) } }
                }
            }
        }
    }
}

fun openItem(m: AppModel, obj: Any) {
    when (obj) {
        is Vod -> m.go(Screen.MovieDetail(obj))
        is Series -> m.go(Screen.SeriesDetail(obj))
    }
}

@Composable
private fun ProgressCard(m: AppModel, p: Progress, w: androidx.compose.ui.unit.Dp, vods: Map<String, Vod>, series: Map<String, Series>) {
    PosterCard(p.title, p.img, w, progress = p.fraction, badge = p.subtitle.ifBlank { null }) {
        if (p.kind == "vod") {
            val v = vods[p.itemId] ?: Vod(p.itemId, p.title, p.img, "", "", p.ext, 0)
            m.go(Screen.MovieDetail(v))
        } else {
            val s = series[p.parentId] ?: Series(p.parentId, p.title, p.img, "", "", "", "", 0)
            m.go(Screen.SeriesDetail(s))
        }
    }
}

@Composable
fun RowSection(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val m = LocalPad.current
    Column(Modifier.padding(top = 18.dp)) {
        SectionTitle(title, Modifier.padding(start = m, bottom = 10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = m, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

val LocalPad = staticCompositionLocalOf { 16.dp }

@Composable
private fun Hero(m: AppModel, v: Vod) {
    var info by remember(v.id) { mutableStateOf<VodInfo?>(null) }
    LaunchedEffect(v.id) {
        info = withContext(Dispatchers.IO) { try { m.client?.vodInfo(v.id) } catch (_: Exception) { null } }
    }
    val h = if (m.isTv) 400.dp else 420.dp
    val anim = m.store.anim3d
    val kb = if (anim) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "heroZoom")
        t.animateFloat(
            initialValue = 1f, targetValue = 1.08f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween<Float>(20_000), androidx.compose.animation.core.RepeatMode.Reverse
            ),
            label = "heroZoom"
        ).value
    } else 1f
    Box(Modifier.fillMaxWidth().height(h).clipToBounds()) {
        val img = info?.backdrop?.ifBlank { null } ?: v.icon
        if (img.isNotBlank()) AsyncImage(model = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = kb; scaleY = kb })
        Box(Modifier.fillMaxSize().background(leftFade()))
        Box(Modifier.fillMaxSize().background(bottomFade()))
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = LocalPad.current, end = LocalPad.current, bottom = 20.dp).widthIn(max = 620.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("FILM", color = C.Sub, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Text(v.name, color = Color.White, fontSize = if (m.isTv) 46.sp else 34.sp, fontWeight = FontWeight.Black, lineHeight = if (m.isTv) 48.sp else 36.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                info?.year?.ifBlank { null },
                info?.genre?.ifBlank { null },
                info?.duration?.ifBlank { null },
                v.rating.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }
            ).joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, color = C.Sub, fontSize = 14.sp)
            info?.plot?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Color.White, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LumoButton("Lecture", icon = { Icon(Icons.Default.PlayArrow, null, tint = Color.Black) }) {
                    m.go(Screen.Player(com.lumo.tv.Playable.Movie(v)))
                }
                LumoButton("Plus d'infos", primary = false, icon = { Icon(Icons.Default.Info, null, tint = Color.White) }) {
                    m.go(Screen.MovieDetail(v))
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(m: AppModel, c: com.lumo.tv.data.LiveStream, list: List<com.lumo.tv.data.LiveStream>) {
    LaunchedEffect(c.id) { m.loadNow(c.id) }
    val w = if (m.isTv) 220.dp else 170.dp
    FocusBox(modifier = Modifier.width(w), onClick = { m.go(Screen.Player(com.lumo.tv.Playable.Live(list, list.indexOf(c).coerceAtLeast(0)))) }) { _ ->
        Column(Modifier.fillMaxWidth().background(C.Card).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().height(if (m.isTv) 90.dp else 70.dp).background(Color(0xFF1F1F1F)), contentAlignment = Alignment.Center) {
                Text(c.name.take(3), color = C.Dim)
                if (c.icon.isNotBlank()) AsyncImage(model = c.icon, contentDescription = c.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
            }
            Text(c.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.nowPlaying[c.id]?.ifBlank { null } ?: "En direct", color = C.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
