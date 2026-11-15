package com.lumo.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.lumo.tv.ui.LumoApp
import kotlinx.coroutines.launch

private const val PIP_ACTION = "com.lumo.tv.PIP_CONTROL"

class MainActivity : ComponentActivity() {
    lateinit var model: AppModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Crash.install(applicationContext)
        model = AppModel(applicationContext)
        handleIntent(intent)
        setContent { LumoApp(model) }
        // Boutons du mini-lecteur
        val f = android.content.IntentFilter(PIP_ACTION)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(pipReceiver, f, android.content.Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(pipReceiver, f)
        model.onPipStateChanged = {
            if (android.os.Build.VERSION.SDK_INT >= 26 && model.pip) try { setPictureInPictureParams(pipParams()) } catch (_: Exception) { }
        }
    }

    private val pipReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) {
            i.getStringExtra("a")?.let { model.pipEvents.tryEmit(it) }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(pipReceiver) } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun pipAction(code: Int, a: String, icon: Int, label: String): android.app.RemoteAction? {
        if (android.os.Build.VERSION.SDK_INT < 26) return null
        val pi = android.app.PendingIntent.getBroadcast(
            this, code, Intent(PIP_ACTION).setPackage(packageName).putExtra("a", a),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        return android.app.RemoteAction(android.graphics.drawable.Icon.createWithResource(this, icon), label, label, pi)
    }

    @android.annotation.TargetApi(26)
    private fun pipParams(): android.app.PictureInPictureParams {
        val actions = listOfNotNull(
            if (model.pipCanStep) pipAction(1, "prev", android.R.drawable.ic_media_previous, "Précédent") else null,
            if (model.pipPlaying) pipAction(2, "toggle", android.R.drawable.ic_media_pause, "Pause")
            else pipAction(2, "toggle", android.R.drawable.ic_media_play, "Lecture"),
            if (model.pipCanStep) pipAction(3, "next", android.R.drawable.ic_media_next, "Suivant") else null
        )
        return android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational(16, 9))
            .setActions(actions)
            .build()
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
                enterPictureInPictureMode(pipParams())
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
