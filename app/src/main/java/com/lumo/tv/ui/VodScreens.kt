package com.lumo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
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
import com.lumo.tv.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------- Films / Séries par catégorie ----------------

@Composable
fun VodBrowser(m: AppModel, series: Boolean) {
    val kind = if (series) "series" else "vod"
    val cats = m.visibleCats(kind)
    val empty = if (series) m.series.isEmpty() else m.vods.isEmpty()
    if (empty) { if (m.loading) Loading() else Empty("Rien à afficher"); return }
    var sel by remember(kind) { mutableStateOf(cats.firstOrNull()?.id ?: "") }
    if (cats.isNotEmpty() && cats.none { it.id == sel }) sel = cats.first().id
    val cardW = if (m.isTv) 140.dp else 108.dp

    val content: @Composable (Modifier) -> Unit = { mod ->
        if (series) {
            val list = remember(sel, m.series) { m.series.filter { it.catId == sel } }
            LazyVerticalGrid(GridCells.Adaptive(cardW), mod, contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list, key = { it.id }) { s -> PosterCard(s.name, s.cover, cardW) { m.go(Screen.SeriesDetail(s)) } }
            }
        } else {
            val list = remember(sel, m.vods) { m.vods.filter { it.catId == sel }.sortedByDescending { it.added } }
            LazyVerticalGrid(GridCells.Adaptive(cardW), mod, contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list, key = { it.id }) { v -> PosterCard(v.name, v.icon, cardW) { m.go(Screen.MovieDetail(v)) } }
            }
        }
    }

    if (m.isTv) {
        Row(Modifier.fillMaxSize().padding(start = 40.dp, end = 28.dp, top = 8.dp)) {
            LazyColumn(Modifier.width(250.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(cats, key = { it.id }) { c ->
                    FocusBox(modifier = Modifier.fillMaxWidth(), scale = 1.03f, onClick = { sel = c.id }) { f ->
                        Text(
                            c.name,
                            color = if (sel == c.id || f) Color.White else C.Sub,
                            fontWeight = if (sel == c.id) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().background(if (sel == c.id) Color(0x1FFFFFFF) else Color.Transparent).padding(horizontal = 14.dp, vertical = 11.dp)
                        )
                    }
                }
            }
            content(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cats, key = { it.id }) { c -> Chip(c.name, sel == c.id) { sel = c.id } }
            }
            content(Modifier.weight(1f))
        }
    }
}

// ---------------- Ma liste ----------------

@Composable
fun MyListScreen(m: AppModel) {
    val favV = run { m.store.version; m.store.favs(m.pid, "vod") }
    val favS = run { m.store.version; m.store.favs(m.pid, "series") }
    val vods = m.vods.filter { it.id in favV }
    val series = m.series.filter { it.id in favS }
    val cardW = if (m.isTv) 140.dp else 108.dp
    if (vods.isEmpty() && series.isEmpty()) { Empty("Ta liste est vide. Ajoute des films et des séries avec le bouton « Ma liste ».") ; return }
    LazyVerticalGrid(GridCells.Adaptive(cardW), Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = LocalPad.current, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(vods, key = { "v" + it.id }) { v -> PosterCard(v.name, v.icon, cardW, badge = "FILM") { m.go(Screen.MovieDetail(v)) } }
        items(series, key = { "s" + it.id }) { s -> PosterCard(s.name, s.cover, cardW, badge = "SÉRIE") { m.go(Screen.SeriesDetail(s)) } }
    }
}

// ---------------- Fiche film ----------------

@Composable
fun MovieDetailScreen(m: AppModel, v: Vod) {
    var info by remember(v.id) { mutableStateOf<VodInfo?>(null) }
    LaunchedEffect(v.id) { info = withContext(Dispatchers.IO) { try { m.client?.vodInfo(v.id) } catch (_: Exception) { null } } }
    val prog = m.progressFor("vod_${v.id}")
    val fav = m.isFav("vod", v.id)
    val pad = if (m.isTv) 56.dp else 18.dp
    Box(Modifier.fillMaxSize()) {
        val img = info?.backdrop?.ifBlank { null } ?: v.icon
        if (img.isNotBlank()) AsyncImage(model = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(leftFade()))
        Box(Modifier.fillMaxSize().background(bottomFade()))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = pad, vertical = if (m.isTv) 56.dp else 24.dp).widthIn(max = 700.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (!m.isTv) Spacer(Modifier.height(140.dp))
            Text(v.name, color = Color.White, fontSize = if (m.isTv) 48.sp else 32.sp, fontWeight = FontWeight.Black, lineHeight = if (m.isTv) 50.sp else 34.sp)
            val meta = listOfNotNull(
                (info?.rating?.ifBlank { null } ?: v.rating.ifBlank { null })?.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                info?.year?.ifBlank { null }, info?.duration?.ifBlank { null }, info?.genre?.ifBlank { null }
            ).joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, color = C.Sub, fontSize = 15.sp)
            if (prog != null && !prog.finished && prog.dur > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.width(200.dp).height(4.dp).background(Color(0x55FFFFFF))) { Box(Modifier.fillMaxHeight().fillMaxWidth(prog.fraction).background(C.Red)) }
                    Text("reste ${((prog.dur - prog.pos) / 60000).coerceAtLeast(1)} min", color = C.Sub, fontSize = 14.sp)
                }
            }
            FlowRowCompat(spacing = 10.dp, center = false) {
                val resume = prog != null && !prog.finished && prog.pos > 30_000
                val fr = rememberAutoFocus(m.isTv)
                LumoButton(if (resume) "Reprendre" else "Lecture", requester = fr, icon = { Icon(Icons.Default.PlayArrow, null, tint = Color.Black) }) {
                    m.go(Screen.Player(Playable.Movie(v)))
                }
                if (resume) LumoButton("Depuis le début", primary = false) { m.go(Screen.Player(Playable.Movie(v, fromStart = true))) }
                LumoButton(if (fav) "Dans ma liste" else "Ma liste", primary = false, icon = { Icon(if (fav) Icons.Default.Check else Icons.Default.Add, null, tint = Color.White) }) {
                    m.toggleFav("vod", v.id)
                }
                if (!m.isTv) SendToTvButton(m) {
                    org.json.JSONObject().put("type", "play_vod").put("id", v.id).put("title", v.name).put("img", v.icon).put("ext", v.ext)
                }
            }
            info?.plot?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color.White, fontSize = 16.sp, lineHeight = 23.sp) }
            info?.cast?.takeIf { it.isNotBlank() }?.let { Text("Avec : $it", color = C.Sub, fontSize = 14.sp) }
            info?.director?.takeIf { it.isNotBlank() }?.let { Text("Réalisation : $it", color = C.Sub, fontSize = 14.sp) }
            if (info == null) Text("Chargement des infos…", color = C.Dim, fontSize = 13.sp)
        }
    }
}

// ---------------- Fiche série ----------------

@Composable
fun SeriesDetailScreen(m: AppModel, s: Series) {
    var info by remember(s.id) { mutableStateOf<SeriesInfo?>(null) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(s.id) { info = withContext(Dispatchers.IO) { try { m.client?.seriesInfo(s.id) } catch (_: Exception) { error = true; null } } }
    val progress = m.progressList().filter { it.kind == "ep" && it.parentId == s.id }
    val last = progress.maxByOrNull { it.ts }
    val i = info
    var season by remember(i) { mutableStateOf(i?.episodes?.entries?.firstOrNull { e -> e.value.any { it.id == last?.itemId } }?.key ?: i?.seasons?.firstOrNull() ?: 1) }
    val fav = m.isFav("series", s.id)
    val pad = if (m.isTv) 56.dp else 16.dp

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(if (m.isTv) 380.dp else 300.dp)) {
                val img = i?.backdrop?.ifBlank { null } ?: s.cover
                if (img.isNotBlank()) AsyncImage(model = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(leftFade()))
                Box(Modifier.fillMaxSize().background(bottomFade()))
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = pad, vertical = 12.dp).widthIn(max = 700.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(s.name, color = Color.White, fontSize = if (m.isTv) 44.sp else 30.sp, fontWeight = FontWeight.Black)
                    val meta = listOfNotNull(
                        s.rating.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                        i?.year?.ifBlank { null } ?: s.year.ifBlank { null },
                        i?.seasons?.size?.takeIf { it > 0 }?.let { if (it > 1) "$it saisons" else "1 saison" },
                        i?.genre?.ifBlank { null }
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, color = C.Sub, fontSize = 14.sp)
                    FlowRowCompat(spacing = 10.dp, center = false) {
                        val favFr = rememberAutoFocus(m.isTv)
                        if (i != null) {
                            val target = findResume(i, last)
                            if (target != null) {
                                val (eps, idx) = target
                                val ep = eps[idx]
                                LumoButton(if (last != null) "Reprendre S${ep.season} É${ep.num}" else "Lecture", icon = { Icon(Icons.Default.PlayArrow, null, tint = Color.Black) }) {
                                    m.go(Screen.Player(Playable.Ep(s, eps, idx)))
                                }
                                if (!m.isTv) SendToTvButton(m) {
                                    org.json.JSONObject().put("type", "play_ep").put("series", s.id).put("id", ep.id).put("title", s.name).put("img", s.cover)
                                }
                            }
                        }
                        LumoButton(if (fav) "Dans ma liste" else "Ma liste", primary = false, requester = favFr, icon = { Icon(if (fav) Icons.Default.Check else Icons.Default.Add, null, tint = Color.White) }) {
                            m.toggleFav("series", s.id)
                        }
                    }
                }
            }
        }
        item {
            val plot = i?.plot?.ifBlank { null } ?: s.plot
            if (plot.isNotBlank()) Text(plot, color = C.Sub, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(horizontal = pad, vertical = 8.dp).widthIn(max = 800.dp))
        }
        when {
            i == null && !error -> item { Box(Modifier.height(200.dp)) { Loading("Chargement des épisodes…") } }
            i == null -> item { Empty("Épisodes indisponibles") }
            else -> {
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = pad, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(i.seasons, key = { it }) { n -> Chip(if (n == 0) "Spécial" else "Saison $n", season == n) { season = n } }
                    }
                }
                val eps = i.episodes[season].orEmpty()
                itemsIndexed(eps, key = { _, e -> e.id }) { idx, e ->
                    val p = progress.firstOrNull { it.itemId == e.id }
                    FocusBox(modifier = Modifier.fillMaxWidth().padding(horizontal = pad, vertical = 4.dp), scale = 1.02f, onClick = {
                        m.go(Screen.Player(Playable.Ep(s, eps, idx)))
                    }) { f ->
                        Row(Modifier.fillMaxWidth().background(if (f) Color(0xFF2A2A2A) else Color.Transparent).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(if (m.isTv) 180.dp else 124.dp).aspectRatio(16f / 9f).background(C.Card2)) {
                                val im = e.img.ifBlank { s.cover }
                                if (im.isNotBlank()) AsyncImage(model = im, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                if (p != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Color(0x55FFFFFF))) {
                                    Box(Modifier.fillMaxHeight().fillMaxWidth(if (p.finished) 1f else p.fraction).background(C.Red))
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${e.num}. ${e.title.ifBlank { "Épisode ${e.num}" }}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val sub = listOfNotNull(
                                    e.durationSecs.takeIf { it > 0 }?.let { "${it / 60} min" },
                                    when { p == null -> null; p.finished -> "vu"; else -> "reste ${((p.dur - p.pos) / 60000).coerceAtLeast(1)} min" }
                                ).joinToString(" · ")
                                if (sub.isNotBlank()) Text(sub, color = C.Dim, fontSize = 12.sp)
                                if (m.isTv && e.plot.isNotBlank()) Text(e.plot, color = C.Sub, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Épisode à lancer : la suite de celui en cours, sinon le tout premier. */
private fun findResume(info: SeriesInfo, last: Progress?): Pair<List<Episode>, Int>? {
    if (last != null) {
        for ((_, eps) in info.episodes) {
            val idx = eps.indexOfFirst { it.id == last.itemId }
            if (idx >= 0) {
                if (!last.finished) return eps to idx
                if (idx + 1 < eps.size) return eps to idx + 1
                val seasons = info.seasons
                val nextSeason = seasons.getOrNull(seasons.indexOf(eps[idx].season) + 1)
                val next = nextSeason?.let { info.episodes[it] }
                if (!next.isNullOrEmpty()) return next to 0
                return eps to idx
            }
        }
    }
    val first = info.seasons.firstOrNull { it > 0 } ?: info.seasons.firstOrNull() ?: return null
    val eps = info.episodes[first].orEmpty()
    return if (eps.isEmpty()) null else eps to 0
}
