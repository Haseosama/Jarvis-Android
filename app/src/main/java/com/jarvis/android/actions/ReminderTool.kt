package com.jarvis.android.actions

import android.app.AlarmManager
import android.content.Context
import com.jarvis.android.JarvisContainer
import com.jarvis.android.core.UndoEntry
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.random.Random

/**
 * Scheduled reminders — Android port of `actions/reminder.py`. Desktop schedules
 * through the OS task scheduler (Task Scheduler / launchd / systemd); the
 * Android equivalent is an exact [AlarmManager] alarm that fires a notification.
 */
object ReminderTool : Tool {
    override val name = "reminder"
    override val description =
        "Schedule a reminder notification. Compute the exact date/time yourself from the " +
            "current date/time given in context and the user's request (e.g. 'in 20 minutes', 'tomorrow at 9am')."
    override val parameters = objectSchema(required = listOf("text", "when_iso")) {
        string("text", "What to remind the user about.")
        string("when_iso", "Exact local date-time in 'yyyy-MM-dd HH:mm' format.")
    }

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val text = args.stringArg("text")
        val whenStr = args.stringArg("when_iso")
        if (text.isBlank() || whenStr.isBlank()) return "I need both what to remind about and when."

        val triggerAt = try {
            fmt.parse(whenStr)?.time ?: return "Could not understand the time '$whenStr'."
        } catch (e: Exception) {
            return "Could not understand the time '$whenStr' — use 'yyyy-MM-dd HH:mm'."
        }
        if (triggerAt <= System.currentTimeMillis()) return "That time is in the past."

        val alarmManager = ctx.appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val id = Random.nextInt(1, Int.MAX_VALUE)
        val pending = ReminderReceiver.pendingIntent(ctx.appContext, id, text)

        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } catch (e: SecurityException) {
            return "Missing permission to schedule exact alarms — enable it for Jarvis in system settings."
        }

        ctx.undoManager.push(UndoEntry("reminder: $text") {
            alarmManager.cancel(pending)
            "Cancelled reminder: $text"
        })

        return "Reminder set for $whenStr: $text"
    }
}
