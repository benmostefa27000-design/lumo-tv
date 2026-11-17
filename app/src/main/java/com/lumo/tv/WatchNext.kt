package com.lumo.tv

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.lumo.tv.data.Progress

/**
 * « Reprendre » sur l'écran d'accueil Google TV / Android TV (rangée « À suivre » / Watch Next).
 * Pas disponible sur Fire TV : dans ce cas rien ne se passe.
 */
object WatchNext {
    private const val PREFS = "watch_next"

    @SuppressLint("RestrictedApi")
    fun sync(ctx: Context, items: List<Progress>) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cr = ctx.contentResolver
            val keep = items.filter { !it.finished && it.dur > 0 && it.pos > 30_000 }.take(12)
            val keepKeys = keep.map { it.key }.toSet()
            // Retirer ceux qui ne sont plus en cours
            for ((k, v) in prefs.all) {
                if (k !in keepKeys) {
                    val id = (v as? Long) ?: continue
                    try { cr.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null) } catch (_: Exception) { }
                    prefs.edit().remove(k).apply()
                }
            }
            for (p in keep) {
                val uri = Uri.parse("lumo://play").buildUpon()
                    .appendQueryParameter("kind", p.kind).appendQueryParameter("id", p.itemId)
                    .appendQueryParameter("parent", p.parentId).appendQueryParameter("title", p.title)
                    .appendQueryParameter("img", p.img).appendQueryParameter("ext", p.ext).build()
                val b = WatchNextProgram.Builder()
                    .setType(if (p.kind == "ep") TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE else TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                    .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                    .setLastEngagementTimeUtcMillis(p.ts)
                    .setLastPlaybackPositionMillis(p.pos.toInt())
                    .setDurationMillis(p.dur.toInt())
                    .setTitle(com.lumo.tv.data.TmdbArt.split(p.title).first.ifBlank { p.title })
                    .setIntentUri(uri)
                    .setInternalProviderId(p.key)
                if (p.kind == "ep") b.setEpisodeTitle(p.subtitle)
                if (p.img.isNotBlank()) b.setPosterArtUri(Uri.parse(p.img)).setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3)
                val values = b.build().toContentValues()
                val existing = prefs.getLong(p.key, -1L)
                var done = false
                if (existing > 0) {
                    done = try { cr.update(TvContractCompat.buildWatchNextProgramUri(existing), values, null, null) > 0 } catch (_: Exception) { false }
                }
                if (!done) {
                    val newUri = cr.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values) ?: continue
                    prefs.edit().putLong(p.key, android.content.ContentUris.parseId(newUri)).apply()
                }
            }
        } catch (_: Throwable) { }
    }
}
