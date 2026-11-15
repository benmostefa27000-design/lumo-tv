package com.lumo.tv.ui

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
    val Red = Color(0xFFE11D2E)
    val Bg = Color(0xFF000000)
    val Card = Color(0xFF141414)
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
    FocusBox(modifier = modifier.height(46.dp), shape = RoundedCornerShape(6.dp), scale = 1.04f, requester = requester, onClick = onClick) { _ ->
        Row(
            Modifier
                .fillMaxHeight()
                .background(if (primary) Color.White else Color(0xB36D6D6E))
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) icon()
            Text(text, color = if (primary) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    FocusBox(shape = RoundedCornerShape(50), scale = 1.05f, onClick = onClick) { _ ->
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
    onClick: () -> Unit
) {
    // Affiche TMDB (meilleure qualité) si la clé est réglée, sinon celle du fournisseur
    val art = rememberTmdbArt(kind, title)
    val providerImg = image
    var tmdbFailed by remember(title) { mutableStateOf(false) }
    val image = (if (tmdbFailed) null else art?.poster?.ifBlank { null }) ?: image
    FocusBox(modifier = Modifier.width(width).aspectRatio(2f / 3f), onLongClick = onLongClick, onClick = onClick) { _ ->
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
