package com.jarvis.android.reminders

import com.jarvis.android.i18n.tr
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
import com.jarvis.android.actions.ReminderReceiver
import com.jarvis.android.core.SpokenAlert
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal object ReminderService {
    private val lock = Any()
    const val LIMITATION = "Les rappels sont reprogrammés après redémarrage, mais pas après un arrêt forcé : rouvrez l’application dans ce cas."

    fun create(context: Context, text: String, whenIso: String): ReminderRecord = synchronized(lock) {
        require(text.isNotBlank()) { "Le texte du rappel est obligatoire." }
        require(text.length <= ReminderStore.MAX_TEXT_CHARS) { "Le texte du rappel est limité à 1000 caractères." }
        val zone = ZoneId.systemDefault()
        val triggerAt = ReminderDates.parse(whenIso, zone, Instant.now())
        val store = store(context)
        val snapshot = store.load()
        require(snapshot.records.size < ReminderStore.MAX_RECORDS) {
            "La limite de 200 rappels est atteinte. Annulez des rappels par identifiant pour libérer de la place."
        }
        require(snapshot.nextId <= Int.MAX_VALUE) { "Les identifiants de rappel sont épuisés." }
        val record = ReminderRecord(snapshot.nextId.toInt(), UUID.randomUUID().toString(), text, whenIso,
            zone.id, triggerAt)
        schedule(context, store, snapshot.copy(nextId = snapshot.nextId + 1), record)
    }

    fun list(context: Context): List<ReminderRecord> = synchronized(lock) {
        store(context).load().records.sortedWith(compareBy<ReminderRecord> { it.triggerAt }.thenBy { it.id })
    }

    fun cancel(context: Context, id: Int, expectedToken: String? = null): ReminderRecord = synchronized(lock) {
        val store = store(context)
        val snapshot = store.load()
        val record = snapshot.records.find { it.id == id }
            ?: throw IllegalArgumentException("Aucun rappel ne porte l’identifiant $id.")
        require(expectedToken == null || record.token == expectedToken) {
            "Ce rappel a changé depuis sa création ; annulation automatique refusée."
        }
        store.save(snapshot.copy(records = snapshot.records.filterNot { it.id == id }))
        discardAlarm(context, record)
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, id)
        } catch (_: RuntimeException) {
        }
        record
    }

    fun restore(context: Context, record: ReminderRecord): ReminderRecord = synchronized(lock) {
        require(record.triggerAt > System.currentTimeMillis()) { "Le rappel ne peut plus être restauré : sa date est passée." }
        val store = store(context)
        val snapshot = store.load()
        require(snapshot.records.none { it.id == record.id }) { "Cet identifiant de rappel est déjà utilisé." }
        require(snapshot.records.size < ReminderStore.MAX_RECORDS) { "La limite de 200 rappels est atteinte." }
        schedule(context, store, snapshot, record.copy(token = UUID.randomUUID().toString(), status = ReminderStatus.PREPARING))
    }

    fun rescheduleAll(context: Context): RescheduleReport = synchronized(lock) {
        val store = store(context)
        val snapshot = store.load()
        val plan = planReschedule(snapshot.records, System.currentTimeMillis())
        var reprogrammed = 0
        var failed = 0
        val updated = snapshot.records.map { record ->
            when {
                record in plan.reprogram -> {
                    try {
                        reprogrammed++
                        record.copy(approximate = programAlarm(context, record), status = ReminderStatus.SCHEDULED)
                    } catch (_: Exception) {
                        reprogrammed--
                        failed++
                        record.copy(status = ReminderStatus.FAILED)
                    }
                }
                record in plan.miss -> record.copy(status = ReminderStatus.FAILED)
                else -> record
            }
        }
        store.save(snapshot.copy(records = updated))
        RescheduleReport(reprogrammed, plan.miss.size, failed)
    }

    internal fun planReschedule(records: List<ReminderRecord>, now: Long): ReschedulePlan {
        val active = records.filter {
            it.status == ReminderStatus.SCHEDULED || it.status == ReminderStatus.PREPARING
        }
        return ReschedulePlan(
            reprogram = active.filter { it.triggerAt > now },
            miss = active.filter { it.triggerAt <= now },
        )
    }

    private fun schedule(context: Context, store: ReminderStore, snapshot: ReminderSnapshot,
                         record: ReminderRecord): ReminderRecord {
        notificationProblem(context)?.let { throw IllegalStateException(it) }
        require(record.triggerAt > System.currentTimeMillis()) { "La date du rappel est déjà passée." }
        val preparing = snapshot.copy(records = snapshot.records + record)
        store.save(preparing)
        try {
            val approximate = programAlarm(context, record)
            val scheduled = record.copy(approximate = approximate, status = ReminderStatus.SCHEDULED)
            store.save(preparing.copy(records = preparing.records.map { if (it.id == record.id) scheduled else it }))
            return scheduled
        } catch (e: Exception) {
            discardAlarm(context, record)
            try {
                store.save(preparing.copy(records = preparing.records.map {
                    if (it.id == record.id) it.copy(status = ReminderStatus.FAILED) else it
                }))
            } catch (_: Exception) {
            }
            throw IOException("Programmation du rappel ${record.id} non confirmée. Consultez la liste et annulez cet identifiant avant de réessayer.", e)
        }
    }

    private fun programAlarm(context: Context, record: ReminderRecord): Boolean {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: throw IllegalStateException("Le service d’alarme est indisponible.")
        val pending = ReminderReceiver.pendingIntent(context, record.id, record.token)
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

    fun deliver(context: Context, id: Int, token: String) = synchronized(lock) {
        val store = store(context)
        val snapshot = store.load()
        val record = snapshot.records.find { it.id == id && it.token == token } ?: return@synchronized
        if (record.status != ReminderStatus.SCHEDULED || record.triggerAt > System.currentTimeMillis()) return@synchronized
        fun saveStatus(status: ReminderStatus) {
            store.save(snapshot.copy(records = snapshot.records.map {
                if (it.id == id) it.copy(status = status) else it
            }))
        }
        val problem = try {
            notificationProblem(context)
        } catch (_: RuntimeException) {
            "Notifications indisponibles."
        }
        if (problem != null) {
            saveStatus(ReminderStatus.BLOCKED)
            return@synchronized
        }
        saveStatus(ReminderStatus.DELIVERING)
        val status = try {
            val notification = NotificationCompat.Builder(context, ReminderReceiver.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(tr("Rappel Jarvis"))
                .setContentText(record.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(record.text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG, id, notification)
            SpokenAlert.announce(context, SpokenAlert.reminderSpokenText(record.text))
            ReminderStatus.DELIVERED
        } catch (_: SecurityException) {
            ReminderStatus.BLOCKED
        } catch (_: RuntimeException) {
            ReminderStatus.FAILED
        }
        saveStatus(status)
    }

    fun notificationProblem(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return "Permission de notification refusée. Autorisez les notifications de Jarvis dans les réglages avant de créer un rappel."
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return "Les notifications de Jarvis sont désactivées dans les réglages."
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return "Le service de notification est indisponible."
        manager.createNotificationChannel(NotificationChannel(ReminderReceiver.CHANNEL_ID,
            tr("Rappels Jarvis"), NotificationManager.IMPORTANCE_HIGH))
        val channel = manager.getNotificationChannel(ReminderReceiver.CHANNEL_ID)
            ?: return "Le canal des rappels est indisponible."
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) {
            return "Le canal de notification des rappels est désactivé dans les réglages."
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && channel.group != null &&
            manager.getNotificationChannelGroup(channel.group)?.isBlocked == true) {
            return "Le groupe de notification des rappels est désactivé dans les réglages."
        }
        return null
    }

    private fun discardAlarm(context: Context, record: ReminderRecord) {
        try {
            val pending = ReminderReceiver.existingPendingIntent(context, record.id, record.token) ?: return
            try {
                (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(pending)
            } finally {
                pending.cancel()
            }
        } catch (_: RuntimeException) {
        }
    }

    private fun store(context: Context): ReminderStore {
        val file = AtomicFile(File(context.noBackupFilesDir, "jarvis_reminders_v1.json"))
        return ReminderStore(object : ReminderStorage {
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
                        if (result.length + count > ReminderStore.MAX_STORAGE_CHARS) throw IOException("Stockage des rappels trop volumineux.")
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

    private const val NOTIFICATION_TAG = "jarvis_reminders"
}

internal data class ReschedulePlan(
    val reprogram: List<ReminderRecord>,
    val miss: List<ReminderRecord>,
)

internal data class RescheduleReport(
    val reprogrammed: Int,
    val missed: Int,
    val failed: Int,
)
