package com.lumo.tv.ui

import kotlinx.coroutines.launch

import androidx.compose.ui.focus.focusProperties

import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key

import androidx.compose.animation.togetherWith

import androidx.compose.ui.graphics.Brush

import androidx.compose.ui.draw.blur

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.ui.focus.onFocusChanged

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
    // Seulement ce qui existe dans la source en cours (une reprise d'une autre source ne marcherait pas ici)
    val progress = m.progressList().filter { !it.finished }
        .filter { p -> if (m.vods.isEmpty()) true else if (p.kind == "ep") p.parentId in seriesById else p.itemId in vodsById }
        .distinctBy { if (it.kind == "ep" && it.parentId.isNotBlank()) "s" + it.parentId else "v" + it.title.lowercase().filter { c -> c.isLetterOrDigit() } }
        .take(30)
    val favVod = run { m.store.version; m.store.favs(m.pid, "vod") }
    val favSer = run { m.store.version; m.store.favs(m.pid, "series") }
    // Si l'affiche d'un film ne se charge pas, on passe au film suivant
    // Film en vedette : défile tout seul toutes les 8 s en fondu enchaîné (les films sans image sont sautés)
    val heroList = remember(recentVods) { recentVods.filter { it.icon.isNotBlank() }.ifEmpty { recentVods }.take(10) }
    val badHero = remember(heroList) { mutableStateListOf<String>() }
    val heroOk = heroList.filter { it.id !in badHero }.ifEmpty { heroList }
    var heroIdx by remember(heroList) { mutableIntStateOf(0) }
    var heroFocused by remember { mutableStateOf(false) }
    val hero = heroOk.getOrNull(heroIdx % heroOk.size.coerceAtLeast(1))
    LaunchedEffect(heroList, heroFocused) {
        while (heroOk.size > 1 && !heroFocused) {
            kotlinx.coroutines.delay(8000)
            heroIdx = (heroIdx + 1) % heroOk.size.coerceAtLeast(1)
        }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val listScope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
        // Haut de l'accueil classique : grande affiche du film en fondu enchaîné (retour à la version d'avant l'effet iPad)
        // TV : bandeau façon Fire TV (image à droite, texte et boutons à gauche)
        if (hero != null && m.isTv) item(key = "firehero") {
            Box(Modifier.onFocusChanged { heroFocused = it.hasFocus; if (it.hasFocus) listScope.launch { listState.animateScrollToItem(0) } }) {
                FireHero(m, hero, heroOk.size, heroIdx % heroOk.size.coerceAtLeast(1),
                    onStep = { d -> heroIdx = ((heroIdx + d) % heroOk.size + heroOk.size) % heroOk.size },
                    onNoImage = { if (hero.id !in badHero) badHero.add(hero.id) })
            }
        }
        else if (hero != null) item {
            Box(Modifier.onFocusChanged { heroFocused = it.hasFocus }) {
                androidx.compose.animation.Crossfade(targetState = hero, animationSpec = androidx.compose.animation.core.tween(1000), label = "hero") { v ->
                    Hero(m, v) { if (v.id !in badHero) badHero.add(v.id) }
                }
                // Petits points : quel film est affiché
                if (heroOk.size > 1) Row(
                    Modifier.align(Alignment.BottomEnd).padding(end = LocalPad.current, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    heroOk.forEachIndexed { i, _ ->
                        val on = i == heroIdx % heroOk.size
                        Box(Modifier.height(4.dp).width(if (on) 16.dp else 6.dp).background(if (on) C.Red else Color(0x66FFFFFF), RoundedCornerShape(2.dp)))
                    }
                }
            }
        }
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
        if (myList.isNotEmpty()) item(key = "mylist") { FlowOrRow(m, "Ma liste", "fav", myList.map { it.second }, cardW) }
        val anim = run { m.store.version; m.store.anim3d }
        if (anim && recentVods.size >= 3) item(key = "flow") {
            val flow = remember(recentVods) {
                recentVods.take(25).map { v ->
                    FlowItem(v.id, v.name, v.icon, listOfNotNull("Film", v.rating.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString(" · "))
                }
            }
            CoverFlow("Nouveautés", flow.map { it.copy(kind = "movie") }, m.isTv) { i -> m.go(Screen.MovieDetail(recentVods[i])) }
        }
        if (recentVods.isNotEmpty() && !anim) item {
            RowSection("Films ajoutés récemment") {
                items(recentVods, key = { "rv" + it.id }) { v -> PosterCard(v.name, v.icon, cardW, kind = "movie") { m.go(Screen.MovieDetail(v)) } }
            }
        }
        if (recentSeries.isNotEmpty()) item(key = "rser") { FlowOrRow(m, "Séries mises à jour", "rs", recentSeries.take(25), cardW) }
        val cats = m.visibleCats("vod").take(8)
        cats.forEach { c ->
            val list = byCat[c.id].orEmpty().take(40)
            if (list.isNotEmpty()) item(key = "cat" + c.id) { FlowOrRow(m, c.name, "c${c.id}_", list, cardW) }
        }
        val scats = m.visibleCats("series").take(4)
        scats.forEach { c ->
            val list = serByCat[c.id].orEmpty().take(40)
            if (list.isNotEmpty()) item(key = "scat" + c.id) { FlowOrRow(m, c.name, "s${c.id}_", list, cardW) }
        }
    }
}

/** Rangée d'affiches en défilement 3D (effet iPad) quand les animations sont activées, sinon rangée classique. */
@Composable
private fun FlowOrRow(m: AppModel, title: String, keyPrefix: String, objs: List<Any>, cardW: androidx.compose.ui.unit.Dp) {
    // Effet iPad gardé seulement pour « Nouveautés » et les profils : ici, rangée classique
    if (false) {
        val flow = remember(objs) {
            objs.map { o ->
                when (o) {
                    is Vod -> FlowItem(o.id, o.name, o.icon, listOfNotNull("Film", o.rating.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString(" · "), "movie")
                    is Series -> FlowItem(o.id, o.name, o.cover, "Série", "tv")
                    else -> FlowItem("", "", "", "")
                }
            }
        }
        CoverFlow(title, flow, m.isTv, startIndex = 1) { i -> openItem(m, objs[i]) }
    } else {
        RowSection(title) {
            items(objs.size, key = { i -> keyPrefix + i }) { i ->
                when (val o = objs[i]) {
                    is Vod -> PosterCard(o.name, o.icon, cardW, kind = "movie") { m.go(Screen.MovieDetail(o)) }
                    is Series -> PosterCard(o.name, o.cover, cardW, kind = "tv") { m.go(Screen.SeriesDetail(o)) }
                }
            }
        }
    }
}

/**
 * Haut de l'accueil : « Nouveautés » en défilement 3D (effet iPad), qui défile tout seul.
 * Derrière : la grande image du film au centre, en fondu ; dessous : titre et boutons.
 */
@Composable
private fun HeroFlow(m: AppModel, films: List<Vod>, onFocusTop: () -> Unit = {}) {
    var cur by remember(films) { mutableIntStateOf(1.coerceAtMost(films.lastIndex)) }
    val v = films[cur.coerceIn(0, films.lastIndex)]
    val land = isPhoneLandscape(m.isTv)
    // Résumés (chargés au fur et à mesure, gardés en mémoire)
    val plots = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(v.id) {
        if (!plots.containsKey(v.id)) {
            val p = withContext(Dispatchers.IO) { try { m.client?.vodInfo(v.id)?.plot } catch (_: Exception) { null } }?.trim().orEmpty()
            // Pas de résumé chez le fournisseur → résumé en français de TMDB
            plots[v.id] = p.ifBlank { withContext(Dispatchers.IO) { com.lumo.tv.data.TmdbArt.overview("movie", v.name) } }
        }
    }
    val flow = remember(films) {
        films.map { f -> FlowItem(f.id, f.name, f.icon, "", "movie") }
    }
    Box(Modifier.fillMaxWidth().onFocusChanged { if (it.hasFocus && m.isTv) onFocusTop() }) {
        // Fond : grande image du film au centre, floutée et assombrie, en fondu enchaîné
        androidx.compose.animation.Crossfade(targetState = v, animationSpec = androidx.compose.animation.core.tween(1200), label = "heroBg", modifier = Modifier.matchParentSize()) { f ->
            val art = rememberTmdbArt("movie", f.name)
            val bg = art?.backdrop?.ifBlank { null } ?: art?.poster?.ifBlank { null } ?: f.icon
            Box(Modifier.fillMaxSize()) {
                // Fond flouté : image chargée en petit (elle est floue de toute façon) → fondu plus léger et fluide
                if (bg.isNotBlank()) AsyncImage(
                    model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current).data(bg).size(480, 270).build(),
                    contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().then(if (android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(12.dp) else Modifier).graphicsLayer { alpha = 0.55f }
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99000000), Color(0x55000000), Color.Black))))
            }
        }
        // TV : tout centré en colonne (affiches, puis titre/résumé/boutons). Téléphone horizontal : côte à côte.
        val wide = land && !m.isTv
        // Titre + résumé + boutons du film du milieu
        val playFr = remember { androidx.compose.ui.focus.FocusRequester() }
        val flowFr = remember { androidx.compose.ui.focus.FocusRequester() }
        var infoFocused by remember { mutableStateOf(false) }
        var target by remember { mutableIntStateOf(-1) }
        var targetKey by remember { mutableIntStateOf(0) }
        fun step(d: Int) { target = ((cur + d) % films.size + films.size) % films.size; targetKey++ }
        @Composable fun Info(center: Boolean) {
            Column(
                modifier = Modifier.onFocusChanged { infoFocused = it.hasFocus },
                horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.compose.animation.AnimatedContent(targetState = v, label = "heroTitle", transitionSpec = {
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(500)) togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(300))
                }) { f ->
                    val ta = if (center) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start
                    Column(horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
                        Text(f.name, color = Color.White, fontSize = if (wide || m.isTv) 22.sp else 24.sp, fontWeight = FontWeight.Black, maxLines = if (m.isTv) 1 else 2, lineHeight = if (wide || m.isTv) 26.sp else 28.sp, overflow = TextOverflow.Ellipsis, textAlign = ta)
                        f.rating.toDoubleOrNull()?.takeIf { it > 0 }?.let { Text("Film · ★ %.1f".format(it), color = C.Sub, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                        // Résumé du film du milieu
                        plots[f.id]?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it, color = Color(0xFFD0D0D0), fontSize = 13.sp, lineHeight = 18.sp,
                                maxLines = if (wide) 4 else 3, overflow = TextOverflow.Ellipsis, textAlign = ta,
                                modifier = Modifier.widthIn(max = if (m.isTv) 760.dp else 640.dp).padding(top = if (m.isTv) 4.dp else 8.dp)
                            )
                        }
                    }
                }
                // Les boutons passent à la ligne s'il manque de place (ils restent toujours atteignables)
                FlowRowCompat(spacing = 10.dp, center = center) {
                    // TV : ▲ depuis les boutons → retour sur les affiches
                    LumoButton("Lecture", requester = playFr, modifier = if (m.isTv) Modifier.focusProperties { up = flowFr } else Modifier
                    , icon = { Icon(Icons.Default.PlayArrow, null, tint = Color.Black) }) {
                        m.go(Screen.Player(com.lumo.tv.Playable.Movie(v)))
                    }
                    LumoButton("Plus d'infos", primary = false, modifier = if (m.isTv) Modifier.focusProperties { up = flowFr } else Modifier
                    , icon = { Icon(Icons.Default.Info, null, tint = Color.White) }) {
                        m.go(Screen.MovieDetail(v))
                    }
                }
            }
        }
        if (wide) {
            // TV et téléphone à l'horizontale : affiches à gauche, titre/résumé/boutons à droite (tout tient à l'écran)
            // Taille calculée pour que tout le haut tienne dans l'écran (sinon la page défile quand on va sur les boutons)
            val sh = screenHeightDp()
            val avail = sh - (if (m.isTv) 190.dp else 110.dp)
            val cw = minOf(if (m.isTv) 150.dp else 105.dp, avail / (1.45f + 0.15f) / 1.5f - 20.dp)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(0.6f)) {
                    CoverFlow(
                        title = null, items = flow, isTv = m.isTv, startIndex = cur,
                        width = cw, reflection = 0.15f,
                        autoMs = 5000, showCaption = false, onCurrent = { cur = it }, centerScale = 1.45f,
                        // TV : ▼ des onglets → affiches (◀ ▶ pour défiler) ; ▼ encore → Lecture / Plus d'infos
                        focusRequester = if (m.isTv) flowFr else null, downTo = if (m.isTv) playFr else null,
                        paused = infoFocused, target = target, targetKey = targetKey
                    ) { i -> m.go(Screen.MovieDetail(films[i])) }
                }
                Box(Modifier.weight(0.4f).padding(end = LocalPad.current, start = 4.dp)) { Info(center = false) }
            }
        } else Column(Modifier.fillMaxWidth().padding(top = if (m.isTv) 0.dp else 6.dp)) {
            // TV : taille des affiches calculée pour que tout (affiches + texte + boutons) tienne dans l'écran
            val tvW = if (m.isTv) ((screenHeightDp() - 272.dp) / 1.5f / 1.5f).coerceIn(80.dp, 160.dp) else 165.dp
            CoverFlow(
                title = null, items = flow, isTv = m.isTv, startIndex = cur,
                width = tvW, reflection = if (m.isTv) 0f else 0.3f, compact = m.isTv,
                autoMs = 5000, showCaption = false, onCurrent = { cur = it }, centerScale = 1.5f,
                // TV : ▼ des onglets → affiches (◀ ▶ défilent) ; ▼ → Lecture / Plus d'infos ; ▼ → rangées
                focusRequester = if (m.isTv) flowFr else null, downTo = if (m.isTv) playFr else null,
                paused = infoFocused
            ) { i -> m.go(Screen.MovieDetail(films[i])) }
            Box(Modifier.fillMaxWidth().padding(horizontal = LocalPad.current), contentAlignment = Alignment.Center) { Info(center = true) }
            Spacer(Modifier.height(6.dp))
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
    PosterCard(p.title, p.img, w, progress = p.fraction, badge = p.subtitle.ifBlank { null }, kind = if (p.kind == "vod") "movie" else "tv",
        onLongClick = { m.askRemoveProgress(p) }) {
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
private fun Hero(m: AppModel, v: Vod, onNoImage: () -> Unit = {}) {
    var info by remember(v.id) { mutableStateOf<VodInfo?>(null) }
    var infoDone by remember(v.id) { mutableStateOf(false) }
    LaunchedEffect(v.id) {
        info = withContext(Dispatchers.IO) { try { m.client?.vodInfo(v.id) } catch (_: Exception) { null } }
        infoDone = true
    }
    val land = isPhoneLandscape(m.isTv)
    val sh = screenHeightDp()
    val h = if (m.isTv) 400.dp else if (land) sh * 0.68f else 420.dp
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
        // Images à essayer dans l'ordre : grande image, affiche ; si aucune ne marche → film suivant
        val tart = rememberTmdbArt("movie", v.name)
        val imgs = listOfNotNull(tart?.backdrop?.ifBlank { null }, info?.backdrop?.ifBlank { null }, v.icon.ifBlank { null }).distinct()
        var ci by remember(v.id, imgs.size) { mutableIntStateOf(0) }
        val img = imgs.getOrNull(ci)
        var allFailed by remember(v.id, imgs.size) { mutableStateOf(false) }
        LaunchedEffect(infoDone, allFailed, imgs.size) { if (infoDone && (allFailed || imgs.isEmpty())) onNoImage() }
        if (img != null) AsyncImage(
            model = img, contentDescription = null, contentScale = ContentScale.Crop,
            onError = { if (ci + 1 < imgs.size) ci++ else allFailed = true },
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = kb; scaleY = kb }
        )
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

/** Bandeau d'accueil TV façon Fire TV : grande image à droite fondue dans le noir, infos et boutons à gauche. */
@Composable
private fun FireHero(m: AppModel, v: Vod, count: Int, index: Int, onStep: (Int) -> Unit, onNoImage: () -> Unit) {
    val infos = remember { mutableStateMapOf<String, VodInfo?>() }
    val plots = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(v.id) {
        if (!infos.containsKey(v.id)) {
            val inf = withContext(Dispatchers.IO) { try { m.client?.vodInfo(v.id) } catch (_: Exception) { null } }
            infos[v.id] = inf
            plots[v.id] = inf?.plot?.trim().orEmpty().ifBlank { withContext(Dispatchers.IO) { com.lumo.tv.data.TmdbArt.overview("movie", v.name) } }
        }
    }
    val bg = Color(0xFF101014)
    val h = screenHeightDp() * 0.5f
    Box(
        Modifier.fillMaxWidth().padding(horizontal = LocalPad.current, vertical = 6.dp).height(h)
            .clip(RoundedCornerShape(12.dp)).background(bg)
    ) {
        // Image à droite (2/3 de la largeur), fondue vers la gauche et vers le bas
        androidx.compose.animation.Crossfade(targetState = v, animationSpec = androidx.compose.animation.core.tween(900), label = "fireImg",
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.68f)) { f ->
            val art = rememberTmdbArt("movie", f.name)
            val imgs = listOfNotNull(art?.backdrop?.ifBlank { null }, infos[f.id]?.backdrop?.ifBlank { null }, f.icon.ifBlank { null }).distinct()
            var ci by remember(f.id, imgs.size) { mutableIntStateOf(0) }
            Box(Modifier.fillMaxSize()) {
                imgs.getOrNull(ci)?.let { img ->
                    AsyncImage(model = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                        onError = { if (ci + 1 < imgs.size) ci++ else if (f.id == v.id) onNoImage() })
                }
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to bg, 0.45f to bg.copy(alpha = 0.55f), 1f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to bg.copy(alpha = 0.8f))))
            }
        }
        // Infos à gauche
        Column(
            Modifier.align(Alignment.CenterStart).fillMaxWidth(0.46f).padding(start = 28.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            androidx.compose.animation.AnimatedContent(targetState = v, label = "fireTxt", transitionSpec = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(500)) togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(250))
            }) { f ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(com.lumo.tv.data.Tmdb.cleanName(f.name).ifBlank { f.name }, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black,
                        lineHeight = 32.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val inf = infos[f.id]
                    val meta = listOfNotNull(
                        inf?.year?.ifBlank { null } ?: com.lumo.tv.data.TmdbArt.split(f.name).second.ifBlank { null },
                        "Film",
                        inf?.genre?.ifBlank { null }?.split(",")?.firstOrNull()?.trim(),
                        f.rating.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }
                    ).joinToString(" · ")
                    Text(meta, color = C.Sub, fontSize = 13.sp, maxLines = 1)
                    plots[f.id]?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = Color(0xFFD0D0D0), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // Boutons hors de l'animation : la sélection de la télécommande ne saute pas quand le film change
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                LumoButton("Lecture", modifier = Modifier.onPreviewKeyEvent { e ->
                    if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.DirectionLeft) { onStep(-1); true } else false
                }, icon = { Icon(Icons.Default.PlayArrow, null, tint = Color.Black) }) {
                    m.go(Screen.Player(com.lumo.tv.Playable.Movie(v)))
                }
                LumoButton("Plus d'infos", primary = false, modifier = Modifier.onPreviewKeyEvent { e ->
                    if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.DirectionRight) { onStep(1); true } else false
                }, icon = { Icon(Icons.Default.Info, null, tint = Color.White) }) {
                    m.go(Screen.MovieDetail(v))
                }
            }
        }
        // Petits points : quel film est affiché
        if (count > 1) Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            repeat(count) { i ->
                val on = i == index
                Box(Modifier.height(4.dp).width(if (on) 16.dp else 6.dp).background(if (on) C.Red else Color(0x66FFFFFF), RoundedCornerShape(2.dp)))
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
