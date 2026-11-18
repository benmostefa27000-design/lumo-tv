package com.lumo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumo.tv.AppModel
import com.lumo.tv.BuildConfig
import com.lumo.tv.Playable
import com.lumo.tv.Screen
import com.lumo.tv.data.Category
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ---------------- Recherche ----------------

@Composable
fun SearchScreen(m: AppModel) {
    var q by remember { mutableStateOf(m.searchPrefill ?: "") }
    val fr = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // Sur TV, pas d'ouverture automatique du clavier (il restait bloqué sur Fire TV)
    LaunchedEffect(Unit) { if (!m.isTv) try { fr.requestFocus() } catch (_: Exception) { } }
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
    val closeKeyboard = {
        keyboard?.hide()
        focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
        Unit
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val speechIntent = remember {
        android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Dis le nom d'un film, d'une série ou d'une chaîne")
    }
    val canSpeak = remember { ctx.packageManager.queryIntentActivities(speechIntent, 0).isNotEmpty() }
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { q = it }
    }
    // Micro lancé tout de suite (touche Recherche/micro), ou texte reçu du téléphone / de l'Assistant
    LaunchedEffect(m.searchVoice, m.searchPrefill) {
        m.searchPrefill?.let { q = it; m.searchPrefill = null }
        if (m.searchVoice) {
            m.searchVoice = false
            if (canSpeak) { kotlinx.coroutines.delay(250); try { voice.launch(speechIntent) } catch (_: Exception) { } }
            else m.showToast("Micro indisponible sur cet appareil : dicte depuis la télécommande Lumo de ton téléphone")
        }
    }
    var res by remember { mutableStateOf(AppModel.SearchResults()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(q) {
        if (q.trim().length < 2) { res = AppModel.SearchResults(); searching = false; return@LaunchedEffect }
        searching = true
        kotlinx.coroutines.delay(300)
        res = m.search(q)
        searching = false
    }
    val vods = res.vods
    val series = res.series
    val live = res.live
    val cardW = if (m.isTv) 140.dp else 108.dp
    val pad = if (m.isTv) 48.dp else 16.dp

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp)) {
        item {
            OutlinedTextField(
                q, { q = it },
                label = { Text("Film, série ou chaîne") },
                singleLine = true,
                colors = lumoTextFieldColors(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { closeKeyboard() }, onDone = { closeKeyboard() }, onNext = { closeKeyboard() }, onGo = { closeKeyboard() }
                ),
                modifier = Modifier.padding(horizontal = pad).fillMaxWidth().focusRequester(fr)
            )
            Row(Modifier.padding(horizontal = pad, vertical = 8.dp)) {
                val micFr = rememberAutoFocus(m.isTv && canSpeak)
                LumoButton("🎤  Recherche vocale", primary = m.isTv, requester = micFr) {
                    if (canSpeak) { try { voice.launch(speechIntent) } catch (_: Exception) { m.showToast("Recherche vocale indisponible") } }
                    else m.showToast("Micro indisponible sur cette TV : ouvre la télécommande Lumo sur ton téléphone et touche « Dicter une recherche »")
                }
            }
        }
        if (searching && vods.isEmpty() && series.isEmpty() && live.isEmpty()) item { Empty("Recherche…") }
        else if (q.trim().length >= 2 && vods.isEmpty() && series.isEmpty() && live.isEmpty()) item { Empty("Aucun résultat pour « $q »") }
        if (vods.isNotEmpty()) item {
            CompositionLocalProvider(LocalPad provides pad) {
                RowSection("Films") { items(vods, key = { it.id }) { v -> PosterCard(v.name, v.icon, cardW, kind = "movie") { m.go(Screen.MovieDetail(v)) } } }
            }
        }
        if (series.isNotEmpty()) item {
            CompositionLocalProvider(LocalPad provides pad) {
                RowSection("Séries") { items(series, key = { it.id }) { s -> PosterCard(s.name, s.cover, cardW, kind = "tv") { m.go(Screen.SeriesDetail(s)) } } }
            }
        }
        if (live.isNotEmpty()) {
            item { SectionTitle("Chaînes", Modifier.padding(start = pad, top = 18.dp, bottom = 8.dp)) }
            items(live, key = { "l" + it.id }) { s ->
                FocusBox(modifier = Modifier.padding(horizontal = pad, vertical = 3.dp).fillMaxWidth(), scale = 1.02f, onClick = {
                    m.go(Screen.Player(Playable.Live(live, live.indexOf(s))))
                }) { f ->
                    Text(s.name, color = Color.White, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().background(if (f) Color(0xFF2A2A2A) else C.Card).padding(14.dp))
                }
            }
        }
    }
}

// ---------------- Réglages ----------------

@Composable
fun SettingsScreen(m: AppModel) {
    val scope = rememberCoroutineScope()
    val pad = if (m.isTv) 48.dp else 16.dp
    val dateFmt = remember { SimpleDateFormat("d MMMM yyyy", Locale.FRANCE) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = pad, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Réglages", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp)) }
        item { SettingRow("Changer de profil", m.profile?.name + if (m.kidsMode) " · profil Enfants" else "") { m.openProfiles() } }
        if (m.kidsMode) return@LazyColumn
        item {
            val n = m.sources.size
            val cur = m.sources.firstOrNull { it.account.key() == m.activeSourceKey }?.name
            SettingRow("Sources", (if (cur != null) "En cours : $cur" else "Ton abonnement") + if (n > 1) " · $n sources" else " · ajouter un autre abonnement") { m.go(Screen.Sources) }
        }
        item { SettingRow("Gérer les catégories", "Tout sélectionner, supprimer, restaurer") { m.go(Screen.Categories) } }
        if (m.isTv) item {
            SettingRow("Fréquence d'image adaptée", if (m.store.matchFrameRate) "Activée · 50 Hz pour la TV, 24 Hz pour les films (image plus fluide)" else "Désactivée") {
                m.store.matchFrameRate = !m.store.matchFrameRate
            }
        }
        if (!m.isTv) item {
            var open by remember { mutableStateOf(false) }
            var withVod by remember { mutableStateOf(false) }
            val autoCount = run { m.store.version; m.store.favs(m.pid, "auto").size }
            var onlyAuto by remember(autoCount) { mutableStateOf(autoCount > 0) }
            var busy by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            SettingRow("Exporter en liste M3U", "Pour Fermata Auto, VLC… avec seulement tes catégories") { open = true }
            if (open) androidx.compose.material3.AlertDialog(
                onDismissRequest = { if (!busy) open = false },
                containerColor = C.Card,
                title = { Text("Exporter en M3U", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Crée un fichier Lumo.m3u avec les chaînes des catégories que tu as gardées (et tes favoris en premier). Le fichier contient tes identifiants : ne le partage pas.", color = C.Sub, fontSize = 14.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(onlyAuto, { onlyAuto = true }, enabled = autoCount > 0)
                            Text("🚗 Liste Voiture ($autoCount chaînes)" + if (autoCount == 0) " — vide" else "", color = if (autoCount > 0) Color.White else C.Sub, fontSize = 15.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(!onlyAuto, { onlyAuto = false })
                            Text("Mes catégories gardées", color = Color.White, fontSize = 15.sp)
                        }
                        if (!onlyAuto) Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(withVod, { withVod = it })
                            Text("Ajouter aussi les films", color = Color.White, fontSize = 15.sp)
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        busy = true
                        scope.launch { m.showToast(m.saveM3u(withVod && !onlyAuto, onlyAuto)); busy = false; open = false }
                    }) { Text(if (busy) "…" else "Enregistrer", color = C.Red) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { scope.launch { m.shareM3u(withVod && !onlyAuto, onlyAuto); open = false } }) { Text("Envoyer à…", color = C.Sub) }
                }
            )
        }
        if (!m.isTv) item { SettingRow("Connecter une TV", "Avec le code ou le QR code affiché sur la TV") { m.go(Screen.PairPhone) } }
        if (!m.isTv) item {
            SettingRow("Télécommande", "Piloter une TV où Lumo est ouvert") {
                scope.launch {
                    val tvs = m.activeTvs()
                    if (tvs.isEmpty()) m.showToast("Aucune TV allumée avec Lumo ouvert") else m.go(Screen.Remote(tvs[0].id, tvs[0].name))
                }
            }
        }
        item {
            var edit by remember { mutableStateOf(false) }
            var txt by remember { mutableStateOf(m.tmdbKey) }
            val hasKey = m.tmdbKey.isNotBlank()
            SettingRow("Images et infos TMDB", if (hasKey) "Activé · images d'épisodes et infos en français" else if (m.isTv) "À régler depuis le téléphone" else "Coller ta clé TMDB pour les images d'épisodes") {
                if (!m.isTv) edit = !edit
            }
            if (edit && !m.isTv) Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = txt, onValueChange = { txt = it.trim() }, singleLine = true,
                    label = { Text("Clé d'API (32 caractères)") }, colors = lumoTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LumoButton("Enregistrer") {
                        if (txt.isNotBlank() && !Regex("^[0-9a-fA-F]{32}$").matches(txt)) m.showToast("La clé doit faire 32 lettres et chiffres (la « Clé d'API », pas le long jeton)")
                        else { m.setTmdbKey(txt); m.showToast(if (txt.isBlank()) "TMDB désactivé" else "Clé TMDB enregistrée"); edit = false }
                    }
                    LumoButton("Annuler", primary = false) { txt = m.tmdbKey; edit = false }
                }
            }
        }
        item {
            SettingRow("Animations 3D", if (m.store.anim3d) "Activées (défilement des affiches, reflets)" else "Désactivées : plus léger pour un vieux Fire Stick") {
                m.store.anim3d = !m.store.anim3d
            }
        }
        item {
            SettingRow("Format du direct", if (m.store.liveFormat == "ts") "TS (par défaut)" else "HLS (m3u8)") {
                m.store.liveFormat = if (m.store.liveFormat == "ts") "m3u8" else "ts"
            }
        }
        item {
            val last = m.store.lastSync
            val sub = if (!m.sync.enabled) "Désactivée" else "${m.syncState}${if (last > 0) " · " + SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(last)) else ""}"
            SettingRow("Synchroniser maintenant", sub) { scope.launch { m.syncNow(); m.showToast("Synchro : ${m.syncState}") } }
        }
        item { SettingRow("Recharger le catalogue", "Récupère les nouveaux films, séries et chaînes") { m.loadCatalog(true); m.showToast("Rechargement…") } }
        item { SettingRow("Vérifier les mises à jour", "Version ${BuildConfig.VERSION_NAME}") { m.checkUpdate(silent = false) } }
        m.auth?.let { a ->
            item {
                val exp = if (a.expDate > 0) "expire le ${dateFmt.format(Date(a.expDate))}" else "sans date de fin"
                SettingRow("Abonnement", "${a.status} · $exp · ${a.maxConnections} écran${if (a.maxConnections > 1) "s" else ""}") { }
            }
        }
        item { SettingRow("Se déconnecter", "Retire le compte de cet appareil") { m.logout() } }
    }
}

@Composable
private fun SettingRow(title: String, sub: String?, onClick: () -> Unit) {
    FocusBox(modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp), scale = 1.02f, onClick = onClick) { f ->
        Row(Modifier.fillMaxWidth().background(if (f) Color(0xFF2A2A2A) else C.Card).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (!sub.isNullOrBlank()) Text(sub, color = C.Dim, fontSize = 13.sp)
            }
            Icon(Icons.Default.KeyboardArrowRight, null, tint = C.Dim)
        }
    }
}

// ---------------- Gérer les catégories ----------------

private val kinds = listOf("live" to "Direct", "vod" to "Films", "series" to "Séries")

private fun catsOf(m: AppModel, kind: String): List<Category> = when (kind) { "live" -> m.liveCats; "vod" -> m.vodCats; else -> m.seriesCats }

@Composable
fun CategoriesScreen(m: AppModel) {
    var kind by remember { mutableStateOf("live") }
    var q by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf(setOf<String>()) }
    var undo by remember { mutableStateOf<Pair<String, Set<String>>?>(null) }
    val deleted = m.deleted(kind)
    val nq = m.norm(q.trim())
    val vis = catsOf(m, kind).filter { it.id !in deleted && (nq.isEmpty() || m.norm(it.name).contains(nq)) }
    val selVis = vis.count { it.id in sel }
    val allOn = vis.isNotEmpty() && selVis == vis.size
    val pad = if (m.isTv) 48.dp else 16.dp

    Column(Modifier.fillMaxSize().padding(horizontal = pad, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Gérer les catégories", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            kinds.forEach { (k, label) -> Chip(label, kind == k) { kind = k; sel = emptySet(); q = "" } }
        }
        OutlinedTextField(q, { q = it }, label = { Text("Filtrer (ex : DE, UK, 4K…)") }, singleLine = true, colors = lumoTextFieldColors(), modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FocusBox(scale = 1.03f, onClick = { sel = if (allOn) sel - vis.map { it.id }.toSet() else sel + vis.map { it.id } }) { _ ->
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CheckBoxBox(allOn, partial = selVis > 0 && !allOn)
                    Text(if (allOn) "Tout désélectionner" else if (nq.isNotEmpty()) "Sélectionner les ${vis.size} résultats" else "Tout sélectionner (${vis.size})", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
            FocusBox(scale = 1.03f, onClick = { val ids = vis.map { it.id }.toSet(); sel = (sel - ids) + ids.filter { it !in sel } }) { _ ->
                Text("Inverser", color = C.Sub, modifier = Modifier.padding(10.dp))
            }
            Spacer(Modifier.weight(1f))
            FocusBox(scale = 1.03f, onClick = { m.go(Screen.DeletedCategories) }) { _ ->
                Text("Supprimées (${deleted.size}) ›", color = C.Sub, modifier = Modifier.padding(10.dp))
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(vis, key = { it.id }) { c ->
                val on = c.id in sel
                FocusBox(modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp), scale = 1.01f, onClick = { sel = if (on) sel - c.id else sel + c.id }) { f ->
                    Row(Modifier.fillMaxWidth().background(if (f) Color(0xFF2A2A2A) else Color.Transparent).padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CheckBoxBox(on)
                        Text(c.name, color = if (on) Color.White else Color(0xFFD6D6D6), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        val u = undo
        if (sel.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LumoButton("Annuler", primary = false) { sel = emptySet() }
                FocusBox(modifier = Modifier.height(46.dp), scale = 1.04f, onClick = {
                    val ids = sel
                    m.setDeleted(kind, deleted + ids)
                    undo = kind to ids
                    sel = emptySet()
                    m.showToast("${ids.size} supprimée${if (ids.size > 1) "s" else ""}")
                }) { _ ->
                    Row(Modifier.fillMaxHeight().background(C.Red).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Delete, null, tint = Color.White)
                        Text("Supprimer ${sel.size} catégorie${if (sel.size > 1) "s" else ""}", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else if (u != null) {
            LumoButton("Annuler la suppression (${u.second.size})", primary = false) {
                m.setDeleted(u.first, m.deleted(u.first) - u.second)
                undo = null
            }
        }
    }
}

@Composable
private fun CheckBoxBox(on: Boolean, partial: Boolean = false) {
    Box(
        Modifier.size(22.dp)
            .background(if (on || partial) C.Red else Color.Transparent, RoundedCornerShape(5.dp))
            .then(if (on || partial) Modifier else Modifier.border(2.dp, Color(0xFF5A5A5A), RoundedCornerShape(5.dp))),
        contentAlignment = Alignment.Center
    ) {
        if (on) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
        else if (partial) Box(Modifier.width(10.dp).height(3.dp).background(Color.White))
    }
}

@Composable
fun DeletedCategoriesScreen(m: AppModel) {
    var kind by remember { mutableStateOf("live") }
    var sel by remember { mutableStateOf(setOf<String>()) }
    val deleted = m.deleted(kind)
    val list = catsOf(m, kind).filter { it.id in deleted }
    val pad = if (m.isTv) 48.dp else 16.dp
    Column(Modifier.fillMaxSize().padding(horizontal = pad, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Catégories supprimées", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { kinds.forEach { (k, label) -> Chip(label, kind == k) { kind = k; sel = emptySet() } } }
        if (list.isEmpty()) Empty("Aucune catégorie supprimée ici")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(list, key = { it.id }) { c ->
                val on = c.id in sel
                FocusBox(modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp), scale = 1.01f, onClick = { sel = if (on) sel - c.id else sel + c.id }) { f ->
                    Row(Modifier.fillMaxWidth().background(if (f) Color(0xFF2A2A2A) else Color.Transparent).padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CheckBoxBox(on)
                        Text(c.name, color = C.Sub, fontSize = 15.sp)
                    }
                }
            }
        }
        if (sel.isNotEmpty()) LumoButton("Restaurer ${sel.size}") { m.setDeleted(kind, deleted - sel); sel = emptySet(); m.showToast("Restauré") }
        LumoButton("Retour", primary = false) { m.back() }
    }
}
