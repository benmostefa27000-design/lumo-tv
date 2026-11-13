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

    /** Télécharge une liste dans un fichier (sans tout garder en mémoire) et le réutilise quelques heures. */
    private fun cachedFile(name: String, url: String, maxAgeMs: Long, force: Boolean): File {
        val dir = File(cacheDir, "xtream").apply { mkdirs() }
        val f = File(dir, "${acc.key()}_$name.json")
        if (!force && f.exists() && f.length() > 0 && System.currentTimeMillis() - f.lastModified() < maxAgeMs) return f
        try {
            val tmp = File(dir, "${acc.key()}_$name.tmp")
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            }
            if (tmp.length() > 0) { if (f.exists()) f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) {
            if (!f.exists()) throw e
        }
        return f
    }

    /**
     * Lit un tableau JSON objet par objet (JsonReader), en ne gardant que les champs utiles.
     * Évite de charger tout le catalogue en mémoire d'un coup (indispensable sur Fire Stick).
     */
    private fun streamArray(f: File, keys: Set<String>, each: (Map<String, String>) -> Unit) {
        if (!f.exists() || f.length() == 0L) return
        android.util.JsonReader(java.io.InputStreamReader(f.inputStream().buffered(65536), Charsets.UTF_8)).use { r ->
            r.isLenient = true
            if (r.peek() != android.util.JsonToken.BEGIN_ARRAY) return
            r.beginArray()
            val row = HashMap<String, String>(keys.size * 2)
            while (r.hasNext()) {
                if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                row.clear()
                r.beginObject()
                while (r.hasNext()) {
                    val k = r.nextName()
                    if (k !in keys) { r.skipValue(); continue }
                    row[k] = when (r.peek()) {
                        android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> r.nextString()
                        android.util.JsonToken.BOOLEAN -> r.nextBoolean().toString()
                        android.util.JsonToken.NULL -> { r.nextNull(); "" }
                        else -> { r.skipValue(); "" }
                    }
                }
                r.endObject()
                each(row)
            }
        }
    }

    private fun Map<String, String>.str(k: String) = this[k] ?: ""
    private fun Map<String, String>.int(k: String) = this[k]?.trim()?.toDoubleOrNull()?.toInt() ?: 0
    private fun Map<String, String>.long(k: String) = this[k]?.trim()?.toDoubleOrNull()?.toLong() ?: 0L

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
        val all = ArrayList<Category>()
        streamArray(cachedFile(name, api(action), SIX_HOURS, force), setOf("category_id", "category_name")) {
            all.add(Category(it.str("category_id"), it.str("category_name").trim()))
        }
        val (bad, good) = all.partition { ContentFilter.blocked(it.name) }
        synchronized(blockedCats) { blockedCats.addAll(bad.map { it.id }) }
        return good
    }

    private fun isBlockedCat(id: String) = synchronized(blockedCats) { id in blockedCats }

    fun liveCategories(force: Boolean = false) = categories("get_live_categories", "live_cats", force)
    fun vodCategories(force: Boolean = false) = categories("get_vod_categories", "vod_cats", force)
    fun seriesCategories(force: Boolean = false) = categories("get_series_categories", "series_cats", force)

    fun liveStreams(force: Boolean = false): List<LiveStream> {
        val out = ArrayList<LiveStream>()
        val keys = setOf("stream_id", "num", "name", "stream_icon", "category_id", "tv_archive", "tv_archive_duration", "epg_channel_id", "is_adult")
        streamArray(cachedFile("live", api("get_live_streams"), SIX_HOURS, force), keys) {
            val name = it.str("name").trim()
            val cat = it.str("category_id")
            if (!isBlockedCat(cat) && !ContentFilter.blocked(name) && it.str("is_adult") != "1") out.add(
                LiveStream(
                    id = it.str("stream_id"),
                    num = it.int("num"),
                    name = name,
                    icon = it.str("stream_icon"),
                    catId = cat,
                    archive = it.int("tv_archive") == 1,
                    archiveDays = it.int("tv_archive_duration"),
                    epgId = it.str("epg_channel_id").trim()
                )
            )
        }
        synchronized(epgIds) {
            epgIds.clear(); liveNames.clear()
            out.forEach { s -> if (s.epgId.isNotEmpty()) epgIds.add(s.epgId.lowercase()); liveNames.add(normName(s.name)) }
        }
        return out
    }

    fun vods(force: Boolean = false): List<Vod> {
        val out = ArrayList<Vod>()
        val keys = setOf("stream_id", "name", "stream_icon", "category_id", "rating", "container_extension", "added", "is_adult")
        streamArray(cachedFile("vod", api("get_vod_streams"), SIX_HOURS, force), keys) {
            val name = it.str("name").trim()
            val cat = it.str("category_id")
            if (!isBlockedCat(cat) && !ContentFilter.blocked(name) && it.str("is_adult") != "1") out.add(
                Vod(
                    id = it.str("stream_id"),
                    name = name,
                    icon = it.str("stream_icon"),
                    catId = cat,
                    rating = it.str("rating"),
                    ext = it.str("container_extension").ifBlank { "mp4" },
                    added = it.long("added")
                )
            )
        }
        return out
    }

    fun series(force: Boolean = false): List<Series> {
        val out = ArrayList<Series>()
        val keys = setOf("series_id", "name", "cover", "category_id", "rating", "plot", "releaseDate", "last_modified")
        streamArray(cachedFile("series", api("get_series"), SIX_HOURS, force), keys) {
            val name = it.str("name").trim()
            val cat = it.str("category_id")
            if (!isBlockedCat(cat) && !ContentFilter.blocked(name)) out.add(
                Series(
                    id = it.str("series_id"),
                    name = name,
                    cover = it.str("cover"),
                    catId = cat,
                    rating = it.str("rating"),
                    plot = it.str("plot").take(300),
                    year = it.str("releaseDate").take(4),
                    modified = it.long("last_modified")
                )
            )
        }
        return out
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

    // ---------- Guides TV complets (XMLTV) ----------
    // 1. API du fournisseur  2. Guide public français  3. Guide arabe/algérien  4. XMLTV du fournisseur

    private val epgIds = HashSet<String>()
    private val liveNames = HashSet<String>()

    private class Guide(val map: Map<String, List<EpgEntry>>, val nameToId: Map<String, String>)

    // Sources : 0 = guide français xmltvfr.fr, 1 = guide arabe/algérien epgshare01, 2 = XMLTV du fournisseur
    private val guides = arrayOfNulls<Guide>(3)
    private val guideFailedAt = LongArray(3)
    private val guideLocks = Array(3) { Any() }

    /** « FR| M6 4K » → « m6 » : pour retrouver la chaîne dans un guide par son nom. */
    fun normName(raw: String): String {
        var n = java.text.Normalizer.normalize(raw.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        val bar = n.indexOfFirst { it == '|' || it == ':' }
        if (bar in 1..6) n = n.substring(bar + 1)
        n = n.replace(Regex("[\\[(].*?[\\])]"), " ")
        n = n.replace(Regex("\\b(4k|uhd|fhd|hd|sd|hevc|h265|h264|1080p?|720p?|50fps|60fps|backup|multi|vip|raw|tnt|fr)\\b"), " ")
        n = n.replace(Regex("[^a-z0-9+]"), "")
        n = n.replace("algeria", "algerie").replace("eldjazair", "aldjazair").replace("eldjazayer", "aldjazair")
            .replace("echourouk", "echorouk").replace("chorouk", "echorouk").replace("eechorouk", "echorouk")
        if (n.length > 4 && n.endsWith("tv")) n = n.dropLast(2)
        return n
    }

    private fun listFrom(g: Guide?, s: LiveStream): List<EpgEntry> {
        if (g == null) return emptyList()
        val direct = s.epgId.lowercase()
        val id = if (direct.isNotEmpty() && g.map.containsKey(direct)) direct else g.nameToId[normName(s.name)]
        return id?.let { g.map[it] }.orEmpty()
    }

    /** Programme d'une chaîne, en essayant les sources l'une après l'autre. */
    fun epgFor(s: LiveStream, full: Boolean): List<EpgEntry> {
        val now = System.currentTimeMillis()
        fun useful(l: List<EpgEntry>) = if (full) l.any { it.end > now - 3600_000 } else l.any { it.end > now }
        fun shape(l: List<EpgEntry>) = if (full) l else l.filter { it.end > now }.take(4)

        val api = try { if (full) fullEpg(s.id) else shortEpg(s.id, 4) } catch (e: Exception) { emptyList() }
        if (useful(api)) return api
        if (!full) {
            val table = try { fullEpg(s.id) } catch (e: Exception) { emptyList() }
            if (useful(table)) return shape(table)
        }
        for (src in 0..2) {
            val l = listFrom(guide(src), s)
            if (useful(l)) return shape(l)
        }
        return api
    }

    private fun guide(src: Int): Guide? {
        synchronized(guideLocks[src]) {
            guides[src]?.let { return it }
            if (System.currentTimeMillis() - guideFailedAt[src] < 10 * 60_000) return null
            return try {
                val g = when (src) {
                    0 -> loadGuide("https://xmltvfr.fr/xmltv/xmltv_fr.xml.gz", "guide_fr.xml.gz", gz = true)
                    1 -> loadGuide("https://epgshare01.online/epgshare01/epg_ripper_AE1.xml.gz", "guide_ar.xml.gz", gz = true)
                    else -> loadGuide("${acc.server}/xmltv.php?username=${enc(acc.user)}&password=${enc(acc.pass)}", "${acc.key()}_xmltv.xml", gz = false)
                }
                guides[src] = g
                g
            } catch (e: Throwable) {
                guideFailedAt[src] = System.currentTimeMillis()
                null
            }
        }
    }

    private fun loadGuide(url: String, fileName: String, gz: Boolean): Guide {
        val dir = File(cacheDir, "xtream").apply { mkdirs() }
        val f = File(dir, fileName)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 12 * 3600_000L || f.length() < 100) {
            val tmp = File(dir, "$fileName.tmp")
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            val longHttp = http.newBuilder().readTimeout(5, java.util.concurrent.TimeUnit.MINUTES).build()
            longHttp.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            }
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        }
        val raw = f.inputStream().buffered()
        val input = if (gz) java.util.zip.GZIPInputStream(raw, 65536) else raw
        return input.use { parseXmltv(it) }
    }

    private fun parseXmltv(input: java.io.InputStream): Guide {
        val wanted = synchronized(epgIds) { HashSet(epgIds) }
        val names = synchronized(epgIds) { HashSet(liveNames) }
        val chanNames = HashMap<String, MutableList<String>>()
        var channelsDone = false
        var curChannelId: String? = null
        val now = System.currentTimeMillis()
        val minEnd = now - 8 * 86_400_000L
        val maxStart = now + 3 * 86_400_000L
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
                            chanNames.forEach { (n, ids) -> if (n in names) wanted.addAll(ids) }
                        }
                        val ch = parser.getAttributeValue(null, "channel")?.lowercase()
                        channel = if (ch != null && ch in wanted) ch else null
                        if (channel != null) {
                            start = time(parser.getAttributeValue(null, "start"))
                            end = time(parser.getAttributeValue(null, "stop"))
                            title = ""; desc = ""
                        }
                    }
                    "title", "desc" -> field = if (channel != null) parser.name else null
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> when (field) {
                    "display-name" -> curChannelId?.let { id ->
                        val n = normName(parser.text)
                        if (n.isNotEmpty()) chanNames.getOrPut(n) { ArrayList() }.let { l -> if (id !in l) l.add(id) }
                    }
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
        val map = out.mapValues { (_, l) -> l.distinctBy { it.start }.sortedBy { it.start } }
        val byName = HashMap<String, String>()
        chanNames.forEach { (n, ids) -> byName[n] = ids.maxByOrNull { map[it]?.size ?: 0 } ?: ids.first() }
        return Guide(map, byName)
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
