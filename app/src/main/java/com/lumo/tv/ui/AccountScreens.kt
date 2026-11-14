package com.lumo.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.lumo.tv.AppModel
import com.lumo.tv.Screen
import com.lumo.tv.data.Account
import com.lumo.tv.data.Profile
import kotlinx.coroutines.launch

// ---------------- Connexion ----------------

@Composable
fun LoginScreen(m: AppModel) {
    if (m.isTv && m.sync.enabled) {
        Row(Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Box(Modifier.weight(1f)) { PairTvPanel(m) }
            Box(Modifier.weight(1f)) { LoginForm(m) }
        }
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 520.dp).padding(24.dp)) { LoginForm(m) }
        }
    }
}

@Composable
private fun LoginForm(m: AppModel) {
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        LumoLogo(40)
        Text(if (m.isTv) "Ou connecte-toi à la main" else "Connexion", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Mets les infos de ton abonnement (les mêmes que dans IPTV Smarters). Tu peux aussi coller ton lien M3U complet dans le premier champ.", color = C.Sub, fontSize = 14.sp)
        OutlinedTextField(server, { server = it }, label = { Text("Serveur ou lien M3U") }, singleLine = true, colors = lumoTextFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it }, label = { Text("Nom d'utilisateur") }, singleLine = true, colors = lumoTextFieldColors(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("Mot de passe") }, singleLine = true, colors = lumoTextFieldColors(),
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = Color(0xFFFF6B75), fontSize = 14.sp) }
        LumoButton(if (busy) "Connexion…" else "Se connecter", modifier = Modifier.fillMaxWidth()) {
            if (busy) return@LumoButton
            val acc = Account.parse(server, user, pass)
            if (acc == null) { error = "Vérifie le serveur et le nom d'utilisateur"; return@LumoButton }
            busy = true; error = null
            scope.launch {
                error = m.login(acc)
                busy = false
            }
        }
    }
}

@Composable
private fun PairTvPanel(m: AppModel) {
    var code by remember { mutableStateOf(m.newPairCode()) }
    var status by remember { mutableStateOf("En attente du téléphone…") }
    LaunchedEffect(code) {
        val ok = m.waitForPairing(code)
        if (!ok) { status = "Code expiré, nouveau code ci-dessous"; code = m.newPairCode() }
    }
    val qr = remember(code) { qrBitmap("lumo://pair?code=$code", 512) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Connecte cette TV avec ton téléphone", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("1. Ouvre Lumo sur ton téléphone\n2. Réglages › Connecter une TV\n3. Scanne le QR code ou tape le code", color = C.Sub, fontSize = 17.sp, lineHeight = 28.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Box(Modifier.size(200.dp).background(Color.White, RoundedCornerShape(10.dp)).padding(12.dp)) {
                Image(qr.asImageBitmap(), contentDescription = "QR code de connexion", modifier = Modifier.fillMaxSize())
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(code.chunked(3).joinToString("-"), color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
                Text(status, color = C.Dim, fontSize = 14.sp)
            }
        }
    }
}

fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bmp
}

// ---------------- Téléphone : connecter une TV ----------------

@Composable
fun PairPhoneScreen(m: AppModel) {
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Connecter une TV", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Ouvre Lumo sur la TV : un code s'affiche. Tape-le ici, ou scanne le QR code de la TV avec l'appareil photo du téléphone.", color = C.Sub)
        OutlinedTextField(code, { code = it.uppercase().take(7) }, label = { Text("Code de la TV") }, singleLine = true, colors = lumoTextFieldColors(), modifier = Modifier.fillMaxWidth())
        msg?.let { Text(it, color = if (it.startsWith("TV")) C.Green else Color(0xFFFF6B75)) }
        LumoButton(if (busy) "Envoi…" else "Connecter la TV", modifier = Modifier.fillMaxWidth()) {
            if (busy) return@LumoButton
            busy = true
            scope.launch {
                val err = m.sendPairing(code)
                msg = err ?: "TV connectée ✓ Elle va s'ouvrir toute seule."
                busy = false
            }
        }
        LumoButton("Retour", primary = false, modifier = Modifier.fillMaxWidth()) { m.back() }
    }
}

// ---------------- Profils ----------------

val profileColors = listOf(0xFF2F6FD6, 0xFFE11D2E, 0xFFF2B33D, 0xFF1F9D6A, 0xFF8E44AD, 0xFFE67E22)

@Composable
fun ProfilesScreen(m: AppModel) {
    var manage by remember { mutableStateOf(false) }
    val profiles = run { m.store.version; m.store.profiles() }
    val canAdd = profiles.size < 6
    // Profils en défilement 3D (façon iPad), comme les « Nouveautés »
    val items = profiles.map { FlowItem(it.id, it.name, "", if (it.kids) "Profil Enfants" else "") } +
        (if (canAdd) listOf(FlowItem("__add", "Ajouter un profil", "", "")) else emptyList())
    val startIdx = profiles.indexOfFirst { it.id == m.profile?.id }.coerceAtLeast(0)
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(m.isTv) { if (m.isTv) { kotlinx.coroutines.delay(200); try { fr.requestFocus() } catch (_: Exception) { } } }
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF1B1F2A), Color(0xFF0B0C10), Color.Black)))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) { LumoLogo(36) }
            Spacer(Modifier.height(if (m.isTv) 28.dp else 40.dp))
            Text(if (manage) "Choisis le profil à modifier" else "Qui regarde ?", color = Color.White, fontSize = if (m.isTv) 40.sp else 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            key(items.size) {
                CoverFlow(
                    title = null, items = items, isTv = m.isTv, startIndex = startIdx,
                    width = if (m.isTv) 210.dp else 190.dp, focusRequester = fr,
                    card = { fi -> ProfileCard(profiles.firstOrNull { p -> p.id == fi.id }, manage) }
                ) { i ->
                    val p = profiles.getOrNull(i)
                    when {
                        p == null -> m.go(Screen.ProfileEdit(null))
                        manage -> m.go(Screen.ProfileEdit(p))
                        else -> m.selectProfile(p)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            LumoButton(if (manage) "Terminé" else "Gérer les profils", primary = false) { manage = !manage }
        }
    }
}

/** Carte de profil : grande photo (ou initiale sur dégradé de couleur). */
@Composable
private fun ProfileCard(p: Profile?, manage: Boolean) {
    if (p == null) {
        Box(Modifier.fillMaxSize().background(Color(0xFF1C1F27)).border(2.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Add, "Ajouter un profil", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(64.dp))
        }
        return
    }
    val img = rememberProfilePhoto(p.photo)
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(p.color), Color(p.color).copy(alpha = 0.55f), Color(0xFF111111)))), contentAlignment = Alignment.Center) {
        if (img != null) Image(img, p.name, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(p.name.take(1).uppercase(), color = Color.White, fontSize = 110.sp, fontWeight = FontWeight.Black)
        if (manage) Box(Modifier.fillMaxSize().background(Color(0x77000000)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Edit, "Modifier", tint = Color.White, modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
private fun ProfileTile(p: Profile, size: androidx.compose.ui.unit.Dp, edit: Boolean, requester: androidx.compose.ui.focus.FocusRequester? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FocusBox(modifier = Modifier.size(size), shape = androidx.compose.foundation.shape.CircleShape, scale = 1.12f, requester = requester, onClick = onClick) { f ->
            focused = f
            Box(Modifier.fillMaxSize().then(if (f) Modifier.border(4.dp, Color.White, androidx.compose.foundation.shape.CircleShape) else Modifier).padding(if (f) 6.dp else 0.dp)) {
                ProfileAvatar(p.name, p.color, p.photo, size)
                if (edit) {
                    Box(Modifier.fillMaxSize().background(Color(0x88000000), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Edit, "Modifier", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                }
            }
        }
        Text(p.name, color = if (focused) Color.White else C.Sub, fontSize = 17.sp, fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Petite mise en ligne qui passe à la ligne (évite les API expérimentales). */
@Composable
fun FlowRowCompat(spacing: androidx.compose.ui.unit.Dp, center: Boolean = true, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = content) { measurables, constraints ->
        val sp = spacing.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val rows = mutableListOf<MutableList<androidx.compose.ui.layout.Placeable>>()
        var cur = mutableListOf<androidx.compose.ui.layout.Placeable>()
        var w = 0
        placeables.forEach { p ->
            if (cur.isNotEmpty() && w + sp + p.width > constraints.maxWidth) { rows.add(cur); cur = mutableListOf(); w = 0 }
            w += (if (cur.isEmpty()) 0 else sp) + p.width
            cur.add(p)
        }
        if (cur.isNotEmpty()) rows.add(cur)
        val height = rows.sumOf { r -> r.maxOf { it.height } } + sp * (rows.size - 1).coerceAtLeast(0)
        layout(constraints.maxWidth, height) {
            var y = 0
            rows.forEach { r ->
                val rw = r.sumOf { it.width } + sp * (r.size - 1)
                var x = if (center) (constraints.maxWidth - rw) / 2 else 0
                r.forEach { p -> p.placeRelative(x, y); x += p.width + sp }
                y += r.maxOf { it.height } + sp
            }
        }
    }
}

@Composable
fun ProfileEditScreen(m: AppModel, existing: Profile?) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var color by remember { mutableStateOf(existing?.color ?: profileColors[(m.store.profiles().size) % profileColors.size]) }
    var kids by remember { mutableStateOf(existing?.kids ?: false) }
    var pin by remember { mutableStateOf(m.store.pin()) }
    var photo by remember { mutableStateOf(existing?.photo ?: "") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) photoToBase64(ctx, uri)?.let { photo = it } ?: m.showToast("Impossible de lire cette photo")
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(if (existing == null) "Nouveau profil" else "Modifier le profil", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            ProfileAvatar(name.ifBlank { "?" }, color, photo, 96.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!m.isTv) LumoButton(if (photo.isBlank()) "Choisir une photo" else "Changer la photo", primary = false) { picker.launch("image/*") }
                else Text("La photo se choisit depuis le téléphone", color = C.Dim, fontSize = 13.sp)
                if (photo.isNotBlank()) LumoButton("Retirer la photo", primary = false) { photo = "" }
            }
        }
        OutlinedTextField(name, { name = it.take(20) }, label = { Text("Prénom") }, singleLine = true, colors = lumoTextFieldColors(), modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth())
        Text("Couleur", color = C.Sub)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            profileColors.forEach { c ->
                FocusBox(modifier = Modifier.size(48.dp), shape = RoundedCornerShape(50), scale = 1.12f, onClick = { color = c }) { _ ->
                    Box(Modifier.fillMaxSize().background(Color(c)).then(if (color == c) Modifier.border(3.dp, Color.White, RoundedCornerShape(50)) else Modifier))
                }
            }
        }
        FocusBox(scale = 1.03f, onClick = { kids = !kids }) { _ ->
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(24.dp).background(if (kids) C.Red else Color.Transparent, RoundedCornerShape(5.dp)).border(2.dp, if (kids) C.Red else Color(0xFF5A5A5A), RoundedCornerShape(5.dp)))
                Column {
                    Text("Profil Enfants", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Seulement les catégories jeunesse et dessins animés", color = C.Dim, fontSize = 13.sp)
                }
            }
        }
        if (kids) {
            OutlinedTextField(
                pin, { pin = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("Code parental (pour quitter ce profil)") }, singleLine = true, colors = lumoTextFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LumoButton("Enregistrer") {
                if (kids && pin.length < 4) { m.showToast("Choisis un code d'au moins 4 chiffres"); return@LumoButton }
                val n = name.trim().ifBlank { "Profil" }
                val order = existing?.order ?: (m.store.profiles().maxOfOrNull { it.order } ?: 0) + 1
                m.commit(m.store.saveProfile(Profile(existing?.id ?: "p_${System.currentTimeMillis()}", n, color, kids, order, photo)))
                if (kids && pin != m.store.pin()) m.commit(m.store.setPin(pin))
                m.back()
            }
            if (existing != null && m.store.profiles().size > 1) {
                LumoButton("Supprimer", primary = false) {
                    m.store.deleteProfile(existing.id).forEach { m.commit(it) }
                    m.back()
                }
            }
            LumoButton("Annuler", primary = false) { m.back() }
        }
    }
}


/** Photo choisie → carré centré 400 px, JPEG, en base64 (léger, synchronisé avec la TV). */
private fun photoToBase64(ctx: android.content.Context, uri: android.net.Uri): String? = try {
    val src = ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: throw Exception()
    // Orientation EXIF (photos prises au téléphone)
    val rot = if (android.os.Build.VERSION.SDK_INT < 24) 1 else try {
        ctx.contentResolver.openInputStream(uri)?.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } ?: 1
    } catch (_: Exception) { 1 }
    val deg = when (rot) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
    val side = minOf(src.width, src.height)
    val mtx = android.graphics.Matrix().apply { val s = 400f / side; postScale(s, s); if (deg != 0f) postRotate(deg) }
    val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side, mtx, true)
    val out = java.io.ByteArrayOutputStream()
    sq.compress(Bitmap.CompressFormat.JPEG, 82, out)
    android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
} catch (_: Exception) { null }
