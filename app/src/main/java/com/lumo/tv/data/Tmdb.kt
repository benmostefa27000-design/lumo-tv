package com.lumo.tv.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/** TMDB : vraies images d'épisodes + titres et résumés en français (clé de l'utilisateur). */
object Tmdb {
    class Ep(val name: String, val still: String, val overview: String)
    private val showCache = HashMap<String, Int?>()
    private val seasonCache = HashMap<String, Map<Int, Ep>>()
    private const val IMG = "https://image.tmdb.org/t/p/w500"

    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]"), "")

    fun cleanName(n: String) = n
        .replace(Regex("""^\s*[A-Za-z]{2,4}\s*[-|:]\s*"""), "")
        .replace(Regex("""\(\d{4}\)"""), "")
        .replace(Regex("""(?i)\b(4k|fhd|uhd|hd|vf|vostfr|multi)\b"""), "")
        .trim()

    private fun get(http: OkHttpClient, url: String): JSONObject? =
        http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string()?.let { JSONObject(it) } else null }

    /** Identifiant TMDB de la série : celui donné par le fournisseur, sinon recherche par nom (+ année). */
    fun showId(http: OkHttpClient, key: String, name: String, year: String, providerId: String): Int? {
        providerId.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        val clean = cleanName(name)
        val ck = clean.lowercase() + "|" + year
        synchronized(showCache) { if (showCache.containsKey(ck)) return showCache[ck] }
        val id = try {
            val y = if (year.length == 4) "&first_air_date_year=$year" else ""
            val res = get(http, "https://api.themoviedb.org/3/search/tv?api_key=$key&language=fr-FR&query=" + URLEncoder.encode(clean, "UTF-8") + y)
                ?.optJSONArray("results")
            if (res == null || res.length() == 0) null else {
                val list = (0 until res.length()).map { res.getJSONObject(it) }
                val target = norm(clean)
                (list.firstOrNull { norm(it.optString("name")) == target || norm(it.optString("original_name")) == target } ?: list.first()).optInt("id")
            }
        } catch (_: Exception) { null }
        synchronized(showCache) { showCache[ck] = id }
        return id
    }

    fun season(http: OkHttpClient, key: String, id: Int, season: Int): Map<Int, Ep> {
        val ck = "$id/$season"
        synchronized(seasonCache) { seasonCache[ck]?.let { return it } }
        val map = HashMap<Int, Ep>()
        try {
            val eps = get(http, "https://api.themoviedb.org/3/tv/$id/season/$season?api_key=$key&language=fr-FR")?.optJSONArray("episodes")
            if (eps != null) for (i in 0 until eps.length()) {
                val e = eps.getJSONObject(i)
                val still = e.optString("still_path").takeIf { it.startsWith("/") }?.let { IMG + it } ?: ""
                map[e.optInt("episode_number")] = Ep(e.optString("name"), still, e.optString("overview"))
            }
        } catch (_: Exception) { }
        synchronized(seasonCache) { seasonCache[ck] = map }
        return map
    }
}
