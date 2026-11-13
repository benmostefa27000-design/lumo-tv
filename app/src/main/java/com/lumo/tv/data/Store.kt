package com.lumo.tv.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/** Une modification locale à envoyer à la base de synchro (value == null : suppression). */
data class Change(val path: String, val value: JSONObject?)

/**
 * Données de l'utilisateur, gardées sur l'appareil et copiées dans la base de synchro :
 * profils, catégories supprimées, favoris et reprise de lecture par profil.
 */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("lumo", Context.MODE_PRIVATE)

    /** Change à chaque modification, pour rafraîchir l'interface. */
    var version by mutableIntStateOf(0)
        private set

    private var doc: JSONObject = try {
        JSONObject(prefs.getString("doc", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    var account: Account?
        get() = try { Account.fromJson(JSONObject(prefs.getString("account", "") ?: "")) } catch (e: Exception) { null }
        set(v) { prefs.edit().putString("account", v?.toJson()?.toString() ?: "").apply(); version++ }

    var liveFormat: String
        get() = prefs.getString("liveFormat", "ts") ?: "ts"
        set(v) { prefs.edit().putString("liveFormat", v).apply(); version++ }

    var profileId: String?
        get() = prefs.getString("profileId", null)
        set(v) { prefs.edit().putString("profileId", v).apply(); version++ }

    var lastSync: Long
        get() = prefs.getLong("lastSync", 0)
        set(v) { prefs.edit().putLong("lastSync", v).apply() }

    fun clearAll() {
        prefs.edit().clear().apply()
        doc = JSONObject()
        version++
    }

    private fun save() {
        prefs.edit().putString("doc", doc.toString()).apply()
        version++
    }

    private fun obj(parent: JSONObject, key: String): JSONObject =
        parent.optJSONObject(key) ?: JSONObject().also { parent.put(key, it) }

    private fun csv(s: String): Set<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun now() = System.currentTimeMillis()

    // ---------- Catégories supprimées (communes à tous les profils) ----------

    fun deletedCats(kind: String): Set<String> = csv(doc.optJSONObject("cats")?.optString(kind) ?: "")

    fun setDeletedCats(kind: String, ids: Set<String>): Change {
        val c = obj(doc, "cats")
        c.put(kind, ids.joinToString(","))
        c.put("ts", now())
        save()
        return Change("cats", JSONObject(c.toString()))
    }

    // ---------- Profils ----------

    fun profiles(): List<Profile> {
        val items = doc.optJSONObject("profiles")?.optJSONObject("items") ?: return emptyList()
        return items.keys().asSequence().mapNotNull { id ->
            val o = items.optJSONObject(id) ?: return@mapNotNull null
            Profile(id, o.optString("name"), o.optLong("color", 0xFF2F6FD6), o.optBoolean("kids"), o.optInt("order"))
        }.sortedBy { it.order }.toList()
    }

    fun saveProfile(p: Profile): Change {
        val sec = obj(doc, "profiles")
        val items = obj(sec, "items")
        items.put(p.id, JSONObject().put("name", p.name).put("color", p.color).put("kids", p.kids).put("order", p.order))
        sec.put("ts", now())
        save()
        return Change("profiles", JSONObject(sec.toString()))
    }

    fun deleteProfile(id: String): List<Change> {
        val sec = obj(doc, "profiles")
        obj(sec, "items").remove(id)
        sec.put("ts", now())
        doc.optJSONObject("p")?.remove(id)
        save()
        return listOf(Change("profiles", JSONObject(sec.toString())), Change("p/$id", null))
    }

    // ---------- Favoris (par profil) ----------

    private fun profileDoc(pid: String) = obj(obj(doc, "p"), pid)

    fun favs(pid: String, kind: String): Set<String> =
        csv(doc.optJSONObject("p")?.optJSONObject(pid)?.optJSONObject("favs")?.optString(kind) ?: "")

    fun toggleFav(pid: String, kind: String, id: String): Change {
        val f = obj(profileDoc(pid), "favs")
        val set = csv(f.optString(kind)).toMutableSet()
        if (!set.add(id)) set.remove(id)
        f.put(kind, set.joinToString(","))
        f.put("ts", now())
        save()
        return Change("p/$pid/favs", JSONObject(f.toString()))
    }

    // ---------- Reprise de lecture (par profil) ----------

    fun progress(pid: String): List<Progress> {
        val pr = doc.optJSONObject("p")?.optJSONObject(pid)?.optJSONObject("prog") ?: return emptyList()
        return pr.keys().asSequence().mapNotNull { k -> pr.optJSONObject(k)?.let { Progress.fromJson(k, it) } }
            .sortedByDescending { it.ts }.toList()
    }

    fun progressFor(pid: String, key: String): Progress? =
        doc.optJSONObject("p")?.optJSONObject(pid)?.optJSONObject("prog")?.optJSONObject(key)?.let { Progress.fromJson(key, it) }

    fun saveProgress(pid: String, p: Progress): Change {
        val pr = obj(profileDoc(pid), "prog")
        val o = p.toJson()
        pr.put(p.key, o)
        // Garder la liste raisonnable : 200 éléments max.
        if (pr.length() > 200) {
            val oldest = pr.keys().asSequence().minByOrNull { pr.optJSONObject(it)?.optLong("ts") ?: 0 }
            if (oldest != null) pr.remove(oldest)
        }
        save()
        return Change("p/$pid/prog/${p.key}", JSONObject(o.toString()))
    }

    fun removeProgress(pid: String, key: String): Change {
        doc.optJSONObject("p")?.optJSONObject(pid)?.optJSONObject("prog")?.remove(key)
        save()
        return Change("p/$pid/prog/$key", null)
    }

    // ---------- Fusion avec la base de synchro ----------

    /** Fusionne la copie distante ; renvoie ce qui est plus récent ici et doit être envoyé. */
    fun merge(remote: JSONObject): List<Change> {
        var changed = false
        val push = ArrayList<Change>()

        fun lww(path: String, l: JSONObject?, r: JSONObject?, apply: (JSONObject) -> Unit) {
            val lt = l?.optLong("ts", -1) ?: -1
            val rt = r?.optLong("ts", -1) ?: -1
            if (r != null && rt > lt) {
                apply(JSONObject(r.toString())); changed = true
            } else if (l != null && lt > rt) {
                push.add(Change(path, JSONObject(l.toString())))
            }
        }

        lww("cats", doc.optJSONObject("cats"), remote.optJSONObject("cats")) { doc.put("cats", it) }
        lww("profiles", doc.optJSONObject("profiles"), remote.optJSONObject("profiles")) { doc.put("profiles", it) }

        val rp = remote.optJSONObject("p") ?: JSONObject()
        val lp = obj(doc, "p")
        val ids = (rp.keys().asSequence().toList() + lp.keys().asSequence().toList()).toSet()
        for (pid in ids) {
            val r = rp.optJSONObject(pid) ?: JSONObject()
            val l = obj(lp, pid)
            lww("p/$pid/favs", l.optJSONObject("favs"), r.optJSONObject("favs")) { l.put("favs", it) }
            val rpr = r.optJSONObject("prog") ?: JSONObject()
            val lpr = obj(l, "prog")
            val keys = (rpr.keys().asSequence().toList() + lpr.keys().asSequence().toList()).toSet()
            for (k in keys) {
                val ro = rpr.optJSONObject(k)
                val lo = lpr.optJSONObject(k)
                val rt = ro?.optLong("ts", -1) ?: -1
                val lt = lo?.optLong("ts", -1) ?: -1
                if (ro != null && rt > lt) { lpr.put(k, ro); changed = true }
                else if (lo != null && lt > rt) push.add(Change("p/$pid/prog/$k", JSONObject(lo.toString())))
            }
        }
        if (changed) save()
        return push
    }
}
