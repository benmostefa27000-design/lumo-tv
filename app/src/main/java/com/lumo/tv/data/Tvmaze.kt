package com.lumo.tv.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Complète les épisodes (titre, image, résumé) avec TVmaze. Gratuit, sans clé. */
object Tvmaze {
    private class Ep(val name: String, val img: String, val summary: String)
    private class Show(val eps: Map<Pair<Int, Int>, Ep>, val images: List<String>)
    private val cache = HashMap<String, Show?>()
    private val generic = Regex("""(?i)(s\d{1,2}\s*e\d{1,3}|episode\s*\d+|épisode\s*\d+)\s*$""")

    private fun cleanName(n: String) = n
        .replace(Regex("""^\s*[A-Za-z]{2,4}\s*[-|:]\s*"""), "")
        .replace(Regex("""\(\d{4}\)"""), "")
        .replace(Regex("""(?i)\b(4k|fhd|uhd|hd|vf|vostfr|multi)\b"""), "")
        .trim()

    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]"), "")

    private fun get(http: OkHttpClient, url: String): String? =
        http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }

    private fun load(http: OkHttpClient, seriesName: String, year: String): Show? {
        val name = cleanName(seriesName)
        val key = name.lowercase()
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val res = try {
            val body = get(http, "https://api.tvmaze.com/singlesearch/shows?q=" + URLEncoder.encode(name, "UTF-8") + "&embed=episodes")
            body?.let { JSONObject(it) }?.let { show ->
                // On vérifie que c'est bien la même série (même nom, ou même année)
                val sameName = norm(show.optString("name")) == norm(name)
                val y = show.optString("premiered").take(4)
                val sameYear = year.length == 4 && y == year
                if (!sameName && !sameYear) null else {
                    val map = HashMap<Pair<Int, Int>, Ep>()
                    show.optJSONObject("_embedded")?.optJSONArray("episodes")?.let { eps ->
                        for (i in 0 until eps.length()) {
                            val e = eps.getJSONObject(i)
                            val img = e.optJSONObject("image")?.let { it.optString("original").ifBlank { it.optString("medium") } } ?: ""
                            val sum = e.optString("summary").replace(Regex("<[^>]+>"), "").let { if (it == "null") "" else it.trim() }
                            map[e.optInt("season") to e.optInt("number")] = Ep(e.optString("name"), img, sum)
                        }
                    }
                    // Grandes images de la série (fonds d'écran en priorité)
                    val imgs = try {
                        val arr = JSONArray(get(http, "https://api.tvmaze.com/shows/${show.optInt("id")}/images") ?: "[]")
                        val list = (0 until arr.length()).map { arr.getJSONObject(it) }
                        list.sortedBy { when (it.optString("type")) { "background" -> 0; "banner" -> 1; else -> 2 } }
                            .filter { it.optString("type") in setOf("background", "banner") }
                            .mapNotNull { it.optJSONObject("resolutions")?.optJSONObject("original")?.optString("url")?.takeIf { u -> u.startsWith("http") } }
                    } catch (_: Exception) { emptyList() }
                    Show(map, imgs)
                }
            }
        } catch (_: Exception) { null }
        synchronized(cache) { cache[key] = res }
        return res
    }

    /** Épisodes complétés + une vignette de repli différente pour chaque épisode. */
    fun enrich(http: OkHttpClient, seriesName: String, cover: String, info: SeriesInfo): SeriesInfo {
        val tm = load(http, seriesName, info.year)
        val pool = (tm?.images.orEmpty() + info.images + listOf(info.backdrop, cover))
            .filter { it.startsWith("http") }.distinct()
        var n = 0
        val eps = info.episodes.toSortedMap().mapValues { (_, list) ->
            list.map { ep ->
                val t = tm?.eps?.get(ep.season to ep.num)
                val title = if (t != null && t.name.isNotBlank() && (ep.title.isBlank() || generic.containsMatchIn(ep.title.trim())))
                    (if (ep.title.isBlank()) t.name else "${ep.title.trim()} - ${t.name}") else ep.title
                val alt = t?.img?.ifBlank { null } ?: pool.getOrNull(n % pool.size.coerceAtLeast(1)) ?: ""
                n++
                ep.copy(title = title, plot = ep.plot.ifBlank { t?.summary ?: "" }, alt = alt)
            }
        }
        return info.copy(episodes = eps, images = (info.images + tm?.images.orEmpty()).distinct())
    }
}
