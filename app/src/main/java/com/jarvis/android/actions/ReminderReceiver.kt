package com.jarvis.android.actions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.jarvis.android.R

/** Fires a notification for a scheduled reminder — the target of the AlarmManager alarm set by [ReminderTool]. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: "Reminder"
        val id = intent.getIntExtra(EXTRA_ID, 0)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Jarvis reminders", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Jarvis reminder")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(id, notification)
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_ID = "id"
        const val CHANNEL_ID = "jarvis_reminders"

        fun pendingIntent(context: Context, id: Int, text: String): PendingIntent {
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_ID, id)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, id, intent, flags)
        }
    }
}
