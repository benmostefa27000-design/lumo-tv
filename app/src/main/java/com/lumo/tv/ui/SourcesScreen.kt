package com.lumo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumo.tv.AppModel
import com.lumo.tv.data.Account
import com.lumo.tv.data.Source
import kotlinx.coroutines.launch

/** Plusieurs abonnements IPTV : on en utilise un à la fois, on passe de l'un à l'autre ici. */
@Composable
fun SourcesScreen(m: AppModel) {
    val scope = rememberCoroutineScope()
    val sources = m.sources
    val active = m.activeSourceKey
    var busyId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf<Source?>(null) }
    val pad = if (m.isTv) 48.dp else 16.dp

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = pad, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Sources", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                "Tes abonnements IPTV. Un seul est utilisé à la fois : touche-en un pour passer dessus. Tes profils, favoris et réglages restent les mêmes.",
                color = C.Sub, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
            )
        }
        items(sources, key = { it.id }) { src ->
            val isActive = src.account.key() == active
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FocusBox(modifier = Modifier.weight(1f), scale = 1.02f, onClick = {
                    if (isActive || busyId != null) return@FocusBox
                    busyId = src.id
                    scope.launch {
                        val err = m.switchSource(src)
                        busyId = null
                        if (err != null) m.showToast(err)
                    }
                }) { f ->
                    Row(
                        Modifier.fillMaxWidth().background(if (f) Color(0xFF2A2A2A) else C.Card).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            Modifier.size(26.dp).background(if (isActive) C.Red else Color(0xFF333333), RoundedCornerShape(50)),
                            contentAlignment = Alignment.Center
                        ) { if (isActive) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                        Column(Modifier.weight(1f)) {
                            Text(src.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    busyId == src.id -> "Connexion…"
                                    isActive -> "En cours d'utilisation · ${src.account.server.substringAfter("//")}"
                                    else -> src.account.server.substringAfter("//")
                                },
                                color = C.Sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (!isActive) FocusBox(shape = RoundedCornerShape(50), scale = 1.1f, onClick = { askDelete = src }) { f ->
                    Box(Modifier.size(44.dp).background(if (f) Color(0xFF3A3A3A) else Color.Transparent, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Delete, "Supprimer", tint = C.Sub)
                    }
                }
            }
        }
        item {
            if (!adding) LumoButton("+ Ajouter une source", primary = false, modifier = Modifier.padding(top = 8.dp)) { adding = true }
            else AddSourceForm(m, onDone = { adding = false })
        }
    }

    askDelete?.let { src ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askDelete = null },
            containerColor = C.Card,
            title = { Text("Supprimer la source", color = Color.White) },
            text = { Text("Retirer « ${src.name} » de tes sources ?", color = C.Sub) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { m.removeSource(src); askDelete = null }) { Text("Supprimer", color = C.Red) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { askDelete = null }) { Text("Annuler", color = C.Sub) } }
        )
    }
}

@Composable
private fun AddSourceForm(m: AppModel, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxWidth().widthIn(max = 640.dp).background(C.Card, RoundedCornerShape(8.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Nouvelle source", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Les infos de l'abonnement (comme dans IPTV Smarters), ou ton lien M3U complet dans le champ « Serveur ».", color = C.Sub, fontSize = 13.sp)
        OutlinedTextField(name, { name = it.take(30) }, label = { Text("Nom (ex. Abonnement arabe)") }, singleLine = true, colors = lumoTextFieldColors(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(server, { server = it }, label = { Text("Serveur ou lien M3U") }, singleLine = true, colors = lumoTextFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it }, label = { Text("Nom d'utilisateur") }, singleLine = true, colors = lumoTextFieldColors(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("Mot de passe") }, singleLine = true, colors = lumoTextFieldColors(),
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = Color(0xFFFF6B75), fontSize = 14.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LumoButton(if (busy) "Vérification…" else "Ajouter") {
                if (busy) return@LumoButton
                val acc = Account.parse(server, user, pass)
                if (acc == null) { error = "Vérifie le serveur et le nom d'utilisateur"; return@LumoButton }
                if (m.sources.any { it.account.key() == acc.key() }) { error = "Cette source est déjà dans la liste"; return@LumoButton }
                busy = true; error = null
                scope.launch {
                    val err = m.addSource(name.trim(), acc)
                    busy = false
                    if (err != null) error = err else { m.showToast("Source ajoutée"); onDone() }
                }
            }
            LumoButton("Annuler", primary = false) { onDone() }
        }
    }
}
