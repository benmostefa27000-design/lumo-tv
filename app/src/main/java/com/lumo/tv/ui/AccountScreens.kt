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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth()) { LumoLogo(36) }
        Spacer(Modifier.height(if (m.isTv) 60.dp else 40.dp))
        Text(if (manage) "Gérer les profils" else "Qui regarde ?", color = Color.White, fontSize = if (m.isTv) 44.sp else 30.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(36.dp))
        val tile = if (m.isTv) 150.dp else 110.dp
        FlowRowCompat(spacing = 24.dp) {
            profiles.forEach { p ->
                ProfileTile(p.name, Color(p.color), tile, edit = manage) {
                    if (manage) m.go(Screen.ProfileEdit(p)) else m.selectProfile(p)
                }
            }
            if (profiles.size < 6) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FocusBox(modifier = Modifier.size(tile), onClick = { m.go(Screen.ProfileEdit(null)) }) { _ ->
                        Box(Modifier.fillMaxSize().border(2.dp, Color(0xFF3A3A3A), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, "Ajouter un profil", tint = C.Dim, modifier = Modifier.size(56.dp))
                        }
                    }
                    Text("Ajouter", color = C.Dim, fontSize = 16.sp)
                }
            }
        }
        Spacer(Modifier.height(40.dp))
        LumoButton(if (manage) "Terminé" else "Gérer les profils", primary = false) { manage = !manage }
    }
}

@Composable
private fun ProfileTile(name: String, color: Color, size: androidx.compose.ui.unit.Dp, edit: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FocusBox(modifier = Modifier.size(size), scale = 1.08f, onClick = onClick) { _ ->
            Box(Modifier.fillMaxSize().background(color), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Black)
                if (edit) {
                    Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Edit, "Modifier", tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                }
            }
        }
        Text(name, color = C.Sub, fontSize = 16.sp)
    }
}

/** Petite mise en ligne qui passe à la ligne (évite les API expérimentales). */
@Composable
fun FlowRowCompat(spacing: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
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
                var x = (constraints.maxWidth - rw) / 2
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(if (existing == null) "Nouveau profil" else "Modifier le profil", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(name, { name = it.take(20) }, label = { Text("Prénom") }, singleLine = true, colors = lumoTextFieldColors(), modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth())
        Text("Couleur", color = C.Sub)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            profileColors.forEach { c ->
                FocusBox(modifier = Modifier.size(48.dp), shape = RoundedCornerShape(50), scale = 1.12f, onClick = { color = c }) { _ ->
                    Box(Modifier.fillMaxSize().background(Color(c)).then(if (color == c) Modifier.border(3.dp, Color.White, RoundedCornerShape(50)) else Modifier))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LumoButton("Enregistrer") {
                val n = name.trim().ifBlank { "Profil" }
                val order = existing?.order ?: (m.store.profiles().maxOfOrNull { it.order } ?: 0) + 1
                m.commit(m.store.saveProfile(Profile(existing?.id ?: "p_${System.currentTimeMillis()}", n, color, existing?.kids ?: false, order)))
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
