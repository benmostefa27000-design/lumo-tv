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
                archiveDays = it.i("tv_archive_duration")
            )
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

    private fun parseEpg(s: String): List<EpgEntry> {
        val o = try { JSONObject(s) } catch (e: Exception) { return emptyList() }
        val arr = o.optJSONArray("epg_listings") ?: return emptyList()
        return arr.objects().map {
            EpgEntry(
                title = b64(it.s("title")).trim(),
                desc = b64(it.s("description")).trim(),
                start = it.l("start_timestamp") * 1000,
                end = (it.l("stop_timestamp").takeIf { v -> v > 0 } ?: it.l("end_timestamp")) * 1000,
                startRaw = it.s("start"),
                hasArchive = it.i("has_archive") == 1
            )
        }.filter { it.start > 0 && it.end > it.start }.distinctBy { it.start }.sortedBy { it.start }
    }

    fun shortEpg(streamId: String, limit: Int = 3): List<EpgEntry> =
        parseEpg(fetch(api("get_short_epg", "&stream_id=$streamId&limit=$limit")))

    fun fullEpg(streamId: String): List<EpgEntry> =
        parseEpg(fetch(api("get_simple_data_table", "&stream_id=$streamId")))

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
