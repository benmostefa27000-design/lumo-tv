package com.lumo.tv.ui

import androidx.compose.foundation.focusGroup

import androidx.compose.foundation.clickable

import androidx.compose.foundation.border

import androidx.compose.ui.focus.focusRequester

import androidx.compose.ui.focus.FocusRequester

import androidx.compose.ui.input.key.type

import androidx.compose.ui.input.key.key

import androidx.compose.ui.input.key.onPreviewKeyEvent

import androidx.compose.ui.draw.clip

import androidx.compose.runtime.getValue

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.animation.core.animateFloat

import androidx.compose.animation.togetherWith

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumo.tv.AppModel
import com.lumo.tv.Screen
import com.lumo.tv.Tab
import com.lumo.tv.player.PlayerScreen

@Composable
fun LumoApp(m: AppModel) {
    LaunchedEffect(m.tmdbKey) { com.lumo.tv.data.TmdbArt.key = m.tmdbKey }
    LumoTheme {
        Box(Modifier.fillMaxSize().background(C.Bg)) {
            BackHandler(enabled = m.stack.size > 1 || (m.current == Screen.Main && m.tab != Tab.Home)) {
                if (!m.back() && m.current == Screen.Main) m.tab = Tab.Home
            }
            when (val s = m.current) {
                Screen.Login -> LoginScreen(m)
                Screen.Profiles -> ProfilesScreen(m)
                is Screen.ProfileEdit -> ProfileEditScreen(m, s.profile)
                Screen.Main -> MainScreen(m)
                is Screen.MovieDetail -> MovieDetailScreen(m, s.vod)
                is Screen.SeriesDetail -> SeriesDetailScreen(m, s.series)
                is Screen.ChannelGuide -> ChannelGuideScreen(m, s.stream, s.list)
                is Screen.Player -> PlayerScreen(m, s.item)
                Screen.Search -> SearchScreen(m)
                is Screen.TvGuide -> GuideGridScreen(m, s.list, s.title)
                Screen.Sport -> SportScreen(m)
                Screen.Categories -> CategoriesScreen(m)
                Screen.Sources -> SourcesScreen(m)
                Screen.DeletedCategories -> DeletedCategoriesScreen(m)
                Screen.PairPhone -> PairPhoneScreen(m)
                Screen.PinCheck -> PinCheckScreen(m)
                is Screen.Remote -> RemoteScreen(m, s.deviceId, s.name)
            }

            m.toast?.let { t ->
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
                    Text(
                        t,
                        color = Color.White,
                        modifier = Modifier.background(Color(0xEE2A2A2A), RoundedCornerShape(8.dp)).padding(horizontal = 18.dp, vertical = 14.dp)
                    )
                }
            }

            m.removeAsk?.let { p ->
                // Télécommande : la fenêtre s'ouvre pendant que OK est encore enfoncé (appui long).
                // On ignore OK jusqu'à ce qu'il soit relâché, sinon le relâchement validait « Annuler » tout de suite.
                var armed by remember(p) { mutableStateOf(false) }
                LaunchedEffect(p) { kotlinx.coroutines.delay(1500); armed = true }
                val guard = Modifier.onPreviewKeyEvent { e ->
                    val ok = e.key == androidx.compose.ui.input.key.Key.DirectionCenter || e.key == androidx.compose.ui.input.key.Key.Enter || e.key == androidx.compose.ui.input.key.Key.NumPadEnter
                    if (!armed && ok) { if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) armed = true; true } else false
                }
                val retirerFr = remember { FocusRequester() }
                LaunchedEffect(p) { kotlinx.coroutines.delay(100); try { retirerFr.requestFocus() } catch (_: Exception) { } }
                AlertDialog(
                    onDismissRequest = { m.removeAsk = null },
                    containerColor = C.Card,
                    title = { Text("Reprendre la lecture", color = Color.White) },
                    text = { Text("Retirer « ${com.lumo.tv.data.TmdbArt.split(p.title).first.ifBlank { p.title }} » de la liste ?", color = C.Sub) },
                    confirmButton = { TextButton(modifier = guard.focusRequester(retirerFr), onClick = { m.removeFromContinue(p); m.removeAsk = null }) { Text("Retirer", color = C.Red) } },
                    dismissButton = { TextButton(modifier = guard, onClick = { m.removeAsk = null }) { Text("Annuler", color = C.Sub) } }
                )
            }

            val up = m.update
            if (up != null && m.current !is Screen.Player) {
                AlertDialog(
                    onDismissRequest = { m.update = null },
                    containerColor = C.Card,
                    title = { Text("Nouvelle version disponible", color = Color.White) },
                    text = { Text(m.updateProgress ?: "Lumo version 1.0.${up.version} est prête. L'installer maintenant ?", color = C.Sub) },
                    confirmButton = { TextButton(onClick = { m.installUpdate(up) }) { Text("Installer", color = C.Red) } },
                    dismissButton = { TextButton(onClick = { m.update = null }) { Text("Plus tard", color = C.Sub) } }
                )
            }
        }
    }
}

@Composable
fun MainScreen(m: AppModel) {
    if (m.isTv) {
        // TV : la barre du haut flotte par-dessus le contenu (l'accueil passe dessous, en plein écran)
        Box(Modifier.fillMaxSize()) {
            // Accueil, Films et Séries passent sous la barre (image en plein écran) ; les autres onglets commencent dessous
            // Le contenu passe sous la barre : Android ne « voit » plus rien en dessous des onglets avec ▼.
            // On envoie donc nous-mêmes la sélection dans le contenu (1er élément : Lecture sur l'accueil).
            val contentFr = remember { FocusRequester() }
            Box(
                Modifier.fillMaxSize().padding(top = if (m.tab == Tab.Home || m.tab == Tab.Movies || m.tab == Tab.Series) 0.dp else 66.dp)
                    .focusRequester(contentFr).focusGroup()
            ) { TabContent(m) }
            Box(Modifier.fillMaxWidth().height(110.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xE6000000), Color(0x00000000)))))
            Box(Modifier.onPreviewKeyEvent { e ->
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.DirectionDown) {
                    // 1) l'écran a indiqué où entrer (ex. « Lecture » sur l'accueil) ; 2) sinon le 1er élément du contenu
                    val ok = m.tvEntry?.let { try { it.requestFocus(); true } catch (_: Exception) { false } } ?: false
                    if (!ok) try { contentFr.requestFocus() } catch (_: Exception) { }
                    true
                } else false
            }) { TvTopBar(m) }
        }
    } else if (isPhoneLandscape(false)) {
        // Téléphone à l'horizontale : onglets en haut (comme sur la TV), pas de barre en bas
        Column(Modifier.fillMaxSize()) {
            PhoneLandscapeTopBar(m)
            Box(Modifier.weight(1f)) { TabContent(m) }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            // Accueil : l'image du film passe sous la barre du haut (plein écran) ; ailleurs, la barre est au-dessus
            if (m.tab == Tab.Home) Box(Modifier.weight(1f)) {
                TabContent(m)
                Box(Modifier.fillMaxWidth().height(96.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xCC000000), Color(0x00000000)))))
                PhoneTopBar(m)
            } else {
                PhoneTopBar(m)
                Box(Modifier.weight(1f)) { TabContent(m) }
            }
            PhoneBottomBar(m)
        }
    }
}

@Composable
private fun TabContent(m: AppModel) {
    CompositionLocalProvider(LocalPad provides if (m.isTv) 48.dp else 16.dp) { TabBody(m) }
}

@Composable
private fun TabBody(m: AppModel) {
    if (m.isTv) { TvSoftTabs(m); return }
    PhoneRevealTabs(m)
}

/** Téléphone : la nouvelle page s'ouvre en cercle qui grandit depuis l'icône touchée. */
@Composable
private fun PhoneRevealTabs(m: AppModel) {
    val land = isPhoneLandscape(false)
    val order = listOf(Tab.Home, Tab.Live, Tab.Movies, Tab.Series, Tab.Settings)
    val landOrder = listOf(Tab.Home, Tab.Live, Tab.Movies, Tab.Series, Tab.MyList)
    androidx.compose.animation.AnimatedContent(
        targetState = m.tab,
        transitionSpec = {
            (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(520), initialAlpha = 0.999f) togetherWith
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(520), targetAlpha = 0.35f)).apply { targetContentZIndex = 1f }
        },
        label = "reveal",
        modifier = Modifier.fillMaxSize()
    ) { tab ->
        val prog by transition.animateFloat(
            transitionSpec = { androidx.compose.animation.core.tween(520, easing = androidx.compose.animation.core.FastOutSlowInEasing) },
            label = "revealP"
        ) { st -> if (st == androidx.compose.animation.EnterExitState.PreEnter) 0f else 1f }
        val entering = transition.targetState == androidx.compose.animation.EnterExitState.Visible && prog < 1f
        val shape = remember(prog, tab, land) {
            androidx.compose.foundation.shape.GenericShape { sz, _ ->
                // Point de départ : l'icône de l'onglet (barre du bas, ou onglets du haut à l'horizontale)
                val list = if (land) landOrder else order
                val idx = list.indexOf(tab).let { if (it < 0) 2 else it }
                val ox = if (land) sz.width * (0.08f + idx * 0.07f) else sz.width * (idx + 0.5f) / list.size
                val oy = if (land) 0f else sz.height
                val maxR = kotlin.math.hypot(maxOf(ox, sz.width - ox), maxOf(oy, sz.height - oy))
                addOval(androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset(ox, oy), maxR * prog))
            }
        }
        Box(Modifier.fillMaxSize().then(if (entering) Modifier.clip(shape) else Modifier).background(Color.Black)) { TabPage(m, tab) }
    }
}

/**
 * TV : transition sobre — l'ancienne page s'efface vite, la nouvelle apparaît en fondu
 * avec un très léger glissement dans le sens de la flèche (style Apple TV / Google TV).
 */
@Composable
private fun TvSoftTabs(m: AppModel) {
    androidx.compose.animation.AnimatedContent(
        targetState = m.tab,
        transitionSpec = {
            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
            val ease = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
            (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(380, delayMillis = 90, easing = ease)) +
                androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(460, delayMillis = 60, easing = ease)) { w -> (dir * w * 0.04f).toInt() } +
                androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(460, delayMillis = 60, easing = ease), initialScale = 0.985f)) togetherWith
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(170))
        },
        label = "softTabs",
        modifier = Modifier.fillMaxSize()
    ) { tab -> TabPage(m, tab) }
}

/** TV : les pages tournent comme les faces d'un cube, dans le sens de la flèche. */
@Composable
private fun TvCubeTabs(m: AppModel) {
    val dirState = remember { androidx.compose.runtime.mutableIntStateOf(1) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    androidx.compose.animation.AnimatedContent(
        targetState = m.tab,
        transitionSpec = {
            dirState.intValue = if (targetState.ordinal > initialState.ordinal) 1 else -1
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(700), initialAlpha = 0.5f) togetherWith
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(700), targetAlpha = 0.3f)
        },
        label = "cube",
        modifier = Modifier.fillMaxSize()
    ) { tab ->
        val p by transition.animateFloat(
            transitionSpec = { androidx.compose.animation.core.tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing) },
            label = "cubeP"
        ) { st ->
            when (st) {
                androidx.compose.animation.EnterExitState.PreEnter -> 1f
                androidx.compose.animation.EnterExitState.Visible -> 0f
                androidx.compose.animation.EnterExitState.PostExit -> -1f
            }
        }
        val d = dirState.intValue
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val k = p * d
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (k > 0f) 0f else 1f, 0.5f)
                translationX = k * size.width
                rotationY = k * 90f
                cameraDistance = 14f * density
            }
        ) { TabPage(m, tab) }
    }
}

@Composable
private fun TabPage(m: AppModel, tab: Tab) {
    when (tab) {
        Tab.Home -> HomeScreen(m)
        Tab.Live -> LiveScreen(m)
        Tab.Movies -> VodBrowser(m, series = false)
        Tab.Series -> VodBrowser(m, series = true)
        Tab.MyList -> MyListScreen(m)
        Tab.Settings -> SettingsScreen(m)
    }
}

@Composable
private fun TvTopBar(m: AppModel) {
    val glass = Color(0x26FFFFFF)
    val edge = Color(0x33FFFFFF)
    Row(
        Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        LumoLogo(26)
        // Onglets dans une « pilule » translucide ; l'onglet sélectionné par la télécommande affiche son nom
        val fr = rememberAutoFocus(true)
        Row(
            Modifier.background(glass, RoundedCornerShape(50)).border(0.5.dp, edge, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(Tab.Home, Tab.Live, Tab.Movies, Tab.Series, Tab.MyList).forEach { t ->
                FocusBox(shape = RoundedCornerShape(50), scale = 1.04f, border = false, requester = if (t == m.tab) fr else null, onClick = { m.tab = t }) { f ->
                    // Changement d'onglet juste en passant dessus avec les flèches (pas besoin de OK)
                    LaunchedEffect(f) { if (f && m.tab != t) { kotlinx.coroutines.delay(220); m.tab = t } }
                    val sel = m.tab == t
                    val bg = when { f -> Color(0xF2FFFFFF); sel -> Color(0x26FFFFFF); else -> Color.Transparent }
                    val fg = when { f -> Color(0xFF111111); sel -> Color.White; else -> Color(0xFFB8B8C0) }
                    Row(
                        Modifier.height(34.dp).background(bg, RoundedCornerShape(50)).padding(horizontal = 11.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        androidx.compose.foundation.Canvas(Modifier.size(19.dp)) { drawTab(t, fg) }
                        androidx.compose.animation.AnimatedVisibility(f || sel) {
                            Text(if (t == Tab.Live) "Direct" else t.label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TvClock()
        Row(
            Modifier.background(glass, RoundedCornerShape(50)).border(0.5.dp, edge, RoundedCornerShape(50)).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconFocus(Icons.Default.Search, "Rechercher") { m.go(Screen.Search) }
            // Micro : recherche vocale directe
            FocusBox(shape = RoundedCornerShape(50), scale = 1.1f, onClick = { m.openSearch(voice = true) }) { _ ->
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Text("🎤", fontSize = 18.sp) }
            }
            IconFocus(Icons.Default.Settings, "Réglages") { m.tab = Tab.Settings }
            ProfileBadge(m) { m.openProfiles() }
        }
    }
}

/** Heure en haut à droite (mise à jour chaque minute). */
@Composable
private fun TvClock() {
    val fmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE) }
    var now by remember { mutableStateOf(fmt.format(java.util.Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            now = fmt.format(java.util.Date())
            kotlinx.coroutines.delay(60_000L - System.currentTimeMillis() % 60_000L + 50)
        }
    }
    Text(now, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun PhoneLandscapeTopBar(m: AppModel) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        LumoLogo(24)
        Spacer(Modifier.width(14.dp))
        listOf(Tab.Home, Tab.Live, Tab.Movies, Tab.Series, Tab.MyList).forEach { t ->
            FocusBox(shape = RoundedCornerShape(10.dp), scale = 1.03f, onClick = { m.tab = t }) { f ->
                TabChip(t, selected = m.tab == t, focused = f, small = true)
            }
        }
        Spacer(Modifier.weight(1f))
        IconFocus(Icons.Default.Search, "Rechercher") { m.go(Screen.Search) }
        IconFocus(Icons.Default.Settings, "Réglages") { m.tab = Tab.Settings }
        ProfileBadge(m) { m.openProfiles() }
    }
}

@Composable
private fun PhoneTopBar(m: AppModel) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LumoLogo(28)
        Spacer(Modifier.weight(1f))
        // Recherche + profil dans une pilule translucide (nouveau visuel)
        Row(
            Modifier.background(Color(0x26FFFFFF), RoundedCornerShape(50)).border(0.5.dp, Color(0x33FFFFFF), RoundedCornerShape(50)).padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconFocus(Icons.Default.Search, "Rechercher") { m.go(Screen.Search) }
            ProfileBadge(m) { m.openProfiles() }
            Spacer(Modifier.width(4.dp))
        }
        Spacer(Modifier.width(10.dp))
    }
}

@Composable
private fun PhoneBottomBar(m: AppModel) {
    val items = listOf(
        Triple(Tab.Home, Icons.Default.Home, "Accueil"),
        Triple(Tab.Live, Icons.Default.PlayArrow, "Direct"),
        Triple(Tab.Movies, Icons.Default.Star, "Films"),
        Triple(Tab.Series, Icons.Default.List, "Séries"),
        Triple(Tab.Settings, Icons.Default.Settings, "Réglages")
    )
    // Nouveau visuel : barre du bas en « pilule » translucide
    Box(Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).background(Color(0xFF16171C), RoundedCornerShape(50))
                .border(0.5.dp, Color(0x33FFFFFF), RoundedCornerShape(50)).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { (t, icon, label) ->
                val sel = m.tab == t
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(vertical = 6.dp).clip(RoundedCornerShape(50))
                        .background(if (sel) Color(0x26FFFFFF) else Color.Transparent).clickable { m.tab = t },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    Icon(icon, contentDescription = label, tint = if (sel) Color.White else C.Dim, modifier = Modifier.size(22.dp))
                    Text(label, fontSize = 10.sp, color = if (sel) Color.White else C.Dim, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun IconFocus(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    FocusBox(shape = RoundedCornerShape(50), scale = 1.1f, onClick = onClick) { _ ->
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = tint)
        }
    }
}

@Composable
private fun ProfileBadge(m: AppModel, onClick: () -> Unit) {
    val p = m.profile ?: return
    FocusBox(shape = androidx.compose.foundation.shape.CircleShape, scale = 1.1f, onClick = onClick) { _ ->
        ProfileAvatar(p.name, p.color, p.photo, 34.dp)
    }
}


/** Onglet en icône seule ; quand il a le focus : carré arrondi clair + nom en dessous (style Google TV). */
@Composable
private fun TabChip(t: Tab, selected: Boolean, focused: Boolean, small: Boolean = false) {
    val box = if (small) 38.dp else 46.dp
    val bg = when { focused -> Color(0xF2FFFFFF); selected -> Color(0x1AFFFFFF); else -> Color.Transparent }
    val fg = when { focused -> Color(0xFF111111); selected -> Color.White; else -> Color(0xFFB8B8C0) }
    Box(
        Modifier.size(box).background(bg, RoundedCornerShape(if (small) 10.dp else 12.dp)).semantics { contentDescription = t.label },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(box * 0.5f)) { drawTab(t, fg) }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTab(t: Tab, c: Color) {
    val w = size.width; val h = size.height
    val sw = w * 0.07f
    val st = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
    fun o(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(w * x, h * y)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(c, o(x1, y1), o(x2, y2), strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    when (t) {
        Tab.Home -> {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.08f, h * 0.48f); lineTo(w * 0.5f, h * 0.1f); lineTo(w * 0.92f, h * 0.48f)
                moveTo(w * 0.2f, h * 0.4f); lineTo(w * 0.2f, h * 0.9f); lineTo(w * 0.8f, h * 0.9f); lineTo(w * 0.8f, h * 0.4f)
            }
            drawPath(p, c, style = st)
            drawRoundRect(c, topLeft = o(0.42f, 0.6f), size = androidx.compose.ui.geometry.Size(w * 0.16f, h * 0.3f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.03f))
        }
        Tab.Live -> {
            // Télé avec antennes
            drawRoundRect(c, topLeft = o(0.05f, 0.3f), size = androidx.compose.ui.geometry.Size(w * 0.9f, h * 0.6f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f), style = st)
            line(0.3f, 0.06f, 0.5f, 0.28f); line(0.7f, 0.06f, 0.5f, 0.28f)
            drawCircle(c, radius = w * 0.07f, center = o(0.75f, 0.6f))
        }
        Tab.Movies -> {
            // Clap de cinéma avec bouton lecture
            drawRoundRect(c, topLeft = o(0.06f, 0.14f), size = androidx.compose.ui.geometry.Size(w * 0.88f, h * 0.74f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f), style = st)
            line(0.06f, 0.36f, 0.94f, 0.36f)
            for (x in listOf(0.26f, 0.44f, 0.62f, 0.8f)) line(x, 0.16f, x - 0.05f, 0.34f)
            val play = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.42f, h * 0.48f); lineTo(w * 0.64f, h * 0.62f); lineTo(w * 0.42f, h * 0.76f); close() }
            drawPath(play, c)
        }
        Tab.Series -> {
            // Écran avec pied (séries)
            drawRoundRect(c, topLeft = o(0.06f, 0.12f), size = androidx.compose.ui.geometry.Size(w * 0.88f, h * 0.58f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f), style = st)
            line(0.5f, 0.72f, 0.5f, 0.86f); line(0.3f, 0.88f, 0.7f, 0.88f)
        }
        Tab.MyList -> {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.5f, h * 0.88f)
                cubicTo(w * 0.1f, h * 0.6f, w * 0.0f, h * 0.35f, w * 0.18f, h * 0.2f)
                cubicTo(w * 0.32f, h * 0.08f, w * 0.46f, h * 0.18f, w * 0.5f, h * 0.3f)
                cubicTo(w * 0.54f, h * 0.18f, w * 0.68f, h * 0.08f, w * 0.82f, h * 0.2f)
                cubicTo(w * 1.0f, h * 0.35f, w * 0.9f, h * 0.6f, w * 0.5f, h * 0.88f)
                close()
            }
            drawPath(p, c, style = st)
        }
        Tab.Settings -> drawCircle(c, radius = w * 0.35f, center = o(0.5f, 0.5f), style = st)
    }
}
