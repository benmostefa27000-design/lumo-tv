package com.lumo.tv.data

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

const val USER_AGENT = "Lumo/1.0 (Linux; Android) ExoPlayerLib/2.19.1"

class XtreamClient(val acc: Account, private val http: OkHttpClient, private val cacheDir: File) {

    var serverTimezone: String = "Europe/Paris"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun api(action: String?, extra: String = ""): String {
        val sb = StringBuilder("${acc.server}/player_api.php?username=${enc(acc.user)}&password=${enc(acc.pass)}")
        if (action != null) sb.append("&action=").append(action)
        sb.append(extra)
        return sb.toString()
    }

    fun fetch(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.string() ?: ""
        }
    }

    private fun cached(name: String, url: String, maxAgeMs: Long, force: Boolean): String {
        val dir = File(cacheDir, "xtream").apply { mkdirs() }
        val f = File(dir, "${acc.key()}_$name.json")
        if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMs) {
            return f.readText()
        }
        return try {
            val s = fetch(url)
            if (s.isNotBlank()) f.writeText(s)
            s
        } catch (e: Exception) {
            if (f.exists()) f.readText() else throw e
        }
    }

    private fun array(s: String): List<JSONObject> {
        val t = s.trim()
        if (!t.startsWith("[")) return emptyList()
        return JSONArray(t).objects()
    }

    fun auth(): AuthInfo {
        val o = JSONObject(fetch(api(null)))
        val u = o.optJSONObject("user_info")
            ?: return AuthInfo(false, "", 0, 0, 0, "", "Réponse du serveur invalide")
        val srv = o.optJSONObject("server_info")
        val tz = srv?.s("timezone")?.ifBlank { null } ?: "Europe/Paris"
        serverTimezone = tz
        val ok = u.i("auth") == 1
        return AuthInfo(
            ok = ok,
            status = u.s("status"),
            expDate = u.l("exp_date") * 1000,
            maxConnections = u.i("max_connections", 1),
            activeCons = u.i("active_cons"),
            timezone = tz,
            message = if (ok) "" else (u.s("message").ifBlank { "Identifiants refusés" })
        )
    }

    /** Ids des catégories retirées par le filtre permanent (jamais affichées). */
    private val blockedCats = HashSet<String>()

    private fun categories(action: String, name: String, force: Boolean): List<Category> {
        val all = array(cached(name, api(action), SIX_HOURS, force))
            .map { Category(it.s("category_id"), it.s("category_name").trim()) }
        val (bad, good) = all.partition { ContentFilter.blocked(it.name) }
        synchronized(blockedCats) { blockedCats.addAll(bad.map { it.id }) }
        return good
    }

    private fun isBlockedCat(id: String) = synchronized(blockedCats) { id in blockedCats }

    fun liveCategories(force: Boolean = false) = categories("get_live_categories", "live_cats", force)
    fun vodCategories(force: Boolean = false) = categories("get_vod_categories", "vod_cats", force)
    fun seriesCategories(force: Boolean = false) = categories("get_series_categories", "series_cats", force)

    fun liveStreams(force: Boolean = false): List<LiveStream> =
        array(cached("live", api("get_live_streams"), SIX_HOURS, force)).mapNotNull {
            val name = it.s("name").trim()
            val cat = it.s("category_id")
            if (isBlockedCat(cat) || ContentFilter.blocked(name) || it.s("is_adult") == "1") null
            else LiveStream(
                id = it.s("stream_id"),
                num = it.i("num"),
                name = name,
                icon = it.s("stream_icon"),
                catId = cat,
                archive = it.i("tv_archive") == 1,
                archiveDays = it.i("tv_archive_duration"),
                epgId = it.s("epg_channel_id").trim()
            )
        }.also { list ->
            synchronized(epgIds) {
                epgIds.clear(); liveNames.clear()
                list.forEach { s -> if (s.epgId.isNotEmpty()) epgIds.add(s.epgId.lowercase()); liveNames.add(normName(s.name)) }
            }
        }

    fun vods(force: Boolean = false): List<Vod> =
        array(cached("vod", api("get_vod_streams"), SIX_HOURS, force)).mapNotNull {
            val name = it.s("name").trim()
            val cat = it.s("category_id")
            if (isBlockedCat(cat) || ContentFilter.blocked(name) || it.s("is_adult") == "1") null
            else Vod(
                id = it.s("stream_id"),
                name = name,
                icon = it.s("stream_icon"),
                catId = cat,
                rating = it.s("rating"),
                ext = it.s("container_extension").ifBlank { "mp4" },
                added = it.l("added")
            )
        }

    fun series(force: Boolean = false): List<Series> =
        array(cached("series", api("get_series"), SIX_HOURS, force)).mapNotNull {
            val name = it.s("name").trim()
            val cat = it.s("category_id")
            if (isBlockedCat(cat) || ContentFilter.blocked(name)) null
            else Series(
                id = it.s("series_id"),
                name = name,
                cover = it.s("cover"),
                catId = cat,
                rating = it.s("rating"),
                plot = it.s("plot"),
                year = it.s("releaseDate").take(4),
                modified = it.l("last_modified")
            )
        }

    fun vodInfo(id: String): VodInfo {
        val o = JSONObject(fetch(api("get_vod_info", "&vod_id=$id")))
        val info = o.optJSONObject("info") ?: JSONObject()
        val backdrop = info.optJSONArray("backdrop_path")?.optString(0)
            ?: info.s("backdrop_path").ifBlank { info.s("movie_image") }
        return VodInfo(
            plot = info.s("plot").ifBlank { info.s("description") },
            cast = info.s("cast").ifBlank { info.s("actors") },
            director = info.s("director"),
            genre = info.s("genre"),
            duration = info.s("duration"),
            backdrop = backdrop ?: "",
            year = info.s("releasedate").take(4),
            rating = info.s("rating"),
            trailer = info.s("youtube_trailer")
        )
    }

    fun seriesInfo(id: String): SeriesInfo {
        val o = JSONObject(fetch(api("get_series_info", "&series_id=$id")))
        val info = o.optJSONObject("info") ?: JSONObject()
        val map = sortedMapOf<Int, List<Episode>>()
        fun parseEp(e: JSONObject, seasonHint: Int): Episode {
            val ei = e.optJSONObject("info") ?: JSONObject()
            return Episode(
                id = e.s("id"),
                season = e.i("season", seasonHint),
                num = e.i("episode_num"),
                title = e.s("title"),
                ext = e.s("container_extension").ifBlank { "mp4" },
                img = ei.s("movie_image"),
                plot = ei.s("plot"),
                durationSecs = ei.i("duration_secs")
            )
        }
        when (val eps = o.opt("episodes")) {
            is JSONObject -> eps.keys().forEach { k ->
                val season = k.toIntOrNull() ?: 0
                val arr = eps.optJSONArray(k) ?: return@forEach
                map[season] = arr.objects().map { parseEp(it, season) }.sortedBy { it.num }
            }
            is JSONArray -> for (n in 0 until eps.length()) {
                val arr = eps.optJSONArray(n) ?: continue
                val list = arr.objects().map { parseEp(it, n + 1) }
                list.groupBy { it.season }.forEach { (s, l) -> map[s] = l.sortedBy { it.num } }
            }
        }
        val backdrop = info.optJSONArray("backdrop_path")?.optString(0) ?: info.s("cover")
        return SeriesInfo(
            plot = info.s("plot"),
            backdrop = backdrop,
            cast = info.s("cast"),
            genre = info.s("genre"),
            year = info.s("releaseDate").take(4),
            rating = info.s("rating"),
            seasons = map.keys.toList(),
            episodes = map
        )
    }

    private fun b64(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
    } catch (e: Exception) {
        s
    }

    @Volatile private var lastApiRaw = -1

    private fun parseEpg(s: String): List<EpgEntry> {
        val o = try { JSONObject(s) } catch (e: Exception) { return emptyList() }
        val arr = o.optJSONArray("epg_listings") ?: return emptyList()
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone(serverTimezone) }
        fun txt(v: String): Long = try { if (v.isBlank()) 0 else f.parse(v.trim())!!.time } catch (e: Exception) { 0 }
        lastApiRaw = arr.length()
        return arr.objects().map {
            val st = it.l("start_timestamp").takeIf { v -> v > 0 }?.times(1000) ?: txt(it.s("start"))
            val en = (it.l("stop_timestamp").takeIf { v -> v > 0 } ?: it.l("end_timestamp").takeIf { v -> v > 0 })?.times(1000)
                ?: txt(it.s("end").ifBlank { it.s("stop") })
            EpgEntry(
                title = b64(it.s("title")).trim(),
                desc = b64(it.s("description")).trim(),
                start = st,
                end = en,
                startRaw = it.s("start"),
                hasArchive = it.i("has_archive") == 1
            )
        }.filter { it.start > 0 && it.end > it.start }.distinctBy { it.start }.sortedBy { it.start }
    }

    fun shortEpg(streamId: String, limit: Int = 3): List<EpgEntry> =
        parseEpg(fetch(api("get_short_epg", "&stream_id=$streamId&limit=$limit")))

    fun fullEpg(streamId: String): List<EpgEntry> =
        parseEpg(fetch(api("get_simple_data_table", "&stream_id=$streamId")))

    // ---------- Guide complet XMLTV (comme IPTV Smarters), en secours ----------

    private val epgIds = HashSet<String>()
    private val liveNames = HashSet<String>()
    private var xmltv: Map<String, List<EpgEntry>>? = null
    /** Nom de chaîne simplifié → identifiant du guide XMLTV. */
    private var nameToId: Map<String, String> = emptyMap()
    /** Diagnostic : programmes bruts par chaîne (nombre, premières dates telles qu'écrites). */
    private val rawStats = HashMap<String, Triple<Int, String, String>>()
    @Volatile var guideStatus: String = "guide complet pas encore chargé"
        private set

    /** « FR| M6 4K » → « m6 » : pour retrouver la chaîne dans le guide par son nom. */
    fun normName(raw: String): String {
        var n = java.text.Normalizer.normalize(raw.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        val bar = n.indexOfFirst { it == '|' || it == ':' }
        if (bar in 1..6) n = n.substring(bar + 1)
        n = n.replace(Regex("[\\[(].*?[\\])]"), " ")
        n = n.replace(Regex("\\b(4k|uhd|fhd|hd|sd|hevc|h265|h264|1080p?|720p?|50fps|60fps|backup|multi|vip|raw|tnt|fr)\\b"), " ")
        return n.replace(Regex("[^a-z0-9+]"), "")
    }

    /** Identifiant du guide pour une chaîne : celui du fournisseur, sinon par le nom. */
    private fun guideIdFor(s: LiveStream, map: Map<String, List<EpgEntry>>): String? {
        val direct = s.epgId.lowercase()
        if (direct.isNotEmpty() && map.containsKey(direct)) return direct
        return nameToId[normName(s.name)]
    }
    private var xmltvFailedAt = 0L
    private val xmlLock = Any()

    /** Programme d'une chaîne : API rapide d'abord, puis guide XMLTV si elle ne donne rien. */
    fun epgFor(s: LiveStream, full: Boolean): List<EpgEntry> {
        val now = System.currentTimeMillis()
        val api = try { if (full) fullEpg(s.id) else shortEpg(s.id, 4) } catch (e: Exception) { emptyList() }
        val ok = if (full) api.any { it.end > now - 3600_000 } else api.any { it.end > now }
        if (ok) return api
        val x = xmltvForStream(s)
        if (x.isEmpty()) return api
        return if (full) x else x.filter { it.end > now }.take(4)
    }

    fun xmltvForStream(st: LiveStream): List<EpgEntry> {
        val map = synchronized(xmlLock) {
            if (xmltv == null && System.currentTimeMillis() - xmltvFailedAt > 10 * 60_000) {
                guideStatus = "téléchargement du guide complet…"
                xmltv = try { loadXmltv() } catch (e: Exception) {
                    xmltvFailedAt = System.currentTimeMillis()
                    guideStatus = "guide complet indisponible (${e.message})"
                    null
                }
            }
            xmltv
        } ?: return emptyList()
        val id = guideIdFor(st, map) ?: return emptyList()
        return map[id].orEmpty()
    }

    /** Ligne d'explication quand une chaîne n'a pas de programme. */
    fun guideDebug(st: LiveStream): String {
        val map = xmltv
        val id = if (map != null) guideIdFor(st, map) else null
        val list = if (map != null && id != null) map[id].orEmpty() else emptyList()
        val hf = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
        val range = if (list.isEmpty()) "0 prog." else "${list.size} prog. du ${hf.format(java.util.Date(list.first().start))} au ${hf.format(java.util.Date(list.last().end))}"
        val raw = id?.let { rawStats[it] }
        val rawTxt = if (raw == null) "brut : 0" else "brut : ${raw.first} (ex. ${raw.second} → ${raw.third})"
        return "API : $lastApiRaw · guide : ${id ?: "non trouvé"} · $range · $rawTxt · maintenant ${hf.format(java.util.Date())} · $guideStatus · tz $serverTimezone"
    }

    private fun loadXmltv(): Map<String, List<EpgEntry>> {
        val dir = File(cacheDir, "xtream").apply { mkdirs() }
        val f = File(dir, "${acc.key()}_xmltv.xml")
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 12 * 3600_000L || f.length() < 100) {
            val tmp = File(dir, "${acc.key()}_xmltv.tmp")
            val req = Request.Builder().url("${acc.server}/xmltv.php?username=${enc(acc.user)}&password=${enc(acc.pass)}")
                .header("User-Agent", USER_AGENT).build()
            val longHttp = http.newBuilder().readTimeout(5, java.util.concurrent.TimeUnit.MINUTES).build()
            longHttp.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            }
            tmp.renameTo(f)
        }
        val wanted = synchronized(epgIds) { HashSet(epgIds) }
        val names = synchronized(epgIds) { HashSet(liveNames) }
        val chanNames = HashMap<String, MutableList<String>>()
        var channelsDone = false
        var curChannelId: String? = null
        val now = System.currentTimeMillis()
        val minEnd = now - 8 * 86_400_000L
        val maxStart = now + 2 * 86_400_000L
        val out = HashMap<String, MutableList<EpgEntry>>()
        val fmtZ = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone(serverTimezone) }
        fun time(v: String?): Long {
            if (v.isNullOrBlank()) return 0
            return try { fmtZ.parse(v.trim())!!.time } catch (e: Exception) {
                try { fmt.parse(v.trim().take(14))!!.time } catch (e2: Exception) { 0 }
            }
        }
        val parser = android.util.Xml.newPullParser()
        f.inputStream().buffered().use { input ->
            parser.setInput(input, null)
            var channel: String? = null
            var start = 0L; var end = 0L
            var title = ""; var desc = ""
            var field: String? = null
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (event) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name) {
                        "channel" -> { curChannelId = parser.getAttributeValue(null, "id")?.lowercase(); field = null }
                        "display-name" -> field = if (curChannelId != null) "display-name" else null
                        "programme" -> {
                            if (!channelsDone) {
                                channelsDone = true
                                // Chaînes du fournisseur sans id de guide : on les retrouve par leur nom.
                                chanNames.forEach { (n, ids) -> if (n in names) wanted.addAll(ids) }
                            }
                            val ch = parser.getAttributeValue(null, "channel")?.lowercase()
                            channel = if (ch != null && (wanted.isEmpty() || ch in wanted)) ch else null
                            if (channel != null) {
                                val rs = parser.getAttributeValue(null, "start") ?: ""
                                val re = parser.getAttributeValue(null, "stop") ?: ""
                                start = time(rs)
                                end = time(re)
                                title = ""; desc = ""
                                val prev = rawStats[channel!!]
                                rawStats[channel!!] = if (prev == null) Triple(1, rs, re) else Triple(prev.first + 1, prev.second, prev.third)
                            }
                        }
                        "title", "desc" -> field = if (channel != null) parser.name else null
                    }
                    org.xmlpull.v1.XmlPullParser.TEXT -> when (field) {
                        "display-name" -> curChannelId?.let { id -> val n = normName(parser.text); if (n.isNotEmpty()) chanNames.getOrPut(n) { ArrayList() }.let { l -> if (id !in l) l.add(id) } }
                        "title" -> if (title.isEmpty()) title = parser.text.trim()
                        "desc" -> if (desc.isEmpty()) desc = parser.text.trim()
                    }
                    org.xmlpull.v1.XmlPullParser.END_TAG -> when (parser.name) {
                        "title", "desc", "display-name" -> field = null
                        "channel" -> curChannelId = null
                        "programme" -> {
                            val ch = channel
                            if (ch != null && start > 0 && end > start && end > minEnd && start < maxStart) {
                                out.getOrPut(ch) { ArrayList() }.add(EpgEntry(title, desc, start, end, "", false))
                            }
                            channel = null
                        }
                    }
                }
                event = parser.next()
            }
        }
        // Plusieurs fiches « TF1 » dans le guide : garder celle qui a des programmes.
        val byName = HashMap<String, String>()
        chanNames.forEach { (n, ids) -> byName[n] = ids.maxByOrNull { out[it]?.size ?: 0 } ?: ids.first() }
        nameToId = byName
        val result = out.mapValues { (_, l) -> l.distinctBy { it.start }.sortedBy { it.start } }
        guideStatus = "guide complet : ${chanNames.size} chaînes, ${result.size} avec programme"
        return result
    }

    fun liveUrl(id: String, format: String) = "${acc.server}/live/${acc.user}/${acc.pass}/$id.$format"
    fun vodUrl(id: String, ext: String) = "${acc.server}/movie/${acc.user}/${acc.pass}/$id.$ext"
    fun episodeUrl(id: String, ext: String) = "${acc.server}/series/${acc.user}/${acc.pass}/$id.$ext"

    fun timeshiftUrl(streamId: String, e: EpgEntry): String {
        val durMin = ((e.end - e.start) / 60000).toInt().coerceAtLeast(1)
        val start = try {
            val inFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val outFmt = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
            outFmt.format(inFmt.parse(e.startRaw)!!)
        } catch (ex: Exception) {
            val f = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
            f.timeZone = TimeZone.getTimeZone(serverTimezone)
            f.format(java.util.Date(e.start))
        }
        return "${acc.server}/timeshift/${acc.user}/${acc.pass}/$durMin/$start/$streamId.ts"
    }

    companion object {
        const val SIX_HOURS = 6L * 3600 * 1000
    }
}
