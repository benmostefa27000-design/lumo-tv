package com.lumo.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.lumo.tv.ui.LumoApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    lateinit var model: AppModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Crash.install(applicationContext)
        model = AppModel(applicationContext)
        handleIntent(intent)
        setContent { LumoApp(model) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** QR code de la TV scanné avec le téléphone : lumo://pair?code=XXXXXX, ou rappel touché. */
    private fun handleIntent(i: Intent?) {
        if (i?.action == "com.lumo.tv.PLAY_LIVE") {
            val id = i.getStringExtra("stream") ?: return
            lifecycleScope.launch {
                // Attendre que les chaînes soient chargées
                repeat(40) { if (model.live.isEmpty()) kotlinx.coroutines.delay(500) }
                model.playLiveById(id)
            }
            return
        }
        val data = i?.data ?: return
        if (data.scheme != "lumo" || data.host != "pair") return
        val code = data.getQueryParameter("code") ?: return
        lifecycleScope.launch {
            val err = model.sendPairing(code)
            model.showToast(err ?: "TV connectée ✓")
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Mini-lecteur quand on quitte l'appli pendant une vidéo (téléphone)
        if (!model.isTv && model.current is Screen.Player && android.os.Build.VERSION.SDK_INT >= 26) {
            try {
                enterPictureInPictureMode(
                    android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build()
                )
            } catch (_: Exception) { }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        model.pip = isInPictureInPictureMode
    }

    override fun onStart() {
        super.onStart()
        model.appStopped = false
    }

    override fun onStop() {
        super.onStop()
        model.appStopped = true
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { model.syncNow() }
    }
}
