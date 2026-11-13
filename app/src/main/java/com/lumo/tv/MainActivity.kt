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
        model = AppModel(applicationContext)
        handleIntent(intent)
        setContent { LumoApp(model) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** QR code de la TV scanné avec le téléphone : lumo://pair?code=XXXXXX */
    private fun handleIntent(i: Intent?) {
        val data = i?.data ?: return
        if (data.scheme != "lumo" || data.host != "pair") return
        val code = data.getQueryParameter("code") ?: return
        lifecycleScope.launch {
            val err = model.sendPairing(code)
            model.showToast(err ?: "TV connectée ✓")
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { model.syncNow() }
    }
}
