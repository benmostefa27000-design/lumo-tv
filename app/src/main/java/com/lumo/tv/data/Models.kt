package com.lumo.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class Account(val server: String, val user: String, val pass: String) {
    /** Clé stable et non devinable qui identifie ce compte dans la base de synchro. */
    fun key(): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest("$server|$user|$pass".toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun toJson(): JSONObject = JSONObject().put("server", server).put("user", user).put("pass", pass)

    companion object {
        fun fromJson(o: JSONObject?): Account? {
            if (o == null) return null
            val s = o.optString("server"); val u = o.optString("user"); val p = o.optString("pass")
            if (s.isBlank() || u.isBlank()) return null
            return Account(s, u, p)
        }

        /** Accepte "host:port", "http://host:port/", ou un lien M3U complet avec username/password. */
        fun parse(input: String, user: String, pass: String): Account? {
            var raw = input.trim()
            if (raw.isEmpty()) return null
            if (!raw.startsWith("http://") && !raw.startsWith("https://")) raw = "http://$raw"
            var u = user.trim()
            var p = pass.trim()
            val qIndex = raw.indexOf('?')
            if (qIndex > 0) {
                val query = raw.substring(qIndex + 1)
                query.split('&').forEach { kv ->
                    val parts = kv.split('=', limit = 2)
                    if (parts.size == 2) {
                        val v = java.net.URLDecoder.decode(parts[1], "UTF-8")
                        if (parts[0] == "username" && u.isEmpty()) u = v
                        if (parts[0] == "password" && p.isEmpty()) p = v
                    }
                }
                raw = raw.substring(0, qIndex)
            }
            // Ne garder que schéma://hôte:port
            val schemeEnd = raw.indexOf("://") + 3
            val slash = raw.indexOf('/', schemeEnd)
            if (slash > 0) raw = raw.substring(0, slash)
            if (u.isEmpty()) return null
            return Account(raw, u, p)
        }
    }
}

data class AuthInfo(
    val ok: Boolean,
    val status: String,
    val expDate: Long,
    val maxConnections: Int,
    val activeCons: Int,
    val timezone: String,
    val message: String
)

data class Category(val id: String, val name: String)

data class LiveStream(
    val id: String,
    val num: Int,
    val name: String,
    val icon: String,
    val catId: String,
    val archive: Boolean,
    val archiveDays: Int,
    val epgId: String = ""
)

data class Vod(
    val id: String,
    val name: String,
    val icon: String,
    val catId: String,
    val rating: String,
    val ext: String,
    val added: Long
)

data class Series(
    val id: String,
    val name: String,
    val cover: String,
    val catId: String,
    val rating: String,
    val plot: String,
    val year: String,
    val modified: Long
)

data class VodInfo(
    val plot: String,
    val cast: String,
    val director: String,
    val genre: String,
    val duration: String,
    val backdrop: String,
    val year: String,
    val rating: String,
    val trailer: String
)

data class Episode(
    val id: String,
    val season: Int,
    val num: Int,
    val title: String,
    val ext: String,
    val img: String,
    val plot: String,
    val durationSecs: Int
)

data class SeriesInfo(
    val plot: String,
    val backdrop: String,
    val cast: String,
    val genre: String,
    val year: String,
    val rating: String,
    val seasons: List<Int>,
    val episodes: Map<Int, List<Episode>>
)

data class EpgEntry(
    val title: String,
    val desc: String,
    val start: Long,
    val end: Long,
    val startRaw: String,
    val hasArchive: Boolean
)

data class Profile(val id: String, val name: String, val color: Long, val kids: Boolean, val order: Int)

data class Progress(
    val key: String,
    val kind: String,      // "vod" ou "ep"
    val itemId: String,    // id du film ou de l'épisode
    val parentId: String,  // id de la série pour un épisode
    val title: String,
    val subtitle: String,
    val img: String,
    val ext: String,
    val pos: Long,
    val dur: Long,
    val ts: Long
) {
    val fraction: Float get() = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val finished: Boolean get() = dur > 0 && pos > dur * 0.93

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind).put("id", itemId).put("parent", parentId)
        .put("title", title).put("sub", subtitle).put("img", img).put("ext", ext)
        .put("pos", pos).put("dur", dur).put("ts", ts)

    companion object {
        fun fromJson(key: String, o: JSONObject) = Progress(
            key = key,
            kind = o.optString("kind"),
            itemId = o.optString("id"),
            parentId = o.optString("parent"),
            title = o.optString("title"),
            subtitle = o.optString("sub"),
            img = o.optString("img"),
            ext = o.optString("ext", "mp4"),
            pos = o.optLong("pos"),
            dur = o.optLong("dur"),
            ts = o.optLong("ts")
        )
    }
}

/** Lecture tolérante des JSON Xtream (nombres en texte, null, etc.). */
fun JSONObject.s(k: String): String {
    val v = opt(k)
    return if (v == null || v == JSONObject.NULL) "" else v.toString()
}

fun JSONObject.i(k: String, d: Int = 0): Int = s(k).trim().toDoubleOrNull()?.toInt() ?: d
fun JSONObject.l(k: String, d: Long = 0): Long = s(k).trim().toDoubleOrNull()?.toLong() ?: d

fun JSONArray.objects(): List<JSONObject> {
    val out = ArrayList<JSONObject>(length())
    for (n in 0 until length()) optJSONObject(n)?.let { out.add(it) }
    return out
}
