package com.lumo.tv

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Garde la trace d'un plantage pour l'envoyer au prochain démarrage. */
object Crash {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val rt = Runtime.getRuntime()
                file(app).writeText(
                    "Lumo ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                        "Mémoire max ${rt.maxMemory() / 1048576} Mo · utilisée ${(rt.totalMemory() - rt.freeMemory()) / 1048576} Mo · fil ${t.name}\n\n" +
                        sw.toString().take(12000)
                )
            } catch (_: Throwable) { }
            previous?.uncaughtException(t, e)
        }
    }

    fun pending(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.readText()
    fun clear(ctx: Context) { file(ctx).delete() }
}
