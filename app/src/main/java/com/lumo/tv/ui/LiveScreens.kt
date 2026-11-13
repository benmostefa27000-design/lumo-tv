package com.lumo.tv.ui

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

@Composable
fun LiveScreen(m: AppModel) {
    if (m.live.isEmpty()) {
        if (m.loading) Loading("Chargement des chaînes…") else Empty("Aucune chaîne")
        return
    }
    val cats = m.visibleCats("live")
    val favs = run { m.store.version; m.store.favs(m.pid, "live") }
    var sel by remember { mutableStateOf(if (favs.isNotEmpty()) FAV else (cats.firstOrNull()?.id ?: ALL)) }
    val visibleIds = remember(cats) { cats.map { it.id }.toSet() }
    val list = remember(sel, m.live, visibleIds, favs) {
        when (sel) {
            FAV -> m.live.filter { it.id in favs }
            ALL -> m.live.filter { it.catId in visibleIds }
            else -> m.live.filter { it.catId == sel }
        }
    }
    val catItems = listOf(FAV to "★ Favoris") + cats.map { it.id to it.name }

    if (m.isTv) {
        Row(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LazyColumn(Modifier.width(250.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
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
            ChannelList(m, list, Modifier.weight(1f))
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(catItems, key = { it.first }) { (id, name) -> Chip(name, sel == id) { sel = id } }
            }
            ChannelList(m, list, Modifier.weight(1f).padding(horizontal = 12.dp))
        }
    }
}

@Composable
private fun ChannelList(m: AppModel, list: List<LiveStream>, modifier: Modifier) {
    if (list.isEmpty()) { Box(modifier) { Empty("Rien ici pour l'instant. Ajoute des chaînes en favori avec l'étoile.") }; return }
    LazyColumn(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(list, key = { _, s -> s.id }) { i, s -> ChannelRow(m, s, list, i) }
    }
}

@Composable
private fun ChannelRow(m: AppModel, s: LiveStream, list: List<LiveStream>, index: Int) {
    LaunchedEffect(s.id) { delay(250); m.loadNow(s.id) }
    val fav = m.isFav("live", s.id)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusBox(modifier = Modifier.weight(1f), scale = 1.02f, onClick = { m.go(Screen.Player(Playable.Live(list, index))) }) { f ->
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
        IconFocus(Icons.Default.DateRange, "Guide et rattrapage", tint = if (s.archive) Color.White else C.Dim) {
            m.go(Screen.ChannelGuide(s, list))
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
            try { m.client?.fullEpg(s.id) ?: emptyList() } catch (_: Exception) { error = true; emptyList() }
        }
    }
    val now = System.currentTimeMillis()
    val pad = if (m.isTv) 48.dp else 16.dp
    Column(Modifier.fillMaxSize().padding(horizontal = pad, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (s.icon.isNotBlank()) AsyncImage(model = s.icon, contentDescription = null, modifier = Modifier.size(64.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(if (s.archive) "Rattrapage disponible (${s.archiveDays} jours)" else "Pas de rattrapage sur cette chaîne", color = C.Sub, fontSize = 14.sp)
            }
            LumoButton("Direct", icon = { Icon(Icons.Default.PlayArrow, null, tint = Color.Black) }) {
                m.go(Screen.Player(Playable.Live(list, list.indexOfFirst { it.id == s.id }.coerceAtLeast(0))))
            }
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
