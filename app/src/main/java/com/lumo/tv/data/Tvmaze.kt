package com.lumo.tv.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/** Complète les épisodes (titre, image, résumé) avec TVmaze quand le fournisseur ne les donne pas. Gratuit, sans clé. */
object Tvmaze {
    private class Ep(val name: String, val img: String, val summary: String)
    private val cache = HashMap<String, Map<Pair<Int, Int>, Ep>?>()
    private val generic = Regex("""(?i)(s\d{1,2}\s*e\d{1,3}|episode\s*\d+|épisode\s*\d+)\s*$""")

    private fun cleanName(n: String) = n
        .replace(Regex("""^\s*[A-Za-z]{2,4}\s*[-|:]\s*"""), "")
        .replace(Regex("""\(\d{4}\)"""), "")
        .replace(Regex("""(?i)\b(4k|fhd|uhd|hd|vf|vostfr|multi)\b"""), "")
        .trim()

    private fun load(http: OkHttpClient, seriesName: String): Map<Pair<Int, Int>, Ep>? {
        val key = cleanName(seriesName).lowercase()
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val res = try {
            val url = "https://api.tvmaze.com/singlesearch/shows?q=" + URLEncoder.encode(cleanName(seriesName), "UTF-8") + "&embed=episodes"
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) null else {
                    val eps = JSONObject(r.body!!.string()).optJSONObject("_embedded")?.optJSONArray("episodes")
                    if (eps == null) null else {
                        val map = HashMap<Pair<Int, Int>, Ep>()
                        for (i in 0 until eps.length()) {
                            val e = eps.getJSONObject(i)
                            val img = e.optJSONObject("image")?.let { it.optString("original").ifBlank { it.optString("medium") } } ?: ""
                            val sum = e.optString("summary").replace(Regex("<[^>]+>"), "").let { if (it == "null") "" else it.trim() }
                            map[e.optInt("season") to e.optInt("number")] = Ep(e.optString("name"), img, sum)
                        }
                        map
                    }
                }
            }
        } catch (_: Exception) { null }
        synchronized(cache) { cache[key] = res }
        return res
    }

    /** Renvoie les infos de série avec les épisodes complétés. Image de repli : grande image de la série. */
    fun enrich(http: OkHttpClient, seriesName: String, cover: String, info: SeriesInfo): SeriesInfo {
        val needs = info.episodes.values.flatten().any { it.img.isBlank() || generic.containsMatchIn(it.title.trim()) || it.title.isBlank() }
        if (!needs) return info
        val tm = load(http, seriesName)
        val fallbackImg = info.backdrop.ifBlank { cover }
        val eps = info.episodes.mapValues { (_, list) ->
            list.map { ep ->
                val t = tm?.get(ep.season to ep.num)
                val title = if (t != null && t.name.isNotBlank() && (ep.title.isBlank() || generic.containsMatchIn(ep.title.trim())))
                    (if (ep.title.isBlank()) t.name else "${ep.title.trim()} - ${t.name}") else ep.title
                ep.copy(
                    title = title,
                    img = ep.img.ifBlank { t?.img?.ifBlank { null } ?: fallbackImg },
                    plot = ep.plot.ifBlank { t?.summary ?: "" }
                )
            }
        }
        return info.copy(episodes = eps)
    }
}
