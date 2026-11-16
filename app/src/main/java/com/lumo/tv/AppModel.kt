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
    data object Sources : Screen
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

    /** Client réservé à la vidéo : aucune connexion gardée ouverte en réserve, pour que le fournisseur
     *  voie tout de suite que l'écran est libéré quand on arrête la lecture. */
    val mediaHttp: OkHttpClient = http.newBuilder()
        .dispatcher(okhttp3.Dispatcher())
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
        .build()

    /** Ferme net toutes les connexions vidéo encore ouvertes. */
    fun closeMediaConnections() {
        try { mediaHttp.dispatcher.cancelAll() } catch (_: Exception) { }
        try { mediaHttp.connectionPool.evictAll() } catch (_: Exception) { }
    }

    val store = Store(context)
    init { com.lumo.tv.data.TmdbArt.init(context, http); com.lumo.tv.data.TmdbArt.key = store.tmdbKey() }
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

    // Mini-lecteur (PiP) : boutons ⏮ ⏯ ⏭ dans la petite fenêtre
    var pipPlaying = true
    var pipCanStep = false
    var onPipStateChanged: (() -> Unit)? = null
    val pipEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    fun updatePip(playing: Boolean, canStep: Boolean) {
        if (playing == pipPlaying && canStep == pipCanStep) return
        pipPlaying = playing; pipCanStep = canStep
        onPipStateChanged?.invoke()
    }
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
            if (prefsSyncKeyMissing()) store.syncKey = acc.key()
            scope.launch(Dispatchers.IO) {
                try { val a = client?.auth(); withContext(Dispatchers.Main) { auth = a } } catch (_: Exception) { }
                syncNow()
                withContext(Dispatchers.Main) { ensureSourceRegistered(acc) }
            }
        }
        startSyncLoop()
        startCommandListener()
        sendPendingCrash()
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
                if (store.syncKey.isBlank() || store.account == null) {
                    if (prefsSyncKeyMissing()) store.syncKey = acc.key()
                }
                store.account = acc
                client = c
                auth = a
            }
            syncNow()
            withContext(Dispatchers.Main) { ensureSourceRegistered(acc) }
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

    private fun prefsSyncKeyMissing(): Boolean =
        context.getSharedPreferences("lumo", Context.MODE_PRIVATE).getString("syncKey", null).isNullOrBlank()

    // ---------- Sources (plusieurs abonnements, une à la fois) ----------

    val sources: List<Source> get() { store.version; return store.sources() }
    val activeSourceKey: String get() { store.version; return store.account?.key() ?: "" }

    /** La source en cours est ajoutée à la liste si elle n'y est pas encore. */
    fun ensureSourceRegistered(acc: Account) {
        if (store.sources().none { it.account.key() == acc.key() }) {
            val name = auth?.let { "Abonnement principal" } ?: "Abonnement"
            commit(store.saveSource(Source("s_" + acc.key().take(10), if (store.sources().isEmpty()) name else acc.server.substringAfter("//").substringBefore(":"), acc)))
        }
    }

    /** Ajoute une source après avoir vérifié qu'elle fonctionne. Renvoie un message d'erreur, ou null. */
    suspend fun addSource(name: String, acc: Account): String? = withContext(Dispatchers.IO) {
        try {
            val a = XtreamClient(acc, http, context.cacheDir).auth()
            if (!a.ok) return@withContext a.message
            withContext(Dispatchers.Main) {
                commit(store.saveSource(Source("s_" + acc.key().take(10), name.ifBlank { acc.server.substringAfter("//").substringBefore(":") }, acc)))
            }
            null
        } catch (e: Exception) { "Connexion impossible : ${e.message ?: "erreur réseau"}" }
    }

    fun removeSource(src: Source) {
        if (src.account.key() == activeSourceKey) { showToast("C'est la source en cours : passe d'abord sur une autre"); return }
        commit(store.removeSource(src.id))
    }

    /** Passe sur une autre source : le catalogue est rechargé, profils/favoris/synchro restent. */
    suspend fun switchSource(src: Source): String? = withContext(Dispatchers.IO) {
        try {
            val c = XtreamClient(src.account, http, context.cacheDir)
            val a = c.auth()
            if (!a.ok) return@withContext a.message
            withContext(Dispatchers.Main) {
                store.account = src.account
                client = c
                auth = a
                live = emptyList(); vods = emptyList(); series = emptyList()
                liveCats = emptyList(); vodCats = emptyList(); seriesCats = emptyList()
                nowPlaying.clear()
                tab = Tab.Home
                reset(Screen.Main)
                loadCatalog(true)
                showToast("Source : ${src.name}")
            }
            null
        } catch (e: Exception) { "Connexion impossible : ${e.message ?: "erreur réseau"}" }
    }

    // ---------- Export M3U (Fermata Auto, VLC…) : seulement les catégories gardées ----------

    /** Liste M3U de la source en cours, limitée aux catégories gardées (le filtre permanent s'applique déjà au catalogue). */
    fun buildM3u(includeVod: Boolean, onlyAuto: Boolean = false): String {
        val acc = store.account ?: return ""
        val c = client ?: return ""
        fun q(v: String) = v.replace("\"", "'").replace("\n", " ")
        val enc = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
        val epg = "${acc.server}/xmltv.php?username=${enc(acc.user)}&password=${enc(acc.pass)}"
        val sb = StringBuilder("#EXTM3U url-tvg=\"$epg\" x-tvg-url=\"$epg\"\n")
        val fmt = if (store.liveFormat == "m3u8") "m3u8" else "ts"
        fun liveLine(s: LiveStream, group: String) {
            sb.append("#EXTINF:-1 tvg-id=\"${q(s.epgId)}\" tvg-name=\"${q(s.name)}\" tvg-logo=\"${q(s.icon)}\" group-title=\"${q(group)}\"")
            if (s.archive) sb.append(" catchup=\"xc\" catchup-days=\"${s.archiveDays}\"")
            sb.append(",").append(s.name).append("\n").append(c.liveUrl(s.id, fmt)).append("\n")
        }
        if (onlyAuto) {
            val autoIds = store.favs(pid, "auto")
            sortLive(live.filter { it.id in autoIds }, "tnt").forEach { liveLine(it, "Voiture") }
            return sb.toString()
        }
        val liveCatIds = allowedCatIds("live")
        val favIds = store.favs(pid, "live")
        live.filter { it.id in favIds && it.catId in liveCatIds }.sortedBy { it.num }.forEach { liveLine(it, "★ Favoris") }
        val byCat = live.groupBy { it.catId }
        visibleCats("live").forEach { cat -> byCat[cat.id].orEmpty().sortedBy { it.num }.forEach { liveLine(it, cat.name) } }
        if (includeVod) {
            val vByCat = vods.groupBy { it.catId }
            visibleCats("vod").forEach { cat ->
                vByCat[cat.id].orEmpty().forEach { v ->
                    sb.append("#EXTINF:-1 tvg-name=\"${q(v.name)}\" tvg-logo=\"${q(v.icon)}\" group-title=\"Films · ${q(cat.name)}\",").append(v.name).append("\n")
                        .append(c.vodUrl(v.id, v.ext.ifBlank { "mp4" })).append("\n")
                }
            }
        }
        return sb.toString()
    }

    /** Enregistre la liste dans Téléchargements (Lumo.m3u). Renvoie un message pour l'utilisateur. */
    suspend fun saveM3u(includeVod: Boolean, onlyAuto: Boolean = false): String = withContext(Dispatchers.IO) {
        val text = buildM3u(includeVod, onlyAuto)
        if (text.isBlank()) return@withContext "Aucune source chargée"
        val name = if (onlyAuto) "Lumo-Voiture.m3u" else "Lumo.m3u"
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val r = context.contentResolver
                val col = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                // Remplace l'ancien fichier s'il existe
                r.delete(col, "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(name))
                val v = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "audio/x-mpegurl")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = r.insert(col, v) ?: return@withContext "Impossible d'enregistrer le fichier"
                r.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                "Enregistré dans Téléchargements/$name"
            } else {
                val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: return@withContext "Impossible d'enregistrer le fichier"
                java.io.File(dir, name).writeText(text)
                "Enregistré dans ${dir.absolutePath}/$name"
            }
        } catch (e: Exception) { "Erreur : ${e.message}" }
    }

    /** Envoie la liste à une autre appli (Fermata, VLC, fichiers…). */
    suspend fun shareM3u(includeVod: Boolean, onlyAuto: Boolean = false) {
        val text = buildM3u(includeVod, onlyAuto)
        if (text.isBlank()) { showToast("Aucune source chargée"); return }
        val f = withContext(Dispatchers.IO) {
            val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
            java.io.File(dir, if (onlyAuto) "Lumo-Voiture.m3u" else "Lumo.m3u").apply { writeText(text) }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.lumo.tv.files", f)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "audio/x-mpegurl"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(send, "Envoyer la liste M3U").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
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
                    withContext(Dispatchers.Main) { liveCats = sortCats(lc); vodCats = sortCats(vc); seriesCats = sortCats(sc) }
                    val v = c.vods(force)
                    withContext(Dispatchers.Main) { vods = v }
                    val s = c.series(force)
                    withContext(Dispatchers.Main) { series = s }
                    val l = c.liveStreams(force)
                    withContext(Dispatchers.Main) { live = l }
                }
                warmSearch()
            } catch (e: OutOfMemoryError) {
                loadError = "Catalogue trop lourd pour cet appareil. Supprime des catégories depuis le téléphone (Réglages › Gérer les catégories)."
            } catch (e: Exception) {
                loadError = "Chargement impossible : ${e.message ?: "erreur réseau"}"
            } finally {
                loading = false
            }
        }
    }

    // ---------- Ordre des catégories : français, puis arabe, puis le reste ----------

    private val frCat = Regex("(^|[^a-z])(fr|vf|vff|vfq|vostfr|french|france|fran[cç]ais)([^a-z]|$)", RegexOption.IGNORE_CASE)
    private val arCat = Regex("(^|[^a-z])(ar|arab|arabe|arabic|maghreb|algerie|algérie|maroc|morocco|tunisie|tunisia|egypt|egypte|liban|lebanon|syrie|irak|iraq|khaleej|gulf|saudi|ksa|qatar|osn|mbc|bein)([^a-z]|$)|[\u0600-\u06FF]", RegexOption.IGNORE_CASE)

    fun sortCats(list: List<Category>): List<Category> = list.sortedBy { c ->
        when {
            frCat.containsMatchIn(c.name) -> 0
            arCat.containsMatchIn(c.name) -> 1
            else -> 2
        }
    }

    // ---------- Ordre des chaînes ----------

    /** Numérotation TNT (depuis le 6 juin 2025), puis quelques chaînes Canal+ et payantes. */
    private val tntOrder: Map<String, Int> = mapOf(
        "tf1" to 1, "france2" to 2, "france3" to 3, "france4" to 4, "france5" to 5, "m6" to 6, "arte" to 7,
        "lcp" to 8, "publicsenat" to 8, "lcppublicsenat" to 8, "lachaineparlementaire" to 8, "w9" to 9, "tmc" to 10,
        "tfx" to 11, "gulli" to 12, "bfmtv" to 13, "bfm" to 13, "cnews" to 14, "lci" to 15, "franceinfo" to 16,
        "cstar" to 17, "t18" to 18, "novo19" to 19, "tf1seriesfilms" to 20, "tf1series" to 20, "tf1seriefilms" to 20,
        "lequipe" to 21, "lequipe21" to 21, "6ter" to 22, "rmcstory" to 23, "rmcdecouverte" to 24, "cherie25" to 25,
        "canal+" to 26, "canalplus" to 26, "canal+cinema" to 27, "canal+cinemas" to 27, "canal+sport" to 28, "canal+foot" to 29,
        "canal+series" to 30, "canal+docs" to 31, "canal+kids" to 32, "c8" to 33, "nrj12" to 34, "parispremiere" to 35
    )

    fun tntRank(s: LiveStream): Int {
        val n = client?.normName(s.name) ?: return 999
        tntOrder[n]?.let { return it }
        if (n.startsWith("france3")) return 3
        return 999
    }

    val liveSort: String get() { store.version; return store.liveSort() }
    fun setLiveSort(mode: String) { commit(store.setLiveSort(mode)) }
    val tmdbKey: String get() { store.version; return store.tmdbKey() }
    fun setTmdbKey(k: String) { commit(store.setTmdbKey(k)) }

    fun sortLive(list: List<LiveStream>, mode: String): List<LiveStream> = when (mode) {
        "az" -> list.sortedBy { norm(it.name.substringAfter('|').trim()) }
        "num" -> list.sortedBy { if (it.num > 0) it.num else Int.MAX_VALUE }
        "watched" -> {
            val w = store.watchCounts(pid)
            list.sortedWith(compareByDescending<LiveStream> { w[it.id] ?: 0 }.thenBy { tntRank(it) })
        }
        else -> list.sortedBy { tntRank(it) }
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
        val sk = store.syncKey.ifBlank { return }
        if (!sync.enabled) return
        scope.launch(Dispatchers.IO) {
            try { sync.apply(sk, c) } catch (_: Exception) { }
        }
    }

    fun isFav(kind: String, id: String): Boolean { store.version; return id in store.favs(pid, kind) }
    fun toggleFav(kind: String, id: String) { commit(store.toggleFav(pid, kind, id)) }

    // ---------- Liste « Voiture » (chaînes légères pour Fermata Auto / Android Auto) ----------

    private val qualityTag = Regex("""(?i)\b(4k|uhd|fhd|hd|sd|lq|hq|hevc|h\.?26[45]|raw|50fps|60fps|backup|vip)\b|[ᴬ-ᵡ]+|\(.*?\)|\[.*?]""")

    /** Nom de base d'une chaîne, sans préfixe pays ni qualité : « FR| TF1 HD RAW » → « tf1 ». */
    private fun baseName(n: String) = n.substringAfter("|", n).replace(qualityTag, " ")
        .lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")

    /** 0 = le plus léger (LQ/SD) … 5 = le plus lourd (4K/UHD, RAW). */
    private fun qualityRank(n: String): Int {
        val u = n.uppercase()
        var r = when {
            Regex("\\b(LQ|SD)\\b").containsMatchIn(u) -> 0
            Regex("\\b(4K|UHD)\\b").containsMatchIn(u) -> 4
            Regex("\\bFHD\\b").containsMatchIn(u) -> 3
            Regex("\\bHD\\b").containsMatchIn(u) -> 2
            else -> 1
        }
        if (u.contains("RAW") || n.contains("ᴿᴬᵂ")) r += 1
        return r
    }

    /** Ajoute à « Voiture » la version la plus légère de chaque chaîne favorite. Renvoie le nombre ajouté. */
    fun fillAutoFromFavs(): Int {
        val favIds = store.favs(pid, "live")
        if (favIds.isEmpty()) return 0
        val byBase = live.groupBy { baseName(it.name) }
        val picks = live.filter { it.id in favIds }.mapNotNull { f ->
            byBase[baseName(f.name)]?.minByOrNull { qualityRank(it.name) * 10 + (if (it.catId == f.catId) 0 else 1) }?.id
        }.toSet() - store.favs(pid, "auto")
        if (picks.isNotEmpty()) commit(store.addFavs(pid, "auto", picks))
        return picks.size
    }

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
    private fun normTitle(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]"), "")

    /** Même film présent deux fois chez le fournisseur (ids différents) : on prend la reprise la plus récente. */
    fun progressFor(key: String): Progress? {
        store.version
        val direct = store.progressFor(pid, key)
        if (!key.startsWith("vod_")) return direct
        val id = key.removePrefix("vod_")
        val title = direct?.title ?: vods.firstOrNull { it.id == id }?.name ?: return direct
        val t = normTitle(title)
        return store.progress(pid).firstOrNull { it.kind == "vod" && normTitle(it.title) == t } ?: direct
    }

    fun saveProgress(p: Progress) {
        commit(store.saveProgress(pid, p))
        if (p.kind == "vod") {
            val t = normTitle(p.title)
            store.progress(pid).filter { it.kind == "vod" && it.key != p.key && normTitle(it.title) == t }
                .forEach { commit(store.removeProgress(pid, it.key)) }
        }
    }
    fun removeProgress(key: String) { commit(store.removeProgress(pid, key)) }

    /** « Reprendre la lecture » : élément dont on demande la suppression (appui long). */
    var removeAsk by mutableStateOf<Progress?>(null)
    fun askRemoveProgress(p: Progress) { removeAsk = p }

    /** Retire un film (toutes ses versions) ou une série (épisodes en cours) de « Reprendre la lecture ». */
    fun removeFromContinue(p: Progress) {
        val t = normTitle(p.title)
        store.progress(pid).filter { o ->
            !o.finished && if (p.kind == "ep") o.kind == "ep" && o.parentId == p.parentId
            else o.kind == "vod" && normTitle(o.title) == t
        }.forEach { commit(store.removeProgress(pid, it.key)) }
        if (p.kind != "ep") commit(store.removeProgress(pid, p.key))
        showToast("Retiré de « Reprendre la lecture »")
    }

    suspend fun syncNow() {
        val sk = store.syncKey.ifBlank { return }
        if (!sync.enabled) return
        withContext(Dispatchers.IO) {
            try {
                val remote = sync.getObject("lumo/$sk") ?: JSONObject()
                val push = withContext(Dispatchers.Main) { store.merge(remote) }
                push.forEach { sync.apply(sk, it) }
                val devName = (if (isTv) "TV " else "") + android.os.Build.MODEL
                sync.put("lumo/$sk/devices/${store.deviceId}", JSONObject().put("name", devName).put("tv", isTv).put("ts", System.currentTimeMillis()))
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
            val got = try { sync.takePairing(code) } catch (_: Exception) { null }
            if (got != null) {
                val (acc, sk) = got
                if (sk.isNotBlank()) store.syncKey = sk
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
        try { sync.publishPairing(clean, acc, store.syncKey); null } catch (e: Exception) { "Envoi impossible : ${e.message}" }
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
    fun warmSearch() { if (!isTv) scope.launch { search("zz") } }

    /** Envoie le rapport du dernier plantage (aucune donnée perso : appareil, version, erreur). */
    private fun sendPendingCrash() {
        val txt = Crash.pending(context) ?: return
        if (!sync.enabled) return
        scope.launch(Dispatchers.IO) {
            try {
                sync.put("crash/${store.deviceId}-${System.currentTimeMillis()}", JSONObject().put("report", txt))
                Crash.clear(context)
            } catch (_: Exception) { }
        }
    }

    // ---------- Regarder sur la TV / télécommande ----------

    suspend fun activeTvs(): List<TvDevice> = withContext(Dispatchers.IO) {
        val acc = store.account ?: return@withContext emptyList()
        if (!sync.enabled) return@withContext emptyList()
        try {
            val o = sync.getObject("lumo/${store.syncKey}/devices") ?: return@withContext emptyList()
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
            sync.put("lumo/${store.syncKey}/cmd/$tvId", cmd.put("ts", System.currentTimeMillis()).put("profile", pid))
            true
        } catch (_: Exception) { false }
    }

    /** Arrête la lecture sur les autres écrans (TV/Fire restés allumés en arrière-plan). */
    suspend fun stopOtherScreens(): Int {
        val acc = store.account ?: return 0
        if (!sync.enabled) return 0
        val ids = withContext(Dispatchers.IO) {
            try {
                val o = sync.getObject("lumo/${store.syncKey}/devices") ?: return@withContext emptyList<String>()
                val now = System.currentTimeMillis()
                o.keys().asSequence().filter { id ->
                    val d = o.optJSONObject(id)
                    id != store.deviceId && d != null && now - d.optLong("ts") < 2 * 86_400_000L
                }.toList()
            } catch (_: Exception) { emptyList() }
        }
        var n = 0
        for (id in ids) if (sendCommand(id, JSONObject().put("type", "remote").put("action", "stop"))) n++
        return n
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
                val path = "lumo/${store.syncKey}/cmd/${store.deviceId}"
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
        val type = c.optString("type")
        // Le téléphone n'accepte que l'ordre « arrêter la lecture » (envoyé depuis la TV)
        if (!isTv && !(type == "remote" && c.optString("action") == "stop")) return
        if (type.startsWith("play_")) c.optString("profile").takeIf { it.isNotBlank() }?.let { p ->
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
