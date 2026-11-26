package com.lumo.tv.ui

import androidx.compose.ui.layout.layout

import androidx.compose.ui.draw.rotate

import androidx.compose.ui.input.key.type

import androidx.compose.ui.input.key.key

import androidx.compose.ui.input.key.onPreviewKeyEvent

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

object C {
    /** Style d'affichage courant (fixé à la racine de l'app, voir LumoApp). */
    var style: String = "classic"
    val Red = Color(0xFFE11D2E)
    val Bg: Color get() = when (style) { "night" -> Color(0xFF060B1A); "amber" -> Color(0xFF130B09); else -> Color(0xFF000000) }
    val Card: Color get() = when (style) { "night" -> Color(0xFF101A33); "amber" -> Color(0xFF26170F); else -> Color(0xFF141414) }
    val Card2 = Color(0xFF2A2A2A)
    val Text = Color(0xFFFFFFFF)
    val Sub = Color(0xFFB3B3B3)
    val Dim = Color(0xFF8C8C8C)
    val Green = Color(0xFF46D369)
}

@Composable
fun LumoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Red,
            onPrimary = Color.White,
            background = C.Bg,
            onBackground = C.Text,
            surface = C.Card,
            onSurface = C.Text,
            surfaceVariant = C.Card2,
            onSurfaceVariant = C.Sub
        ),
        content = content
    )
}

/** Zone cliquable qui grossit et s'entoure de blanc quand elle a le focus (télécommande). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
    scale: Float = 1.06f,
    onLongClick: (() -> Unit)? = null,
    requester: FocusRequester? = null,
    border: Boolean = true,
    onClick: () -> Unit,
    content: @Composable BoxScope.(focused: Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (focused) scale else 1f, label = "focusScale")
    Box(
        modifier
            .graphicsLayer { scaleX = s; scaleY = s }
            .onFocusChanged { focused = it.isFocused }
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            // Télécommande : appui long sur OK (ou bouton Menu ≡) = action secondaire (ex. supprimer de « Reprendre »)
            .then(if (onLongClick == null) Modifier else Modifier.onPreviewKeyEvent { e ->
                val k = e.key
                val ok = k == androidx.compose.ui.input.key.Key.DirectionCenter || k == androidx.compose.ui.input.key.Key.Enter || k == androidx.compose.ui.input.key.Key.NumPadEnter
                when {
                    k == androidx.compose.ui.input.key.Key.Menu && e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown -> { onLongClick(); true }
                    ok && e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 -> {
                        if (!longFired) { longFired = true; onLongClick() }
                        true
                    }
                    ok && e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp && longFired -> { longFired = false; true }
                    else -> false
                }
            })
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (focused && border) Modifier.border(3.dp, Color.White, shape) else Modifier)
    ) {
        content(focused)
    }
}

@Composable
fun LumoLogo(size: Int = 32) {
    Text("LUMO", color = C.Red, fontSize = size.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
}

/** Bouton blanc (principal) ou gris (secondaire). */
@Composable
fun LumoButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    requester: FocusRequester? = null,
    onClick: () -> Unit
) {
    // Boutons en « pilule » (nouveau visuel)
    FocusBox(modifier = modifier.height(46.dp), shape = RoundedCornerShape(50), scale = 1.05f, requester = requester, onClick = onClick) { _ ->
        Row(
            Modifier
                .fillMaxHeight()
                .background(if (primary) Color.White else Color(0x40FFFFFF))
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) icon()
            Text(text, color = if (primary) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, requester: FocusRequester? = null, onClick: () -> Unit) {
    FocusBox(shape = RoundedCornerShape(50), scale = 1.05f, requester = requester, onClick = onClick) { _ ->
        Box(
            Modifier
                .background(if (selected) Color.White else Color.Transparent)
                .border(1.dp, if (selected) Color.White else Color(0xFF3A3A3A), RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 9.dp)
        ) {
            Text(text, color = if (selected) Color.Black else C.Text, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
        }
    }
}

/** Affiche de film ou de série, avec barre de progression optionnelle. */
@Composable
fun PosterCard(
    title: String,
    image: String,
    width: Dp,
    progress: Float? = null,
    badge: String? = null,
    onLongClick: (() -> Unit)? = null,
    kind: String? = null,
    requester: FocusRequester? = null,
    onClick: () -> Unit
) {
    // Affiche TMDB (meilleure qualité) si la clé est réglée, sinon celle du fournisseur
    val art = rememberTmdbArt(kind, title)
    val providerImg = image
    var tmdbFailed by remember(title) { mutableStateOf(false) }
    val image = (if (tmdbFailed) null else art?.poster?.ifBlank { null }) ?: image
    FocusBox(modifier = Modifier.width(width).aspectRatio(2f / 3f), onLongClick = onLongClick, requester = requester, onClick = onClick) { _ ->
        Box(Modifier.fillMaxSize().background(C.Card2)) {
            Text(
                title,
                color = C.Sub,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center).padding(8.dp),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            if (image.isNotBlank()) {
                AsyncImage(model = image, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    onError = { if (image != providerImg) tmdbFailed = true })
            }
            if (badge != null) {
                Text(
                    badge,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(6.dp).background(Color(0x99000000), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(C.Red))
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = C.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = modifier)
}

@Composable
fun Loading(text: String = "Chargement…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(color = C.Red)
            Text(text, color = C.Sub)
        }
    }
}

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        Text(text, color = C.Dim, fontSize = 15.sp)
    }
}

fun bottomFade() = Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
fun leftFade() = Brush.horizontalGradient(listOf(Color.Black, Color(0xCC000000), Color.Transparent))

@Composable
fun lumoTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color.White,
    unfocusedBorderColor = Color(0xFF5A5A5A),
    focusedLabelColor = Color.White,
    unfocusedLabelColor = C.Dim,
    cursorColor = C.Red,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White
)

/** Sur TV, place la sélection sur un élément dès l'ouverture de l'écran. */
@Composable
fun rememberAutoFocus(enabled: Boolean, key: Any? = Unit): FocusRequester? {
    if (!enabled) return null
    val fr = remember { FocusRequester() }
    LaunchedEffect(key) {
        kotlinx.coroutines.delay(150)
        try { fr.requestFocus() } catch (_: Exception) { }
    }
    return fr
}

/** Image TMDB d'un film (« movie ») ou d'une série (« tv ») ; null tant qu'elle n'est pas trouvée. */
@Composable
fun rememberTmdbArt(kind: String?, name: String): com.lumo.tv.data.TmdbArt.Art? {
    if (kind == null) return null
    val key = com.lumo.tv.data.TmdbArt.key
    val initial = remember(kind, name, key) { if (key.isBlank()) null else com.lumo.tv.data.TmdbArt.cached(kind, name)?.takeIf { it.poster.isNotBlank() || it.backdrop.isNotBlank() } }
    val st = produceState(initial, kind, name, key) {
        if (value == null && key.isNotBlank()) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.lumo.tv.data.TmdbArt.lookup(kind, name) }
    }
    return st.value
}


/** Photo de profil (JPEG en base64) → image ; null si pas de photo. */
@Composable
fun rememberProfilePhoto(b64: String): androidx.compose.ui.graphics.ImageBitmap? = remember(b64) {
    if (b64.isBlank()) null else try {
        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: Exception) { null }
}

/** Avatar rond : photo si elle existe, sinon initiale sur la couleur du profil. */
@Composable
fun ProfileAvatar(name: String, color: Long, photo: String, size: Dp, modifier: Modifier = Modifier) {
    val img = rememberProfilePhoto(photo)
    Box(
        modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(color), Color(color).copy(alpha = 0.65f)))),
        contentAlignment = Alignment.Center
    ) {
        if (img != null) androidx.compose.foundation.Image(img, name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(name.take(1).uppercase(), color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold)
    }
}


/** Téléphone tenu à l'horizontale. */
@Composable
fun isPhoneLandscape(isTv: Boolean): Boolean =
    !isTv && androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

/** Hauteur de l'écran en dp. */
@Composable
fun screenHeightDp(): Dp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp


// ---------------- Nouveau visuel « cinéma » ----------------

/** Logo du titre TMDB s'il existe (comme sur Netflix), sinon le titre en majuscules espacées. */
@Composable
fun TitleArt(raw: String, kind: String, fontSize: androidx.compose.ui.unit.TextUnit, logoHeight: androidx.compose.ui.unit.Dp, maxLines: Int = 2) {
    val key = com.lumo.tv.data.TmdbArt.key
    val logo by produceState(initialValue = "", kind, raw, key) {
        value = if (key.isBlank()) "" else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.lumo.tv.data.TmdbArt.logo(kind, raw) }
    }
    var failed by remember(raw) { mutableStateOf(false) }
    if (logo.isNotBlank() && !failed) {
        coil.compose.AsyncImage(
            model = logo, contentDescription = raw, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            alignment = Alignment.CenterStart, onError = { failed = true },
            modifier = Modifier.height(logoHeight).fillMaxWidth()
        )
    } else CinemaTitle(raw, fontSize, maxLines)
}

/** Titre façon affiche de cinéma : majuscules fines et espacées. */
@Composable
fun CinemaTitle(raw: String, fontSize: androidx.compose.ui.unit.TextUnit, maxLines: Int = 2, center: Boolean = false) {
    val clean = com.lumo.tv.data.TmdbArt.split(raw).first.ifBlank { raw }
    Text(
        clean.uppercase(), color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Normal,
        letterSpacing = (fontSize.value * 0.16f).sp, lineHeight = (fontSize.value * 1.15f).sp,
        maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        textAlign = if (center) androidx.compose.ui.text.style.TextAlign.Center else null
    )
}

/** Langue / qualité lues dans le nom du fournisseur : « FR - X 4K » → [FR, 4K]. */
fun versionBadges(name: String): List<String> {
    val out = mutableListOf<String>()
    Regex("""^\s*([A-Za-z]{2,4})\s*[-|:]""").find(name)?.groupValues?.get(1)?.uppercase()?.let { out.add(it) }
    Regex("""(?i)\b(vostfr|multi|4k|uhd|fhd|hdr|hd|3d)\b""").findAll(name).forEach { val t = it.value.uppercase(); if (t !in out) out.add(t) }
    return out
}

/** « 01:33:00 » ou « 93 » → « 1 h 33 ». */
fun prettyDuration(d: String?): String? {
    if (d.isNullOrBlank()) return null
    val parts = d.split(":").mapNotNull { it.trim().toIntOrNull() }
    val min = when {
        parts.size >= 2 -> parts[0] * 60 + parts[1]
        parts.size == 1 -> parts[0]
        else -> return null
    }
    if (min <= 0) return null
    return if (min >= 60) "${min / 60} h ${"%02d".format(min % 60)}" else "$min min"
}

/** Ligne d'infos : textes séparés par « · » et petits badges encadrés (HD, VF…). */
@Composable
fun MetaLine(texts: List<String>, badges: List<String> = emptyList(), fontSize: androidx.compose.ui.unit.TextUnit = 14.sp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        texts.firstOrNull()?.let { Text(it, color = Color(0xFFD8D8D8), fontSize = fontSize) }
        badges.forEach { b ->
            Text(b, color = Color(0xFFE6E6E6), fontSize = (fontSize.value - 2).sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.border(0.8.dp, Color(0x99FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
        }
        texts.drop(1).forEach { Text(it, color = Color(0xFFD8D8D8), fontSize = fontSize, maxLines = 1) }
    }
}


/** Bouton « Bande-annonce » : ouvre la vidéo dans YouTube (ou le navigateur). Caché si aucune n'est trouvée. */
@Composable
fun TrailerButton(kind: String, raw: String, providerKey: String? = null) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val tk = com.lumo.tv.data.TmdbArt.key
    val key by produceState(initialValue = providerKey?.takeIf { it.length in 8..15 && !it.contains("/") } ?: "", kind, raw, tk) {
        if (value.isBlank() && tk.isNotBlank()) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.lumo.tv.data.TmdbArt.trailer(kind, raw) }
    }
    if (key.isBlank()) return
    LumoButton("Bande-annonce", primary = false, icon = { Text("▶", color = Color.White, fontSize = 13.sp) }) {
        val app = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$key")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$key")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(app) } catch (_: Exception) { try { ctx.startActivity(web) } catch (_: Exception) { } }
    }
}


/** Couleurs du style « verre » (null = style classique). Verre simulé : pas de flou réel, pour rester fluide sur TV. */
class Glass(
    val glow: Color,          // lueur du fond
    val panelTop: Color,      // haut du panneau translucide
    val panelBottom: Color,   // bas du panneau
    val edge: Color,          // bordure lumineuse
    val item: Color,          // fond d'un élément non sélectionné
    val dim: Color,           // texte secondaire
    val onPill: Color,        // texte/icône sur la pilule blanche
    val stripText: Color      // texte vertical du bandeau blanc
)

fun glassOf(style: String): Glass? = when (style) {
    "night" -> Glass(Color(0xFF1F3C7A), Color(0x5A6E96F0), Color(0xA0101A38), Color(0x40FFFFFF), Color(0x1FFFFFFF), Color(0xFFC8D3EE), Color(0xFF0B1530), Color(0xFF7F8DB0))
    "amber" -> Glass(Color(0xFF6E3A28), Color(0x66E0A08A), Color(0xA6402018), Color(0x45FFFFFF), Color(0x22FFFFFF), Color(0xFFF3DCD2), Color(0xFF4A1B0C), Color(0xFFB0806E))
    else -> null
}

/** Fond de l'app en style verre : une lueur colorée en haut, puis la couleur de fond. */
fun Modifier.glassBackground(g: Glass?): Modifier = if (g == null) this.background(C.Bg) else this.background(C.Bg).background(
    androidx.compose.ui.graphics.Brush.radialGradient(listOf(g.glow, Color.Transparent), center = androidx.compose.ui.geometry.Offset(1400f, -200f), radius = 1600f)
)

/** Panneau en verre : dégradé translucide + bordure lumineuse. */
fun Modifier.glassPanel(g: Glass, shape: Shape, strong: Boolean = false): Modifier = this
    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(if (strong) g.panelTop.copy(alpha = (g.panelTop.alpha + 0.25f).coerceAtMost(1f)) else g.panelTop, if (strong) g.panelBottom.copy(alpha = 0.92f) else g.panelBottom)), shape)
    .border(1.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(g.edge, g.edge.copy(alpha = 0.08f))), shape)

/** Texte écrit à la verticale (de bas en haut). */
fun Modifier.verticalText(): Modifier = this.rotate(-90f).layout { measurable, _ ->
    val p = measurable.measure(androidx.compose.ui.unit.Constraints())
    layout(p.height, p.width) { p.place((p.height - p.width) / 2, (p.width - p.height) / 2) }
}
