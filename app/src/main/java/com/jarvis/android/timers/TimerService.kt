package com.jarvis.android.timers

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvis.android.core.SpokenAlert
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

internal object TimerService {
    private val lock = Any()

    fun create(context: Context, durationSeconds: Long, label: String): TimerRecord = synchronized(lock) {
        require(durationSeconds in 1..TimerDurations.MAX_SECONDS) { "La durée doit être comprise entre 1 seconde et 24 heures." }
        require(label.length <= TimerStore.MAX_LABEL_CHARS) { "Le libellé est limité à 200 caractères." }
        val now = System.currentTimeMillis()
        val store = store(context)
        val snapshot = store.load()
        require(snapshot.records.count { it.status == TimerStatus.SCHEDULED } < TimerStore.MAX_RECORDS) {
            "La limite de 50 minuteurs actifs est atteinte. Annulez des minuteurs pour libérer de la place."
        }
        require(snapshot.nextId <= Int.MAX_VALUE) { "Les identifiants de minuteur sont épuisés." }
        val cleanLabel = label.trim().ifEmpty { "Minuteur" }
        val record = TimerRecord(snapshot.nextId.toInt(), UUID.randomUUID().toString(), cleanLabel,
            durationSeconds, now, now + durationSeconds * 1000)
        val approximate = programAlarm(context, record)
        val scheduled = record.copy(approximate = approximate)
        store.save(snapshot.copy(nextId = snapshot.nextId + 1,
            records = snapshot.records + scheduled))
        scheduled
    }

    fun list(context: Context): List<TimerRecord> = synchronized(lock) {
        store(context).load().records
            .filter { it.status == TimerStatus.SCHEDULED }
            .sortedBy { it.triggerAt }
    }

    fun cancel(context: Context, id: Int): TimerRecord = synchronized(lock) {
        val store = store(context)
        val snapshot = store.load()
        val record = snapshot.records.find { it.id == id && it.status == TimerStatus.SCHEDULED }
            ?: throw IllegalArgumentException("Aucun minuteur actif ne porte l’identifiant $id.")
        store.save(snapshot.copy(records = snapshot.records.map {
            if (it.id == id) it.copy(status = TimerStatus.CANCELLED) else it
        }))
        discardAlarm(context, record)
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, id)
        } catch (_: RuntimeException) {
        }
        record
    }

    fun deliver(context: Context, id: Int, token: String) = synchronized(lock) {
        val store = store(context)
        val snapshot = store.load()
        val record = snapshot.records.find { it.id == id && it.token == token } ?: return@synchronized
        if (record.status != TimerStatus.SCHEDULED) return@synchronized
        fun saveStatus(status: TimerStatus) {
            store.save(snapshot.copy(records = snapshot.records.map {
                if (it.id == id) it.copy(status = status) else it
            }))
        }
        val problem = try {
            notificationProblem(context)
        } catch (_: RuntimeException) {
            "Notifications indisponibles."
        }
        if (problem != null) return@synchronized
        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Minuteur terminé")
                .setContentText(record.label)
                .setStyle(NotificationCompat.BigTextStyle().bigText(record.label))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG, id, notification)
            SpokenAlert.announce(context, SpokenAlert.timerSpokenText(record.label))
            saveStatus(TimerStatus.FIRED)
        } catch (_: SecurityException) {
        } catch (_: RuntimeException) {
        }
    }

    fun rescheduleAll(context: Context): TimerRescheduleReport = synchronized(lock) {
        val store = store(context)
        val snapshot = store.load()
        val plan = planReschedule(snapshot.records, System.currentTimeMillis())
        var failed = 0
        val updated = snapshot.records.map { record ->
            when {
                record in plan.reprogram -> {
                    try {
                        programAlarm(context, record)
                        record
                    } catch (_: Exception) {
                        failed++
                        record.copy(status = TimerStatus.CANCELLED)
                    }
                }
                record in plan.miss -> record.copy(status = TimerStatus.CANCELLED)
                else -> record
            }
        }
        store.save(snapshot.copy(records = updated))
        TimerRescheduleReport(plan.reprogram.size - failed, plan.miss.size, failed)
    }

    internal fun planReschedule(records: List<TimerRecord>, now: Long): TimerReschedulePlan {
        val active = records.filter { it.status == TimerStatus.SCHEDULED }
        return TimerReschedulePlan(
            reprogram = active.filter { it.triggerAt > now },
            miss = active.filter { it.triggerAt <= now },
        )
    }

    private fun programAlarm(context: Context, record: TimerRecord): Boolean {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: throw IllegalStateException("Le service d’alarme est indisponible.")
        val pending = TimerReceiver.pendingIntent(context, record.id, record.token)
        var approximate = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()
        if (!approximate) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, record.triggerAt, pending)
            } catch (_: SecurityException) {
                approximate = true
            }
        }
        if (approximate) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, record.triggerAt, pending)
        }
        return approximate
    }

    fun notificationProblem(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return "Permission de notification refusée. Autorisez les notifications de Jarvis dans les réglages avant de démarrer un minuteur."
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return "Les notifications de Jarvis sont désactivées dans les réglages."
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return "Le service de notification est indisponible."
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            "Minuteurs Jarvis", NotificationManager.IMPORTANCE_HIGH))
        val channel = manager.getNotificationChannel(CHANNEL_ID)
            ?: return "Le canal des minuteurs est indisponible."
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) {
            return "Le canal de notification des minuteurs est désactivé dans les réglages."
        }
        return null
    }

    private fun discardAlarm(context: Context, record: TimerRecord) {
        try {
            val pending = TimerReceiver.existingPendingIntent(context, record.id, record.token) ?: return
            try {
                (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(pending)
            } finally {
                pending.cancel()
            }
        } catch (_: RuntimeException) {
        }
    }

    private fun store(context: Context): TimerStore {
        val file = AtomicFile(File(context.noBackupFilesDir, "jarvis_timers_v1.json"))
        return TimerStore(object : TimerStorage {
            override fun read(): String? {
                val input = try {
                    file.openRead()
                } catch (e: FileNotFoundException) {
                    if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw e
                    return null
                }
                return input.bufferedReader(Charsets.UTF_8).use { reader ->
                    val result = StringBuilder()
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        if (result.length + count > TimerStore.MAX_STORAGE_CHARS) throw IOException("Stockage des minuteurs trop volumineux.")
                        result.append(buffer, 0, count)
                    }
                    result.toString()
                }
            }

            override fun write(value: String) {
                val output = file.startWrite()
                try {
                    output.write(value.toByteArray(Charsets.UTF_8))
                    file.finishWrite(output)
                } catch (e: Exception) {
                    file.failWrite(output)
                    throw e
                }
            }
        })
    }

    const val CHANNEL_ID = "jarvis_timers"
    private const val NOTIFICATION_TAG = "jarvis_timers"
}

internal data class TimerReschedulePlan(
    val reprogram: List<TimerRecord>,
    val miss: List<TimerRecord>,
)

internal data class TimerRescheduleReport(
    val reprogrammed: Int,
    val missed: Int,
    val failed: Int,
)
