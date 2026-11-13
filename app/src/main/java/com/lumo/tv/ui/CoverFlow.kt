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
import androidx.compose.ui.focus.onFocusChanged
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

data class FlowItem(val id: String, val title: String, val image: String, val meta: String)

/**
 * Défilement 3D des affiches (façon albums sur iPad) :
 * au doigt sur le téléphone, avec gauche/droite sur la télécommande.
 */
@Composable
fun CoverFlow(title: String, items: List<FlowItem>, isTv: Boolean, onOpen: (Int) -> Unit) {
    if (items.isEmpty()) return
    val scope = rememberCoroutineScope()
    val last = items.lastIndex
    val pos = remember(items.size) { Animatable(min(2, last).toFloat()) }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val cardW = if (isTv) 200.dp else 170.dp
    val cardH = cardW * 1.5f
    val near1 = with(density) { (if (isTv) 160.dp else 108.dp).toPx() }
    val far = with(density) { (if (isTv) 75.dp else 46.dp).toPx() }
    val current = pos.value.roundToInt().coerceIn(0, last)
    var focused by remember { mutableStateOf(false) }

    var firstTick by remember { mutableStateOf(true) }
    LaunchedEffect(current) {
        if (!isTv && !firstTick) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        firstTick = false
    }

    fun goTo(i: Int) {
        scope.launch { pos.animateTo(i.coerceIn(0, last).toFloat(), spring(dampingRatio = 0.82f, stiffness = 260f)) }
    }

    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        SectionTitle(title, Modifier.padding(start = LocalPad.current, bottom = 4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(cardH + cardH * 0.3f + 30.dp)
                .clipToBounds()
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
                .focusable()
                .pointerInput(items.size) {
                    val tracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = { tracker.resetTracking(); scope.launch { pos.stop() } },
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
            val p = pos.value
            val lo = max(0, floor(p - 5f).toInt())
            val hi = min(last, ceil(p + 5f).toInt())
            for (k in lo..hi) {
                val item = items[k]
                val d = k - p
                val a = abs(d)
                val s = sign(d)
                val near = min(a, 1f)
                val x = s * (near * near1 + max(a - 1f, 0f) * far)
                val isCenter = k == current
                key(item.id) {
                    Box(
                        Modifier
                            .zIndex(100f - a * 10f)
                            .padding(top = 14.dp)
                            .size(cardW, cardH)
                            .graphicsLayer {
                                translationX = x
                                rotationY = -s * near * 55f
                                cameraDistance = 14f * density.density
                                val sc = 1f - 0.14f * near
                                scaleX = sc; scaleY = sc
                                alpha = if (a > 4.6f) 0f else 1f
                            }
                            .pointerInput(k) { detectTapGestures { if (k == pos.value.roundToInt()) onOpen(k) else goTo(k) } }
                    ) {
                        Box(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(C.Card2)
                                .then(if (isCenter && focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
                        ) {
                            Text(item.title, color = C.Sub, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(10.dp))
                            if (item.image.isNotBlank()) AsyncImage(model = item.image, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            // Assombrir les affiches sur les côtés
                            if (a > 0.05f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (min(a, 3f) * 0.16f))))
                        }
                        // Reflet sous l'affiche
                        Box(
                            Modifier.offset(y = cardH + 6.dp).fillMaxWidth().height(cardH * 0.3f).clipToBounds()
                        ) {
                            if (item.image.isNotBlank()) AsyncImage(
                                model = item.image, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.requiredSize(cardW, cardH).align(Alignment.TopCenter).graphicsLayer { scaleY = -1f; alpha = 0.35f }
                            )
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x66000000), Color.Black))))
                        }
                    }
                }
            }
        }
        val cur = items[current]
        Text(cur.title, color = Color.White, fontSize = if (isTv) 26.sp else 22.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        if (cur.meta.isNotBlank()) Text(cur.meta, color = C.Sub, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
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
