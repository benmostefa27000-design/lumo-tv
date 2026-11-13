package com.lumo.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.lumo.tv.data.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.concurrent.TimeUnit

enum class Tab(val label: String) { Home("Accueil"), Live("TV en direct"), Movies("Films"), Series("Séries"), MyList("Ma liste"), Settings("Réglages") }

sealed interface Playable {
    data class Live(val list: List<LiveStream>, val index: Int) : Playable
    data class Movie(val vod: Vod, val fromStart: Boolean = false) : Playable
    data class Ep(val series: Series, val episodes: List<Episode>, val index: Int, val fromStart: Boolean = false) : Playable
    data class Replay(val stream: LiveStream, val entry: EpgEntry) : Playable
}

sealed interface Screen {
    data object Login : Screen
    data object Profiles : Screen
    data class ProfileEdit(val profile: Profile?) : Screen
    data object Main : Screen
    data class MovieDetail(val vod: Vod) : Screen
    data class SeriesDetail(val series: Series) : Screen
    data class ChannelGuide(val stream: LiveStream, val list: List<LiveStream>) : Screen
    data class Player(val item: Playable) : Screen
    data object Search : Screen
    data object Categories : Screen
    data object DeletedCategories : Screen
    data object PairPhone : Screen
    data object PinCheck : Screen
    data class Remote(val deviceId: String, val name: String) : Screen
}

data class TvDevice(val id: String, val name: String)

data class UpdateInfo(val version: Int, val url: String)

class AppModel(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    val store = Store(context)
    val sync = SyncClient(BuildConfig.SYNC_URL, http)

    val isTv: Boolean = run {
        val pm = context.packageManager
        val ui = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        ui == Configuration.UI_MODE_TYPE_TELEVISION ||
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature("amazon.hardware.fire_tv") ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    var client: XtreamClient? = null
        private set
    var auth by mutableStateOf<AuthInfo?>(null)

    // Catalogues
    var liveCats by mutableStateOf<List<Category>>(emptyList())
    var live by mutableStateOf<List<LiveStream>>(emptyList())
    var vodCats by mutableStateOf<List<Category>>(emptyList())
    var vods by mutableStateOf<List<Vod>>(emptyList())
    var seriesCats by mutableStateOf<List<Category>>(emptyList())
    var series by mutableStateOf<List<Series>>(emptyList())
    var loading by mutableStateOf(false)
    var loadError by mutableStateOf<String?>(null)

    // Navigation
    val stack = mutableStateListOf<Screen>()
    var tab by mutableStateOf(Tab.Home)
    val current: Screen get() = stack.lastOrNull() ?: Screen.Login

    var toast by mutableStateOf<String?>(null)
    var syncState by mutableStateOf(if (sync.enabled) "En attente" else "Désactivée")
    var update by mutableStateOf<UpdateInfo?>(null)

    /** Mini-lecteur (image dans l'image) et appli en arrière-plan. */
    var pip by mutableStateOf(false)
    var appStopped by mutableStateOf(false)

    /** Ordres de télécommande reçus du téléphone (lecture/pause, chaîne…). */
    val remoteEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)

    init {
        val acc = store.account
        if (acc == null) {
            stack.add(Screen.Login)
        } else {
            client = XtreamClient(acc, http, context.cacheDir)
            // Profil Enfants verrouillé : on reste dessus au redémarrage.
            val p = profile
            stack.add(if (p != null && p.kids && store.pin().isNotEmpty()) Screen.Main else Screen.Profiles)
            loadCatalog(false)
            scope.launch(Dispatchers.IO) {
                try { val a = client?.auth(); withContext(Dispatchers.Main) { auth = a } } catch (_: Exception) { }
            }
        }
        startSyncLoop()
        if (isTv) startCommandListener()
        checkUpdate(silent = true)
    }

    // ---------- Navigation ----------

    fun go(s: Screen) { stack.add(s) }
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
    fun reset(s: Screen) { stack.clear(); stack.add(s) }

    fun showToast(msg: String) {
        toast = msg
        scope.launch { delay(3500); if (toast == msg) toast = null }
    }

    // ---------- Compte ----------

    suspend fun login(acc: Account): String? = withContext(Dispatchers.IO) {
        try {
            val c = XtreamClient(acc, http, context.cacheDir)
            val a = c.auth()
            if (!a.ok) return@withContext a.message
            withContext(Dispatchers.Main) {
                store.account = acc
                client = c
                auth = a
            }
            syncNow()
            withContext(Dispatchers.Main) {
                if (store.profiles().isEmpty()) {
                    commit(store.saveProfile(Profile("p_${System.currentTimeMillis()}", "Profil 1", 0xFF2F6FD6, false, 0)))
                }
                reset(Screen.Profiles)
                loadCatalog(true)
            }
            null
        } catch (e: Exception) {
            "Connexion impossible : ${e.message ?: "erreur réseau"}"
        }
    }

    fun logout() {
        store.clearAll()
        client = null
        auth = null
        live = emptyList(); vods = emptyList(); series = emptyList()
        liveCats = emptyList(); vodCats = emptyList(); seriesCats = emptyList()
        reset(Screen.Login)
    }

    // ---------- Catalogue ----------

    fun loadCatalog(force: Boolean) {
        val c = client ?: return
        loading = true
        loadError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val lc = c.liveCategories(force)
                    val vc = c.vodCategories(force)
                    val sc = c.seriesCategories(force)
                    withContext(Dispatchers.Main) { liveCats = lc; vodCats = vc; seriesCats = sc }
                    val v = c.vods(force)
                    withContext(Dispatchers.Main) { vods = v }
                    val s = c.series(force)
                    withContext(Dispatchers.Main) { series = s }
                    val l = c.liveStreams(force)
                    withContext(Dispatchers.Main) { live = l }
                }
                warmSearch()
            } catch (e: Exception) {
                loadError = "Chargement impossible : ${e.message ?: "erreur réseau"}"
            } finally {
                loading = false
            }
        }
    }

    // ---------- Profil courant ----------

    val profile: Profile?
        get() {
            store.version
            val list = store.profiles()
            return list.firstOrNull { it.id == store.profileId } ?: list.firstOrNull()
        }

    val pid: String get() = profile?.id ?: "default"

    fun selectProfile(p: Profile) {
        store.profileId = p.id
        tab = Tab.Home
        reset(Screen.Main)
    }

    // ---------- Données synchronisées ----------

    fun commit(c: Change) {
        val acc = store.account ?: return
        if (!sync.enabled) return
        scope.launch(Dispatchers.IO) {
            try { sync.apply(acc.key(), c) } catch (_: Exception) { }
        }
    }

    fun isFav(kind: String, id: String): Boolean { store.version; return id in store.favs(pid, kind) }
    fun toggleFav(kind: String, id: String) { commit(store.toggleFav(pid, kind, id)) }

    fun deleted(kind: String): Set<String> { store.version; return store.deletedCats(kind) }
    fun setDeleted(kind: String, ids: Set<String>) { commit(store.setDeletedCats(kind, ids)) }

    val kidsMode: Boolean get() = profile?.kids == true

    private val kidsPattern = Regex("(kid|enfant|jeunesse|junior|animation|anime|dessin|cartoon|famille|family|disney|pixar|toon|nick|gulli|tiji|piwi|baby)", RegexOption.IGNORE_CASE)

    fun visibleCats(kind: String): List<Category> {
        val del = deleted(kind)
        val cats = when (kind) { "live" -> liveCats; "vod" -> vodCats; else -> seriesCats }
        return cats.filter { it.id !in del && (!kidsMode || kidsPattern.containsMatchIn(it.name)) }
    }

    fun allowedCatIds(kind: String): Set<String> = visibleCats(kind).map { it.id }.toSet()

    /** Changer de profil : en profil Enfants, il faut le code parental. */
    fun openProfiles() {
        if (kidsMode && store.pin().isNotEmpty()) go(Screen.PinCheck) else reset(Screen.Profiles)
    }

    fun progressList(): List<Progress> { store.version; return store.progress(pid) }
    fun progressFor(key: String): Progress? { store.version; return store.progressFor(pid, key) }
    fun saveProgress(p: Progress) { commit(store.saveProgress(pid, p)) }
    fun removeProgress(key: String) { commit(store.removeProgress(pid, key)) }

    suspend fun syncNow() {
        val acc = store.account ?: return
        if (!sync.enabled) return
        withContext(Dispatchers.IO) {
            try {
                val remote = sync.getObject("lumo/${acc.key()}") ?: JSONObject()
                val push = withContext(Dispatchers.Main) { store.merge(remote) }
                push.forEach { sync.apply(acc.key(), it) }
                val devName = (if (isTv) "TV " else "") + android.os.Build.MODEL
                sync.put("lumo/${acc.key()}/devices/${store.deviceId}", JSONObject().put("name", devName).put("tv", isTv).put("ts", System.currentTimeMillis()))
                store.lastSync = System.currentTimeMillis()
                withContext(Dispatchers.Main) { syncState = "OK" }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { syncState = "Hors ligne" }
            }
        }
    }

    private fun startSyncLoop() {
        if (!sync.enabled) return
        scope.launch {
            while (isActive) {
                syncNow()
                delay(20_000)
            }
        }
    }

    // ---------- Connexion TV par code ----------

    fun newPairCode(): String {
        val chars = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    suspend fun waitForPairing(code: String): Boolean = withContext(Dispatchers.IO) {
        if (!sync.enabled) return@withContext false
        val start = System.currentTimeMillis()
        while (isActive && System.currentTimeMillis() - start < 10 * 60 * 1000) {
            val acc = try { sync.takePairing(code) } catch (_: Exception) { null }
            if (acc != null) {
                val err = login(acc)
                return@withContext err == null
            }
            delay(3000)
        }
        false
    }

    suspend fun sendPairing(code: String): String? = withContext(Dispatchers.IO) {
        val acc = store.account ?: return@withContext "Pas de compte sur ce téléphone"
        if (!sync.enabled) return@withContext "Synchro désactivée"
        val clean = code.uppercase().filter { it.isLetterOrDigit() }
        if (clean.length != 6) return@withContext "Le code fait 6 caractères"
        try { sync.publishPairing(clean, acc); null } catch (e: Exception) { "Envoi impossible : ${e.message}" }
    }

    // ---------- Programme en cours (guide) ----------

    val nowPlaying = androidx.compose.runtime.mutableStateMapOf<String, String>()
    private val nowPending = HashSet<String>()

    fun loadNow(streamId: String) {
        val c = client ?: return
        if (nowPlaying.containsKey(streamId) || !nowPending.add(streamId)) return
        scope.launch(Dispatchers.IO) {
            val title = try {
                val now = System.currentTimeMillis()
                val st = live.firstOrNull { it.id == streamId }
                val list = if (st != null) c.epgFor(st, full = false) else c.shortEpg(streamId, 2)
                list.firstOrNull { it.start <= now && it.end > now }?.title ?: list.firstOrNull { it.end > now }?.title ?: ""
            } catch (_: Exception) { "" }
            withContext(Dispatchers.Main) { nowPlaying[streamId] = title; nowPending.remove(streamId) }
        }
    }

    // ---------- Recherche ----------

    private val accents = Regex("\\p{Mn}+")

    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(accents, "")

    data class SearchResults(val vods: List<Vod> = emptyList(), val series: List<Series> = emptyList(), val live: List<LiveStream> = emptyList())

    private class SearchIndex(val src: List<Any>, val vods: List<Pair<String, Vod>>, val series: List<Pair<String, Series>>, val live: List<Pair<String, LiveStream>>)
    @Volatile private var index: SearchIndex? = null

    /** Recherche hors du fil principal, sur des titres normalisés une seule fois. */
    suspend fun search(query: String): SearchResults {
        val v = vods; val s = series; val l = live
        val av = allowedCatIds("vod"); val aser = allowedCatIds("series"); val al = allowedCatIds("live")
        return withContext(Dispatchers.Default) {
            val q = norm(query.trim())
            if (q.length < 2) return@withContext SearchResults()
            var idx = index
            if (idx == null || idx.src[0] !== v || idx.src[1] !== s || idx.src[2] !== l) {
                idx = SearchIndex(listOf(v, s, l), v.map { norm(it.name) to it }, s.map { norm(it.name) to it }, l.map { norm(it.name) to it })
                index = idx
            }
            SearchResults(
                vods = idx.vods.asSequence().filter { it.second.catId in av && it.first.contains(q) }.map { it.second }.take(60).toList(),
                series = idx.series.asSequence().filter { it.second.catId in aser && it.first.contains(q) }.map { it.second }.take(60).toList(),
                live = idx.live.asSequence().filter { it.second.catId in al && it.first.contains(q) }.map { it.second }.take(30).toList()
            )
        }
    }

    /** Prépare l'index de recherche en avance, dès que le catalogue est chargé. */
    fun warmSearch() { scope.launch { search("zz") } }

    // ---------- Regarder sur la TV / télécommande ----------

    suspend fun activeTvs(): List<TvDevice> = withContext(Dispatchers.IO) {
        val acc = store.account ?: return@withContext emptyList()
        if (!sync.enabled) return@withContext emptyList()
        try {
            val o = sync.getObject("lumo/${acc.key()}/devices") ?: return@withContext emptyList()
            val now = System.currentTimeMillis()
            o.keys().asSequence().mapNotNull { id ->
                val d = o.optJSONObject(id) ?: return@mapNotNull null
                if (id == store.deviceId || !d.optBoolean("tv") || now - d.optLong("ts") > 3 * 60_000) null
                else TvDevice(id, d.optString("name").ifBlank { "TV" })
            }.toList()
        } catch (_: Exception) { emptyList() }
    }

    suspend fun sendCommand(tvId: String, cmd: JSONObject): Boolean = withContext(Dispatchers.IO) {
        val acc = store.account ?: return@withContext false
        try {
            sync.put("lumo/${acc.key()}/cmd/$tvId", cmd.put("ts", System.currentTimeMillis()).put("profile", pid))
            true
        } catch (_: Exception) { false }
    }

    /** Sur la TV : écoute en continu les ordres envoyés par le téléphone. */
    private fun startCommandListener() {
        if (!sync.enabled) return
        scope.launch(Dispatchers.IO) {
            val streamHttp = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
            var lastTs = System.currentTimeMillis()
            while (isActive) {
                val acc = store.account
                if (acc == null) { delay(5000); continue }
                val path = "lumo/${acc.key()}/cmd/${store.deviceId}"
                try {
                    val req = Request.Builder().url("${BuildConfig.SYNC_URL}/$path.json").header("Accept", "text/event-stream").build()
                    streamHttp.newCall(req).execute().use { r ->
                        val src = r.body?.source() ?: return@use
                        var event = ""
                        while (isActive && !src.exhausted()) {
                            val line = src.readUtf8Line() ?: break
                            if (line.startsWith("event:")) event = line.substringAfter(":").trim()
                            else if (line.startsWith("data:") && (event == "put" || event == "patch")) {
                                val data = line.substringAfter(":").trim()
                                val o = try { JSONObject(data) } catch (_: Exception) { null } ?: continue
                                val cmd = o.optJSONObject("data") ?: continue
                                val ts = cmd.optLong("ts")
                                if (ts <= lastTs - 60_000 || ts == lastTs) continue
                                lastTs = ts
                                try { sync.delete(path) } catch (_: Exception) { }
                                withContext(Dispatchers.Main) { handleCommand(cmd) }
                            }
                        }
                    }
                } catch (_: Exception) { }
                delay(3000)
            }
        }
    }

    private fun handleCommand(c: JSONObject) {
        c.optString("profile").takeIf { it.isNotBlank() }?.let { p ->
            if (store.profiles().any { it.id == p } && !(kidsMode && store.pin().isNotEmpty())) store.profileId = p
        }
        when (c.optString("type")) {
            "play_vod" -> {
                val v = vods.firstOrNull { it.id == c.optString("id") }
                    ?: Vod(c.optString("id"), c.optString("title"), c.optString("img"), "", "", c.optString("ext", "mp4"), 0)
                openPlayer(Playable.Movie(v))
            }
            "play_live" -> {
                val s = live.firstOrNull { it.id == c.optString("id") } ?: return
                val list = live.filter { it.catId == s.catId }
                openPlayer(Playable.Live(list, list.indexOf(s).coerceAtLeast(0)))
            }
            "play_ep" -> {
                val sid = c.optString("series"); val eid = c.optString("id")
                scope.launch {
                    val info = withContext(Dispatchers.IO) { try { client?.seriesInfo(sid) } catch (_: Exception) { null } } ?: return@launch
                    val ser = series.firstOrNull { it.id == sid } ?: Series(sid, c.optString("title"), c.optString("img"), "", "", "", "", 0)
                    for ((_, eps) in info.episodes) {
                        val idx = eps.indexOfFirst { it.id == eid }
                        if (idx >= 0) { openPlayer(Playable.Ep(ser, eps, idx)); break }
                    }
                }
            }
            "remote" -> {
                when (val a = c.optString("action")) {
                    "volup" -> volume(android.media.AudioManager.ADJUST_RAISE)
                    "voldown" -> volume(android.media.AudioManager.ADJUST_LOWER)
                    "mute" -> volume(android.media.AudioManager.ADJUST_TOGGLE_MUTE)
                    "stop" -> if (current is Screen.Player) back()
                    else -> remoteEvents.tryEmit(a)
                }
            }
        }
    }

    private fun openPlayer(p: Playable) {
        if (current is Screen.Player) stack.removeAt(stack.lastIndex)
        if (stack.isEmpty() || current == Screen.Login || current == Screen.Profiles) { tab = Tab.Home; reset(Screen.Main) }
        go(Screen.Player(p))
        showToast("Lancé depuis le téléphone")
    }

    private fun volume(dir: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, android.media.AudioManager.FLAG_SHOW_UI)
    }

    fun playLiveById(id: String) {
        val s = live.firstOrNull { it.id == id } ?: return
        val list = live.filter { it.catId == s.catId }
        openPlayer(Playable.Live(list, list.indexOf(s).coerceAtLeast(0)))
    }

    // ---------- Rappels ----------

    fun hasReminder(streamId: String, start: Long): Boolean { store.version; return store.reminders().any { it.optString("stream") == streamId && it.optLong("start") == start } }

    fun toggleReminder(s: LiveStream, e: EpgEntry) {
        val list = store.reminders().toMutableList()
        val existing = list.firstOrNull { it.optString("stream") == s.id && it.optLong("start") == e.start }
        val id = (s.id + e.start).hashCode()
        if (existing != null) {
            list.remove(existing)
            Reminders.cancel(context, id)
            showToast("Rappel retiré")
        } else {
            list.add(JSONObject().put("stream", s.id).put("start", e.start).put("title", e.title).put("channel", s.name))
            Reminders.schedule(context, id, s.id, s.name, e.title, e.start)
            showToast("Rappel 5 min avant le début")
        }
        store.saveReminders(list)
    }

    // ---------- Mises à jour ----------

    fun checkUpdate(silent: Boolean) {
        val repo = BuildConfig.GITHUB_REPO
        if (repo.isBlank()) { if (!silent) showToast("Mises à jour non configurées"); return }
        scope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url("https://api.github.com/repos/$repo/releases/latest")
                    .header("Accept", "application/vnd.github+json").build()
                val body = http.newCall(req).execute().use { it.body?.string() ?: "" }
                val o = JSONObject(body)
                val tag = o.optString("tag_name").removePrefix("v")
                val v = tag.toIntOrNull() ?: 0
                val assets = o.optJSONArray("assets")
                var url = ""
                if (assets != null) for (n in 0 until assets.length()) {
                    val a = assets.optJSONObject(n) ?: continue
                    if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
                }
                withContext(Dispatchers.Main) {
                    if (v > BuildConfig.VERSION_CODE && url.isNotBlank()) update = UpdateInfo(v, url)
                    else if (!silent) showToast("Tu as la dernière version")
                }
            } catch (e: Exception) {
                if (!silent) withContext(Dispatchers.Main) { showToast("Vérification impossible") }
            }
        }
    }

    var updateProgress by mutableStateOf<String?>(null)

    fun installUpdate(u: UpdateInfo) {
        updateProgress = "Téléchargement…"
        scope.launch(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val f = File(dir, "lumo.apk")
                http.newCall(Request.Builder().url(u.url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
                    r.body!!.byteStream().use { input -> f.outputStream().use { input.copyTo(it) } }
                }
                val uri = FileProvider.getUriForFile(context, "com.lumo.tv.files", f)
                val i = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                withContext(Dispatchers.Main) { updateProgress = null; context.startActivity(i) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { updateProgress = null; showToast("Mise à jour impossible : ${e.message}") }
            }
        }
    }
}
