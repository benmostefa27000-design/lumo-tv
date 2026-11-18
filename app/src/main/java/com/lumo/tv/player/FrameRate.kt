package com.lumo.tv.player

import android.app.Activity
import android.os.Build
import android.view.Display

/**
 * Fréquence d'image adaptée : la TV passe en 50 Hz pour les chaînes françaises, en 23,976/24 Hz pour les films…
 * L'image est plus fluide (plus de petites saccades). Seulement si la TV propose ce mode, à la même résolution.
 */
object FrameRate {
    private var changed = false

    /** fps : images/seconde de la vidéo (≤ 0 si inconnu). */
    fun match(activity: Activity, fps: Float, live: Boolean) {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            val display = activity.window.decorView.display ?: return
            val cur = display.mode
            // Fréquence voulue : celle de la vidéo, ou 50 Hz pour le direct si inconnue (TV française)
            val want = when {
                fps in 23.0f..24.5f -> fps
                fps in 24.5f..25.5f -> 50f
                fps in 29.0f..30.5f -> 59.94f.takeIf { fps < 29.99f } ?: 60f
                fps in 49.0f..50.5f -> 50f
                fps in 59.0f..60.5f -> fps
                live -> 50f
                else -> return
            }
            val same = display.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            fun ok(m: Display.Mode, r: Float) = kotlin.math.abs(m.refreshRate - r) < 0.05f
            val target = same.firstOrNull { ok(it, want) }
                ?: same.firstOrNull { ok(it, want * 2) }
                ?: (if (want in 23f..24.1f) same.firstOrNull { ok(it, 24f) || ok(it, 23.976f) } else null)
                ?: return
            if (target.modeId == cur.modeId) return
            val lp = activity.window.attributes
            lp.preferredDisplayModeId = target.modeId
            activity.window.attributes = lp
            changed = true
        } catch (_: Throwable) { }
    }

    /** En quittant le lecteur : retour au mode normal de la TV. */
    fun reset(activity: Activity) {
        if (!changed || Build.VERSION.SDK_INT < 23) return
        try {
            val lp = activity.window.attributes
            lp.preferredDisplayModeId = 0
            activity.window.attributes = lp
        } catch (_: Throwable) { }
        changed = false
    }
}
