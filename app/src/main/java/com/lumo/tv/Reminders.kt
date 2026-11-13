package com.lumo.tv

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Rappels d'émissions : une notification 5 minutes avant le début. */
object Reminders {
    private const val CHANNEL = "rappels"

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

    private fun pending(context: Context, id: Int, intent: Intent?) =
        PendingIntent.getBroadcast(context, id, intent ?: Intent(context, ReminderReceiver::class.java), flags())

    fun schedule(context: Context, id: Int, streamId: String, channel: String, title: String, start: Long) {
        val i = Intent(context, ReminderReceiver::class.java)
            .putExtra("id", id).putExtra("stream", streamId).putExtra("channel", channel).putExtra("title", title).putExtra("start", start)
        val at = (start - 5 * 60_000).coerceAtLeast(System.currentTimeMillis() + 5_000)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, id, i))
        else am.set(AlarmManager.RTC_WAKEUP, at, pending(context, id, i))
    }

    fun cancel(context: Context, id: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(context, id, null))
    }

    fun notify(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rappels d'émissions", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = Intent(context, MainActivity::class.java)
            .setAction("com.lumo.tv.PLAY_LIVE")
            .putExtra("stream", intent.getStringExtra("stream"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val id = intent.getIntExtra("id", 0)
        val pi = PendingIntent.getActivity(context, id, open, flags())
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Ça commence dans 5 min")
            .setContentText("${intent.getStringExtra("title")} · ${intent.getStringExtra("channel")}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try { NotificationManagerCompat.from(context).notify(id, n) } catch (_: SecurityException) { }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Reminders.notify(context, intent)
}
