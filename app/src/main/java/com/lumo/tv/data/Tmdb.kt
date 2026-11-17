package com.lumo.tv.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import kotlinx.coroutines.sync.withPermit

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
        // Arabe : « مسلسل » (série), « فيلم » (film), « برنامج » (émission) devant le titre
        .replace(Regex("""^\s*(مسلسل|فيلم|برنامج|المسلسل|الفيلم)\s+"""), "")
        .replace(Regex("""\s*(مدبلج|مترجم)\s*$"""), "")
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

/** Affiches et grandes images TMDB pour films et séries (avec cache sur l'appareil). */
object TmdbArt {
    data class Art(val poster: String, val backdrop: String)
    private val keyState = androidx.compose.runtime.mutableStateOf("")
    var key: String
        get() = keyState.value
        set(v) { if (keyState.value != v) keyState.value = v }
    @Volatile var http: OkHttpClient? = null
    private var prefs: android.content.SharedPreferences? = null
    private val mem = java.util.concurrent.ConcurrentHashMap<String, Art>()
    private val limiter = kotlinx.coroutines.sync.Semaphore(6)
    private val NONE = Art("", "")

    fun init(ctx: android.content.Context, client: OkHttpClient) {
        prefs = ctx.getSharedPreferences("tmdb_art", android.content.Context.MODE_PRIVATE)
        http = client
    }

    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[أإآ]"), "ا").replace("ة", "ه").replace("ى", "ي")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun nonLatin(s: String) = s.any { it.isLetter() && it.code > 0x24F }

    /** Cherche sur TMDB et renvoie le résultat qui correspond au titre (aussi pour les titres arabes). */
    private fun findBest(client: okhttp3.OkHttpClient, apiKey: String, kind: String, name: String, year: String): org.json.JSONObject? {
        val yp = if (year.isNotBlank()) (if (kind == "movie") "&year=$year" else "&first_air_date_year=$year") else ""
        fun search(lang: String): List<org.json.JSONObject> {
            val url = "https://api.themoviedb.org/3/search/$kind?api_key=$apiKey&language=$lang&query=" + java.net.URLEncoder.encode(name, "UTF-8") + yp
            val arr = client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string()?.let { org.json.JSONObject(it).optJSONArray("results") } else null
            } ?: return emptyList()
            return (0 until arr.length()).map { arr.getJSONObject(it) }
        }
        val target = norm(name)
        if (target.isBlank()) return null
        val titleKey = if (kind == "movie") "title" else "name"
        val origKey = if (kind == "movie") "original_title" else "original_name"
        fun match(l: List<org.json.JSONObject>) = l.firstOrNull { norm(it.optString(titleKey)) == target || norm(it.optString(origKey)) == target }
        val fr = search("fr-FR")
        match(fr)?.let { return it }
        if (nonLatin(name)) {
            // Titre arabe : on compare avec les titres arabes de TMDB, sinon le 1er résultat de la recherche
            val ar = search("ar")
            match(ar)?.let { m -> return fr.firstOrNull { it.optInt("id") == m.optInt("id") } ?: m }
            return fr.firstOrNull()
        }
        return fr.firstOrNull()?.takeIf { year.isNotBlank() }
    }

    // ---------- Logos de titre (comme sur Netflix) et Top 10 ----------

    private val logoMem = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Logo du titre (PNG transparent) en français, sinon anglais, sinon sans langue. « » si aucun. */
    suspend fun logo(kind: String, raw: String): String {
        val apiKey = key
        val client = http ?: return ""
        if (apiKey.isBlank() || raw.isBlank()) return ""
        val k = "logo|$kind|$raw"
        logoMem[k]?.let { return it }
        prefs?.getString(k, null)?.let { v ->
            val parts = v.split("|")
            val age = System.currentTimeMillis() - (parts.getOrNull(1)?.toLongOrNull() ?: 0)
            if (parts[0].isNotBlank() || age < 7L * 86_400_000) { logoMem[k] = parts[0]; return parts[0] }
        }
        return limiter.withPermit {
            val (name, year) = split(raw)
            val url = try {
                val best = findBest(client, apiKey, kind, name, year)
                val id = best?.optInt("id") ?: 0
                if (id <= 0) "" else {
                    val req = okhttp3.Request.Builder().url("https://api.themoviedb.org/3/$kind/$id/images?api_key=$apiKey&include_image_language=fr,en,null").build()
                    val arr = client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string()?.let { org.json.JSONObject(it).optJSONArray("logos") } else null }
                    val logos = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }
                    val pick = logos.firstOrNull { it.optString("iso_639_1") == "fr" } ?: logos.firstOrNull { it.optString("iso_639_1") == "en" } ?: logos.firstOrNull()
                    pick?.optString("file_path")?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w500$it" } ?: ""
                }
            } catch (_: Exception) { return@withPermit "" }
            logoMem[k] = url
            prefs?.edit()?.putString(k, "$url|${System.currentTimeMillis()}")?.apply()
            url
        }
    }

    /** Films les plus vus de la semaine (titres français et originaux) pour la rangée « Top 10 ». */
    suspend fun trendingTitles(): List<Pair<String, String>> {
        val apiKey = key
        val client = http ?: return emptyList()
        if (apiKey.isBlank()) return emptyList()
        return try {
            val out = mutableListOf<Pair<String, String>>()
            for (page in 1..2) {
                val req = okhttp3.Request.Builder().url("https://api.themoviedb.org/3/trending/movie/week?api_key=$apiKey&language=fr-FR&page=$page").build()
                val arr = client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string()?.let { org.json.JSONObject(it).optJSONArray("results") } else null } ?: break
                for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); out.add(o.optString("title") to o.optString("original_title")) }
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    /** « FR - Malfaisante (2026) » → (« Malfaisante », « 2026 ») */
    fun split(raw: String): Pair<String, String> {
        val year = Regex("""\((\d{4})\)""").find(raw)?.groupValues?.get(1)
            ?: Regex("""\b(19|20)\d{2}\s*$""").find(raw.trim())?.value?.trim() ?: ""
        val name = Tmdb.cleanName(raw).replace(Regex("""\b(19|20)\d{2}\s*$"""), "").trim()
        return name to year
    }

    fun cached(kind: String, raw: String): Art? {
        val k = "2|$kind|$raw"
        mem[k]?.let { return it }
        val v = prefs?.getString(k, null) ?: return null
        val parts = v.split("|")
        val ts = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        val art = Art(parts.getOrNull(0) ?: "", parts.getOrNull(1) ?: "")
        // Un « pas trouvé » est retenté au bout de 7 jours
        if (art == NONE && System.currentTimeMillis() - ts > 7L * 86_400_000) return null
        mem[k] = art
        return art
    }

    private val overviews = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Résumé en français depuis TMDB (quand le fournisseur n'en donne pas). */
    suspend fun overview(kind: String, raw: String): String {
        val apiKey = key
        val client = http ?: return ""
        if (apiKey.isBlank() || raw.isBlank()) return ""
        overviews["$kind|$raw"]?.let { return it }
        return limiter.withPermit {
            val (name, year) = split(raw)
            val txt = try { findBest(client, apiKey, kind, name, year)?.optString("overview")?.trim() ?: "" } catch (_: Exception) { "" }
            overviews["$kind|$raw"] = txt
            txt
        }
    }

    suspend fun lookup(kind: String, raw: String): Art? {
        val apiKey = key
        val client = http ?: return null
        if (apiKey.isBlank() || raw.isBlank()) return null
        cached(kind, raw)?.let { return it.takeIf { a -> a != NONE } }
        return limiter.withPermit {
            cached(kind, raw)?.let { return@withPermit it.takeIf { a -> a != NONE } }
            val (name, year) = split(raw)
            val art = try {
                val best = findBest(client, apiKey, kind, name, year)
                if (best == null) NONE else Art(
                    best.optString("poster_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w342$it" } ?: "",
                    best.optString("backdrop_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w1280$it" } ?: ""
                )
            } catch (_: Exception) { null }
            if (art != null) {
                val k = "2|$kind|$raw"
                mem[k] = art
                prefs?.edit()?.putString(k, "${art.poster}|${art.backdrop}|${System.currentTimeMillis()}")?.apply()
            }
            art?.takeIf { it != NONE }
        }
    }
}
