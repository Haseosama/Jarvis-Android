package com.jarvis.android.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.android.timers.TimerService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val result = goAsync()
        try {
            executor.execute {
                try {
                    val appContext = context.applicationContext
                    val reminders = ReminderService.rescheduleAll(appContext)
                    Log.i("JarvisReminders", "Rappels reprogrammés après redémarrage : ${reminders.reprogrammed}, manqués : ${reminders.missed}, échecs : ${reminders.failed}.")
                    val timers = TimerService.rescheduleAll(appContext)
                    Log.i("JarvisTimers", "Minuteurs reprogrammés après redémarrage : ${timers.reprogrammed}, manqués : ${timers.missed}, échecs : ${timers.failed}.")
                } catch (_: Exception) {
                    Log.e("JarvisReminders", "Reprogrammation impossible après redémarrage.")
                } finally {
                    result.finish()
                }
            }
        } catch (_: RuntimeException) {
            result.finish()
        }
    }

    companion object {
        private val executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(8))
            .apply { allowCoreThreadTimeOut(true) }
    }
}
