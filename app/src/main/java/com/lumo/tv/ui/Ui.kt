package com.lumo.tv.ui

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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
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
    onClick: () -> Unit,
    content: @Composable BoxScope.(focused: Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (focused) scale else 1f, label = "focusScale")
    Box(
        modifier
            .graphicsLayer { scaleX = s; scaleY = s }
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
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
    onClick: () -> Unit
) {
    FocusBox(modifier = modifier.height(46.dp), shape = RoundedCornerShape(6.dp), scale = 1.04f, onClick = onClick) { _ ->
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
    onClick: () -> Unit
) {
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
                AsyncImage(model = image, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
