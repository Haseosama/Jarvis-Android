package com.jarvis.android.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.android.reminders.ReminderAlarm
import com.jarvis.android.reminders.ReminderService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Fires a reminder when its alarm goes off (the alarm itself is set in reminders/ReminderAlarm.kt). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val (id, token) = ReminderAlarm.parse(intent) ?: return
        val result = goAsync()
        try {
            executor.execute {
                try {
                    ReminderService.deliver(context.applicationContext, id, token)
                } catch (_: Exception) {
                    Log.e("JarvisReminders", "Rappel non confirmé : stockage ou notification indisponible.")
                } finally {
                    result.finish()
                }
            }
        } catch (_: RuntimeException) {
            result.finish()
        }
    }

    companion object {
        private val executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(32))
            .apply { allowCoreThreadTimeOut(true) }
    }
}
