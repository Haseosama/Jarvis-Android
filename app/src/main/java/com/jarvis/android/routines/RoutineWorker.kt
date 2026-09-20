package com.jarvis.android.routines

import com.jarvis.android.i18n.tr
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvis.android.JarvisApp
import com.jarvis.android.MainActivity
import com.jarvis.android.R
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

private const val ROUTINE_WORK = "jarvis_routines"
private const val ROUTINE_CHANNEL = "jarvis_routines"

/**
 * Checks every 15 minutes (the minimum WorkManager allows, and Android may delay it further in battery saving)
 * whether a routine is due, runs it as a background task and posts the result as a notification.
 */
class RoutineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as JarvisApp).container
        val now = LocalDateTime.now()
        for (routine in container.routines.list().filter { isDue(it, now) }) {
            // Marked first: a routine that crashes must not repeat every 15 minutes.
            container.routines.markRun(routine.id, now.toLocalDate().toString())
            val result = container.agent.runOnce(routine.task)
            notify(routine, result)
        }
        return Result.success()
    }

    private fun notify(routine: Routine, text: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(ROUTINE_CHANNEL, tr("Routines Jarvis"), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, ROUTINE_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Jarvis : ${routine.task.take(40)}")
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(7200 + routine.id, notification)
        } catch (_: SecurityException) {
        }
    }
}

internal object RoutineScheduler {
    fun ensureScheduled(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ROUTINE_WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RoutineWorker>(15, TimeUnit.MINUTES).build(),
        )
    }
}
