package com.jarvis.android.quiet

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.android.JarvisApp
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * "Je suis en réunion jusqu'à 15 h": Android's Do Not Disturb until then, letting through only the starred contacts
 * (calls and messages), repeated calls and alarms; the user's own Do Not Disturb settings are put back at the end,
 * unless they changed them meanwhile. At the end, a notification says what arrived meanwhile. Optionally, and only if
 * the user switched it on, each person who writes gets one "je suis en réunion jusqu'à 15 h" answer.
 */

internal const val DEFAULT_QUIET_REPLY = "Je ne suis pas disponible jusqu'à {heure}, je te recontacte après. (Réponse automatique)"
internal const val QUIET_MIN_MINUTES = 5
internal const val QUIET_MAX_MINUTES = 12 * 60

@Serializable
internal data class SavedPolicy(val categories: Int, val callSenders: Int, val messageSenders: Int, val suppressed: Int, val conversations: Int = -1)

@Serializable
internal data class QuietState(
    val startedAt: Long,
    val untilMs: Long,
    val reason: String = "",
    val previousFilter: Int,
    val previousPolicy: SavedPolicy? = null,
    val ourFilter: Int = NotificationManager.INTERRUPTION_FILTER_PRIORITY,
)

@Serializable
internal data class QuietData(
    val state: QuietState? = null,
    val autoReply: Boolean = false,
    val replyText: String = DEFAULT_QUIET_REPLY,
)

internal class QuietStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun load(): QuietData = try {
        if (file.exists()) json.decodeFromString<QuietData>(file.readText()) else QuietData()
    } catch (_: Exception) {
        QuietData()
    }

    @Synchronized
    fun update(change: (QuietData) -> QuietData): QuietData {
        val next = change(load())
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(next))
        } catch (_: Exception) {
        }
        return next
    }
}

/** "15 h", "15 h 30". */
internal fun clockWords(ms: Long, zone: ZoneId): String {
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    return if (t.minute == 0) "${t.hour} h" else "${t.hour} h ${t.minute.toString().padStart(2, '0')}"
}

/** The next [hour]:[minute] after [now] (tomorrow when it has passed today). */
internal fun nextClock(hour: Int, minute: Int, now: LocalDateTime, zone: ZoneId): Long? {
    if (hour !in 0..23 || minute !in 0..59) return null
    var t = now.toLocalDate().atTime(hour, minute)
    if (!t.isAfter(now)) t = t.plusDays(1)
    return t.atZone(zone).toInstant().toEpochMilli()
}

/** The end asked for, from "HH:MM" or a number of minutes; null when neither is usable. Capped to 5 minutes – 12 hours. */
internal fun quietUntil(until: String, minutes: Int, now: LocalDateTime, zone: ZoneId): Long? {
    val nowMs = now.atZone(zone).toInstant().toEpochMilli()
    val end = Regex("^(\\d{1,2})\\s*(?:[:hH]\\s*(\\d{2})?)?$").find(until.trim())?.let { m ->
        nextClock(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { "0" }.toInt(), now, zone)
    } ?: minutes.takeIf { it > 0 }?.let { nowMs + it * 60_000L } ?: return null
    return end.coerceIn(nowMs + QUIET_MIN_MINUTES * 60_000L, nowMs + QUIET_MAX_MINUTES * 60_000L)
}

internal sealed interface QuietStart {
    data class Started(val untilMs: Long) : QuietStart
    data object NeedsAccess : QuietStart
}

internal object QuietMode {
    private const val CHANNEL = "jarvis_quiet"
    private const val SUMMARY_CHANNEL = "jarvis_quiet_summary"
    private const val ONGOING_ID = 7_701
    private const val SUMMARY_ID = 7_702

    fun store(context: Context) = (context.applicationContext as JarvisApp).container.quietStore

    fun hasAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun active(context: Context): QuietState? = store(context).load().state?.takeIf { it.untilMs > System.currentTimeMillis() }

    @Synchronized
    fun start(context: Context, untilMs: Long, reason: String): QuietStart {
        val app = context.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return QuietStart.NeedsAccess
        val old = store(app).load().state
        // Extending a quiet time already on keeps the settings saved when it began.
        val previousFilter = old?.previousFilter ?: nm.currentInterruptionFilter
        val previousPolicy = old?.previousPolicy ?: nm.notificationPolicy.let {
            SavedPolicy(it.priorityCategories, it.priorityCallSenders, it.priorityMessageSenders, it.suppressedVisualEffects,
                if (Build.VERSION.SDK_INT >= 30) it.priorityConversationSenders else -1)
        }
        val categories = NotificationManager.Policy.PRIORITY_CATEGORY_CALLS or NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS or
            NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES or NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS
        val starred = NotificationManager.Policy.PRIORITY_SENDERS_STARRED
        nm.notificationPolicy = if (Build.VERSION.SDK_INT >= 30) {
            NotificationManager.Policy(categories, starred, starred, previousPolicy.suppressed, NotificationManager.Policy.CONVERSATION_SENDERS_IMPORTANT)
        } else {
            NotificationManager.Policy(categories, starred, starred, previousPolicy.suppressed)
        }
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        store(app).update {
            it.copy(state = QuietState(old?.startedAt ?: System.currentTimeMillis(), untilMs, reason.take(80), previousFilter, previousPolicy))
        }
        scheduleEnd(app, untilMs)
        showOngoing(app, untilMs)
        return QuietStart.Started(untilMs)
    }

    /** Ends the quiet time: puts the user's settings back (unless they changed them meanwhile) and says what arrived. */
    fun stop(context: Context, summarize: Boolean = true): Boolean = stopWithSummary(context, summarize) != null

    /** Like [stop]; the job posts the summary (null when there was no quiet time to end). */
    @Synchronized
    fun stopWithSummary(context: Context, summarize: Boolean = true): kotlinx.coroutines.Job? {
        val app = context.applicationContext
        val state = store(app).load().state ?: return null
        store(app).update { it.copy(state = null) }
        val nm = app.getSystemService(NotificationManager::class.java)
        cancelEnd(app)
        NotificationManagerCompat.from(app).cancel(ONGOING_ID)
        if (nm.isNotificationPolicyAccessGranted && nm.currentInterruptionFilter == state.ourFilter) {
            try {
                state.previousPolicy?.let { p ->
                    nm.notificationPolicy = if (Build.VERSION.SDK_INT >= 30 && p.conversations >= 0) {
                        NotificationManager.Policy(p.categories, p.callSenders, p.messageSenders, p.suppressed, p.conversations)
                    } else {
                        NotificationManager.Policy(p.categories, p.callSenders, p.messageSenders, p.suppressed)
                    }
                }
                nm.setInterruptionFilter(state.previousFilter)
            } catch (_: Exception) {
            }
        }
        val container = (app as JarvisApp).container
        return CoroutineScope(Dispatchers.Default).launch {
            if (!summarize) return@launch
            val text = try {
                com.jarvis.android.actions.NotificationsTool.digest(container, state.startedAt)
                    .substringBefore("\n(Contenu des notifications")
            } catch (_: Exception) {
                ""
            }
            showSummary(app, text)
        }
    }

    /** After a reboot: the quiet time that should have ended is ended now, else its end is set again. */
    fun afterBoot(context: Context) {
        val state = store(context).load().state ?: return
        if (state.untilMs <= System.currentTimeMillis()) stop(context) else {
            scheduleEnd(context.applicationContext, state.untilMs)
            showOngoing(context.applicationContext, state.untilMs)
        }
    }

    /** A message arrived: during a quiet time, and only if the user switched it on, one automatic answer per person. */
    fun onMessage(context: Context, n: Notification, app: String, sender: String) {
        val data = store(context).load()
        val state = data.state?.takeIf { it.untilMs > System.currentTimeMillis() } ?: return
        if (!data.autoReply) return
        val text = data.replyText.ifBlank { DEFAULT_QUIET_REPLY }.replace("{heure}", clockWords(state.untilMs, ZoneId.systemDefault()))
        com.jarvis.android.messaging.AutoReply.tryReply(context, n, app, sender, text, "ne pas déranger")
    }

    private fun endIntent(context: Context) = PendingIntent.getBroadcast(
        context, ONGOING_ID, Intent(context, QuietReceiver::class.java).setAction(QuietReceiver.ACTION_END),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun scheduleEnd(context: Context, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, endIntent(context))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, endIntent(context))
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, endIntent(context))
        }
    }

    private fun cancelEnd(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(endIntent(context))

    private fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannels(listOf(
                NotificationChannel(CHANNEL, tr("Ne pas déranger"), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(SUMMARY_CHANNEL, tr("Fin du mode ne pas déranger"), NotificationManager.IMPORTANCE_DEFAULT),
            ))
    }

    private fun showOngoing(context: Context, until: Long) {
        try {
            channel(context)
            NotificationManagerCompat.from(context).notify(
                ONGOING_ID,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
                    .setContentTitle(trf("Ne pas déranger jusqu’à {0}", clockWords(until, ZoneId.systemDefault())))
                    .setContentText(tr("Seuls vos contacts favoris et les alarmes passent."))
                    .setOngoing(true)
                    .addAction(0, tr("Terminer"), endIntent(context))
                    .build(),
            )
        } catch (_: SecurityException) {
        }
    }

    private fun showSummary(context: Context, text: String) {
        try {
            channel(context)
            val body = text.ifBlank { tr("Rien de nouveau pendant cette période.") }.take(1500)
            NotificationManagerCompat.from(context).notify(
                SUMMARY_ID,
                NotificationCompat.Builder(context, SUMMARY_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                    .setContentTitle(tr("Fin du mode ne pas déranger"))
                    .setContentText(body.lineSequence().first())
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build(),
            )
        } catch (_: SecurityException) {
        }
    }
}

class QuietReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_END) return
        // Kept alive until the summary of what arrived is posted (the alarm may have woken the app just for this).
        val pending = goAsync()
        val job = QuietMode.stopWithSummary(context)
        if (job == null) pending.finish() else job.invokeOnCompletion { pending.finish() }
    }

    companion object {
        const val ACTION_END = "com.jarvis.android.QUIET_END"
    }
}
