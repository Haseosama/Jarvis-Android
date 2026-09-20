package com.jarvis.android.proactive

import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvis.android.JarvisApp
import com.jarvis.android.MainActivity
import com.jarvis.android.R
import com.jarvis.android.memory.BriefingInputs
import com.jarvis.android.memory.remindersToday
import com.jarvis.android.reminders.ReminderService
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "jarvis_proactive"
private const val CHANNEL_ID = "jarvis_proactive"
private const val PREFS = "jarvis_proactive_state"

/** Runs every 15 minutes at most; checks the phone locally (no network) and posts notifications. */
class ProactiveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as JarvisApp).container
        if (!container.configStore.snapshotProactiveEnabled()) return Result.success()
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = ProactiveState(
            batteryAlerted = prefs.getBoolean("battery", false),
            lastStorageDay = prefs.getString("storage", "").orEmpty(),
            lastMorningDay = prefs.getString("morning", "").orEmpty(),
        )
        val morning = morningNotificationText(
            BriefingInputs(
                today = LocalDate.now(),
                lastBriefingDate = "",
                lastSession = container.memoryManager.peekLastSession(),
                reminders = remindersToday(ReminderService.list(applicationContext), System.currentTimeMillis(), ZoneId.systemDefault()),
            )
        )
        val (alerts, next) = evaluateProactive(readStatus(), state, LocalDateTime.now(), morning)
        alerts.forEach { notify(it) }
        prefs.edit()
            .putBoolean("battery", next.batteryAlerted)
            .putString("storage", next.lastStorageDay)
            .putString("morning", next.lastMorningDay)
            .apply()
        return Result.success()
    }

    private fun readStatus(): DeviceStatus {
        val battery = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val stat = StatFs(Environment.getDataDirectory().path)
        return DeviceStatus(
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else 100,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            freeBytes = stat.availableBytes,
            totalBytes = stat.totalBytes,
        )
    }

    private fun notify(alert: ProactiveAlert) {
        val (id, title, text) = when (alert) {
            is ProactiveAlert.Battery -> Triple(7101, tr("Batterie faible"), trf("Il reste {0} % : pensez à brancher le téléphone.", alert.percent))
            is ProactiveAlert.Storage -> Triple(7102, tr("Stockage presque plein"), trf("Il reste environ {0} Mo d’espace libre.", alert.freeMegabytes))
            is ProactiveAlert.Morning -> Triple(7103, tr("Jarvis : briefing du matin"), alert.text)
        }
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, tr("Vérifications Jarvis"), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // Notifications not allowed: nothing to show.
        }
    }
}

internal object ProactiveScheduler {
    fun apply(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (enabled) {
            work.enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<ProactiveWorker>(15, TimeUnit.MINUTES).build(),
            )
        } else {
            work.cancelUniqueWork(WORK_NAME)
        }
    }
}
