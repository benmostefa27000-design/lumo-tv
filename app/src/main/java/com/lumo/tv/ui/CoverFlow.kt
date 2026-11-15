package com.lumo.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

data class FlowItem(val id: String, val title: String, val image: String, val meta: String, val kind: String? = null)

/**
 * Défilement 3D des affiches (façon albums sur iPad) :
 * au doigt sur le téléphone, avec gauche/droite sur la télécommande.
 */
@Composable
fun CoverFlow(
    title: String?,
    items: List<FlowItem>,
    isTv: Boolean,
    startIndex: Int = 2,
    width: androidx.compose.ui.unit.Dp? = null,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    card: (@Composable (FlowItem) -> Unit)? = null,
    autoMs: Long = 0,
    showCaption: Boolean = true,
    onCurrent: ((Int) -> Unit)? = null,
    centerScale: Float = 1f,
    downTo: androidx.compose.ui.focus.FocusRequester? = null,
    selectable: Boolean = true,
    paused: Boolean = false,
    target: Int = -1,
    targetKey: Int = 0,
    reflection: Float = 0.3f,
    onOpen: (Int) -> Unit
) {
    if (items.isEmpty()) return
    val scope = rememberCoroutineScope()
    val last = items.lastIndex
    val pos = remember(items.size) { Animatable(min(startIndex.coerceAtLeast(0), last).toFloat()) }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val cardW = width ?: if (isTv) 200.dp else 170.dp
    val cardH = cardW * 1.5f
    // Écarts proportionnels à la taille des affiches (bonnes proportions quelle que soit la taille)
    val near1 = with(density) { (cardW * (if (isTv) 0.8f else 0.64f)).toPx() }
    val far = with(density) { (cardW * (if (isTv) 0.37f else 0.27f)).toPx() }
    // Fluidité : on ne recompose que quand l'affiche du milieu change ; le mouvement image par image
    // est calculé dans graphicsLayer (sans recomposition)
    val current by remember(items.size) { derivedStateOf { pos.value.roundToInt().coerceIn(0, last) } }
    var focused by remember { mutableStateOf(false) }

    var firstTick by remember { mutableStateOf(true) }
    LaunchedEffect(current) {
        if (!isTv && !firstTick) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        firstTick = false
    }

    fun goTo(i: Int) {
        scope.launch { pos.animateTo(i.coerceIn(0, last).toFloat(), spring(dampingRatio = 0.82f, stiffness = 260f)) }
    }
    // Défilement automatique (en pause quand la télécommande est dessus)
    var touchTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(autoMs, focused, paused, items.size, touchTick, targetKey) {
        while (autoMs > 0 && !focused && !paused && last > 0) {
            kotlinx.coroutines.delay(autoMs)
            val c = pos.value.roundToInt()
            pos.animateTo((if (c >= last) 0 else c + 1).toFloat(), androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.CubicBezierEasing(0.45f, 0f, 0.2f, 1f)))
        }
    }
    LaunchedEffect(current) { onCurrent?.invoke(current) }
    // Pilotage de l'extérieur (ex. ◀ ▶ sur les boutons de l'accueil TV)
    LaunchedEffect(targetKey) { if (targetKey > 0 && target >= 0) goTo(if (target > last) 0 else target) }

    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        if (title != null) SectionTitle(title, Modifier.padding(start = LocalPad.current, bottom = 4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(cardH * centerScale + cardH * reflection + 30.dp)
                .clipToBounds()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                // Télécommande : ▼ depuis les affiches → bouton « Lecture » (au lieu de sauter à la rangée suivante)
                .then(if (downTo != null) Modifier.focusProperties { down = downTo } else Modifier)
                .onFocusChanged { focused = it.hasFocus || it.isFocused }
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> if (current > 0) { goTo(current - 1); true } else false
                        Key.DirectionRight -> if (current < last) { goTo(current + 1); true } else false
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onOpen(current); true }
                        else -> false
                    }
                }
                .focusable(enabled = selectable)
                .pointerInput(items.size) {
                    val tracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = { tracker.resetTracking(); touchTick++; scope.launch { pos.stop() } },
                        onDragEnd = {
                            val v = tracker.calculateVelocity().x
                            goTo((pos.value - v / near1 * 0.3f).roundToInt())
                        },
                        onDragCancel = { goTo(pos.value.roundToInt()) },
                        onHorizontalDrag = { change, dx ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            change.consume()
                            scope.launch { pos.snapTo((pos.value - dx / near1).coerceIn(-0.4f, last + 0.4f)) }
                        }
                    )
                },
            contentAlignment = Alignment.TopCenter
        ) {
            val lo = max(0, current - 5)
            val hi = min(last, current + 5)
            val spread = near1 * (1f + (centerScale - 1f) * 1.1f)
            for (k in lo..hi) {
                val item = items[k]
                val isCenter = k == current
                key(item.id) {
                    Box(
                        Modifier
                            .zIndex(100f - abs(k - current) * 10f)
                            .padding(top = 14.dp + cardH * (centerScale - 1f) / 2f)
                            .size(cardW, cardH)
                            .graphicsLayer {
                                // Position lue ici (à chaque image) → pas de recomposition pendant l'animation
                                val d = k - pos.value
                                val a = abs(d)
                                val s = sign(d)
                                val near = min(a, 1f)
                                translationX = s * (near * spread + max(a - 1f, 0f) * far)
                                rotationY = -s * near * 55f
                                cameraDistance = 14f * density.density
                                // L'affiche grandit progressivement en arrivant au milieu (courbe douce)
                                val t = 1f - near
                                val grow = 1f + (centerScale - 1f) * (t * t * (3f - 2f * t))
                                val sc = (1f - 0.14f * near) * grow
                                scaleX = sc; scaleY = sc
                                alpha = if (a > 4.6f) 0f else 1f
                            }
                            .pointerInput(k) { detectTapGestures { if (k == pos.value.roundToInt()) onOpen(k) else goTo(k) } }
                    ) {
                        // Une seule image chargée par affiche, réutilisée pour le reflet (moins de travail = plus fluide)
                        val imgUrl = if (card == null) (rememberTmdbArt(item.kind, item.title)?.poster?.ifBlank { null } ?: item.image) else ""
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        val pxW = with(density) { cardW.roundToPx() }
                        val painter = if (imgUrl.isNotBlank()) coil.compose.rememberAsyncImagePainter(
                            coil.request.ImageRequest.Builder(ctx).data(imgUrl).size(pxW, (pxW * 1.5f).toInt()).crossfade(250).build()
                        ) else null
                        Box(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(C.Card2)
                                // Assombrir les affiches sur les côtés (calculé au dessin, sans recomposition)
                                .drawWithContent {
                                    drawContent()
                                    val a = abs(k - pos.value)
                                    if (a > 0.05f) drawRect(Color.Black.copy(alpha = min(a, 3f) * 0.16f))
                                }
                                .then(if (isCenter && focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
                        ) {
                            if (card != null) card(item) else {
                                Text(item.title, color = C.Sub, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(10.dp))
                                if (painter != null) androidx.compose.foundation.Image(painter, item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            // Assombrir les affiches sur les côtés
                        }
                        // Reflet sous l'affiche
                        Box(
                            Modifier.offset(y = cardH + 6.dp).fillMaxWidth().height(cardH * reflection).clipToBounds()
                        ) {
                            if (card != null) Box(Modifier.requiredSize(cardW, cardH).align(Alignment.TopCenter).graphicsLayer { scaleY = -1f; alpha = 0.35f }.clip(RoundedCornerShape(8.dp))) { card(item) }
                            else if (painter != null) androidx.compose.foundation.Image(
                                painter, null, contentScale = ContentScale.Crop,
                                modifier = Modifier.requiredSize(cardW, cardH).align(Alignment.TopCenter).graphicsLayer { scaleY = -1f; alpha = 0.35f }
                            )
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x66000000), Color.Black))))
                        }
                    }
                }
            }
        }
        val cur = items[current]
        if (showCaption) Text(cur.title, color = Color.White, fontSize = if (isTv) 26.sp else 22.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        if (showCaption && cur.meta.isNotBlank()) Text(cur.meta, color = C.Sub, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (showCaption) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
            val start = (current - 6).coerceAtLeast(0)
            val end = (start + 12).coerceAtMost(last)
            for (k in start..end) {
                Box(
                    Modifier.padding(horizontal = 2.5.dp).height(5.dp).width(if (k == current) 18.dp else 5.dp)
                        .background(if (k == current) C.Red else Color(0xFF4A4A4A), RoundedCornerShape(3.dp))
                )
            }
        }
    }
}
