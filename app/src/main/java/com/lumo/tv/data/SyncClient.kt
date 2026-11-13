package com.lumo.tv.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** Accès à la base de synchro (Firebase Realtime Database, via son API REST). */
class SyncClient(private val base: String, private val http: OkHttpClient) {

    val enabled: Boolean get() = base.isNotBlank()

    private val json = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String) = "$base/$path.json"

    private fun exec(req: Request): String {
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Synchro HTTP ${r.code}")
            return r.body?.string() ?: ""
        }
    }

    fun getObject(path: String): JSONObject? {
        val s = exec(Request.Builder().url(url(path)).get().build()).trim()
        if (s.isEmpty() || s == "null" || !s.startsWith("{")) return null
        return JSONObject(s)
    }

    fun put(path: String, value: JSONObject) {
        exec(Request.Builder().url(url(path)).put(value.toString().toRequestBody(json)).build())
    }

    fun delete(path: String) {
        exec(Request.Builder().url(url(path)).delete().build())
    }

    fun apply(accountKey: String, c: Change) {
        val p = "lumo/$accountKey/${c.path}"
        if (c.value == null) delete(p) else put(p, c.value)
    }

    // ---------- Connexion d'une TV par code ----------

    fun publishPairing(code: String, acc: Account) {
        put("pair/$code", acc.toJson().put("ts", System.currentTimeMillis()))
    }

    fun takePairing(code: String): Account? {
        val o = getObject("pair/$code") ?: return null
        val ts = o.optLong("ts")
        try { delete("pair/$code") } catch (_: Exception) { }
        if (System.currentTimeMillis() - ts > 15 * 60 * 1000) return null
        return Account.fromJson(o)
    }
}
