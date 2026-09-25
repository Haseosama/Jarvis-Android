package com.jarvis.android.habits

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.android.JarvisApp
import com.jarvis.android.actions.ReminderReceiver
import com.jarvis.android.core.SpokenAlert
import com.jarvis.android.i18n.tr
import com.jarvis.android.reminders.ReminderService
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * One exact alarm at a time: the next slot of any habit. When it rings, the slot is announced (spoken, and a notification
 * with "Fait" / "Pas cette fois" buttons that only write the answer in the log), then the following slot is armed. Re-armed
 * after every change to the habits and after a reboot (BootReceiver).
 */
internal object HabitAlarms {
    private const val REQUEST_FIRE = 71_000
    private const val NOTIFICATION_TAG = "jarvis_habit"

    fun store(context: Context): HabitStore = (context.applicationContext as JarvisApp).container.habitStore

    /** False when Android only lets Jarvis ring "around" the time (Android 12+ without the "Alarms & reminders" access): up to an hour late. */
    fun exactAlarmsAllowed(context: Context): Boolean {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
    }

    /** Arms the alarm for the next slot, or cancels it when there is none. */
    fun reschedule(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val habits = store(context).load().habits
        val next = nextSlot(habits, now)
        val pending = fireIntent(context, next?.first?.id ?: 0, next?.second?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: 0L)
        manager.cancel(pending)
        if (next == null) return
        val at = next.second.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        try {
            if (exactAllowed) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    /** The same PendingIntent every time (one alarm), carrying the habit and slot it is for. */
    private fun fireIntent(context: Context, habitId: Int, slot: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_FIRE,
            Intent(context, HabitReceiver::class.java).setAction(HabitReceiver.ACTION_FIRE)
                .putExtra(HabitReceiver.EXTRA_HABIT, habitId).putExtra(HabitReceiver.EXTRA_SLOT, slot),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun answerIntent(context: Context, action: String, habitId: Int, slot: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, (habitId * 2 + if (action == HabitReceiver.ACTION_DONE) 0 else 1) + 72_000,
            Intent(context, HabitReceiver::class.java).setAction(action)
                .putExtra(HabitReceiver.EXTRA_HABIT, habitId).putExtra(HabitReceiver.EXTRA_SLOT, slot),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Announces [habitId]'s slot if it still exists and is still scheduled at that time, then arms the next one. */
    fun fire(context: Context, habitId: Int, slot: Long) {
        try {
            val habit = store(context).load().habits.firstOrNull { it.id == habitId } ?: return
            val zone = ZoneId.systemDefault()
            val slotTime = Instant.ofEpochMilli(slot).atZone(zone).toLocalDateTime()
            val stillScheduled = slotsBetween(habit, slotTime, slotTime.plusSeconds(1)).isNotEmpty()
            val tooLate = System.currentTimeMillis() - slot > LATE_HOURS * 3_600_000L
            if (!stillScheduled || tooLate) return
            val spoken = if (habit.medication) "C’est l’heure de votre médicament : ${habit.name}. Dites-moi quand c’est pris."
            else "C’est l’heure : ${habit.name}. Dites-moi quand c’est fait."
            if (ReminderService.notificationProblem(context) == null) { // also creates the reminders' channel, reused here
                val notification = NotificationCompat.Builder(context, ReminderReceiver.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(if (habit.medication) tr("Médicament") else tr("Habitude"))
                    .setContentText("${habit.name} — ${formatTime(slotTime.toLocalTime())}")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .addAction(0, tr("Fait"), answerIntent(context, HabitReceiver.ACTION_DONE, habitId, slot))
                    .addAction(0, tr("Pas cette fois"), answerIntent(context, HabitReceiver.ACTION_SKIP, habitId, slot))
                    .build()
                try {
                    NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG, habitId, notification)
                } catch (_: SecurityException) {
                }
            }
            SpokenAlert.announce(context, spoken)
        } finally {
            reschedule(context)
        }
    }

    fun answer(context: Context, habitId: Int, slot: Long, answer: HabitAnswer) {
        store(context).answer(habitId, slot, answer, System.currentTimeMillis())
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, habitId)
    }
}

class HabitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val habitId = intent.getIntExtra(EXTRA_HABIT, 0)
        val slot = intent.getLongExtra(EXTRA_SLOT, 0L)
        if (habitId <= 0 || slot <= 0L) return
        val app = context.applicationContext
        val result = goAsync()
        try {
            executor.execute {
                try {
                    when (intent.action) {
                        ACTION_FIRE -> HabitAlarms.fire(app, habitId, slot)
                        ACTION_DONE -> HabitAlarms.answer(app, habitId, slot, HabitAnswer.DONE)
                        ACTION_SKIP -> HabitAlarms.answer(app, habitId, slot, HabitAnswer.SKIPPED)
                    }
                } catch (_: Exception) {
                    Log.e("JarvisHabits", "Habitude non traitée.")
                } finally {
                    result.finish()
                }
            }
        } catch (_: RuntimeException) {
            result.finish()
        }
    }

    companion object {
        const val ACTION_FIRE = "com.jarvis.android.habits.FIRE"
        const val ACTION_DONE = "com.jarvis.android.habits.DONE"
        const val ACTION_SKIP = "com.jarvis.android.habits.SKIP"
        const val EXTRA_HABIT = "habit"
        const val EXTRA_SLOT = "slot"
        private val executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(16))
            .apply { allowCoreThreadTimeOut(true) }
    }
}
