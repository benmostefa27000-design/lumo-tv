package com.lumo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumo.tv.AppModel
import com.lumo.tv.Screen
import com.lumo.tv.TvDevice
import kotlinx.coroutines.launch
import org.json.JSONObject

// ---------------- Bouton « Sur la TV » (téléphone) ----------------

@Composable
fun SendToTvButton(m: AppModel, label: String = "Sur la TV", cmd: () -> JSONObject) {
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf<List<TvDevice>?>(null) }

    fun send(tv: TvDevice) {
        scope.launch {
            val ok = m.sendCommand(tv.id, cmd())
            if (ok) { m.showToast("Envoyé sur ${tv.name}"); m.go(Screen.Remote(tv.id, tv.name)) }
            else m.showToast("Envoi impossible")
        }
    }

    LumoButton(label, primary = false) {
        scope.launch {
            val tvs = m.activeTvs()
            when {
                tvs.isEmpty() -> m.showToast("Aucune TV allumée avec Lumo ouvert")
                tvs.size == 1 -> send(tvs[0])
                else -> choices = tvs
            }
        }
    }

    choices?.let { list ->
        AlertDialog(
            onDismissRequest = { choices = null },
            containerColor = C.Card,
            title = { Text("Sur quelle TV ?", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { tv ->
                        LumoButton(tv.name, primary = false, modifier = Modifier.fillMaxWidth()) { choices = null; send(tv) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choices = null }) { Text("Annuler", color = C.Sub) } }
        )
    }
}

// ---------------- Télécommande (téléphone) ----------------

@Composable
fun RemoteScreen(m: AppModel, tvId: String, name: String) {
    val scope = rememberCoroutineScope()
    fun act(a: String) { scope.launch { m.sendCommand(tvId, JSONObject().put("type", "remote").put("action", a)) } }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("Télécommande", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(name, color = C.Sub)
        Spacer(Modifier.height(8.dp))
        RemoteKey("Lecture / Pause", big = true) { act("playpause") }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RemoteKey("−10 s") { act("rew") }
            RemoteKey("+30 s") { act("fwd") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RemoteKey("Précédent") { act("prev") }
            RemoteKey("Suivant") { act("next") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RemoteKey("Volume −") { act("voldown") }
            RemoteKey("Muet") { act("mute") }
            RemoteKey("Volume +") { act("volup") }
        }
        RemoteKey("Arrêter la vidéo") { act("stop") }
        Spacer(Modifier.weight(1f))
        LumoButton("Fermer", primary = false) { m.back() }
    }
}

@Composable
private fun RemoteKey(label: String, big: Boolean = false, onClick: () -> Unit) {
    FocusBox(shape = RoundedCornerShape(14.dp), scale = 1.04f, onClick = onClick) { _ ->
        Box(
            Modifier.background(if (big) Color.White else C.Card2).padding(horizontal = if (big) 48.dp else 20.dp, vertical = if (big) 22.dp else 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = if (big) Color.Black else Color.White, fontSize = if (big) 18.sp else 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }
}

// ---------------- Code parental ----------------

@Composable
fun PinCheckScreen(m: AppModel) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Text("Code parental", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Pour quitter le profil Enfants, entre le code.", color = C.Sub)
        OutlinedTextField(
            pin, { pin = it.filter { c -> c.isDigit() }.take(6); error = false },
            label = { Text("Code") }, singleLine = true, colors = lumoTextFieldColors(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.width(240.dp)
        )
        if (error) Text("Code incorrect", color = Color(0xFFFF6B75))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LumoButton("Valider") {
                if (pin == m.store.pin()) m.reset(Screen.Profiles) else { error = true; pin = "" }
            }
            LumoButton("Annuler", primary = false) { m.back() }
        }
    }
}
