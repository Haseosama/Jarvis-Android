package com.jarvis.android.timers

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getIntExtra(EXTRA_ID, 0)
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        if (id <= 0 || token.length != 36 || intent.data != identity(id, token)) return
        val result = goAsync()
        try {
            executor.execute {
                try {
                    TimerService.deliver(context.applicationContext, id, token)
                } catch (_: Exception) {
                    Log.e("JarvisTimers", "Minuteur non confirmé : stockage ou notification indisponible.")
                } finally {
                    result.finish()
                }
            }
        } catch (_: RuntimeException) {
            result.finish()
        }
    }

    companion object {
        const val EXTRA_ID = "id"
        private const val EXTRA_TOKEN = "timer_token"
        private const val ACTION_FIRE = "com.jarvis.android.timers.FIRE"
        private val executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(32))
            .apply { allowCoreThreadTimeOut(true) }

        private fun identity(id: Int, token: String): Uri = Uri.Builder()
            .scheme("jarvis-timer").authority("local").appendPath(id.toString()).appendPath(token).build()

        private fun intent(context: Context, id: Int, token: String) =
            Intent(context, TimerReceiver::class.java).apply {
                action = ACTION_FIRE
                data = identity(id, token)
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_TOKEN, token)
            }

        fun pendingIntent(context: Context, id: Int, token: String): PendingIntent =
            PendingIntent.getBroadcast(context, id, intent(context, id, token),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun existingPendingIntent(context: Context, id: Int, token: String): PendingIntent? =
            PendingIntent.getBroadcast(context, id, intent(context, id, token),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
    }
}
