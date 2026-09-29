package com.jarvis.android.wakeup

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.android.JarvisApp
import com.jarvis.android.JarvisContainer
import com.jarvis.android.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * The briefing on waking up: when the morning alarm is stopped, Jarvis says (or shows) the weather, the day's agenda
 * and reminders, the payments due and last night's sleep. Android has no "alarm dismissed" event for other apps, but it
 * does tell when the next alarm changes: the alarm that was next has just gone by, and the new next one is not a snooze.
 */

internal const val WAKE_OFF = 0
internal const val WAKE_NOTIFY = 1
internal const val WAKE_SPEAK = 2

@Serializable
internal data class WakeData(
    val mode: Int = WAKE_OFF,
    val nextAlarmAt: Long = 0,
    val lastBriefDay: String = "",
    /** When the morning alarm started ringing; Jarvis waits for it to be stopped (0: nothing pending). */
    val ringingSince: Long = 0,
    /** The parts switched off (keys of BRIEFING_SECTIONS). */
    val off: List<String> = emptyList(),
    /** Also said aloud when the phone joins the car (Android Auto or its Bluetooth) in the morning, once a day. */
    val car: Boolean = false,
    val lastCarDay: String = "",
)

internal class WakeStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun load(): WakeData = try {
        if (file.exists()) json.decodeFromString<WakeData>(file.readText()) else WakeData()
    } catch (_: Exception) {
        WakeData()
    }

    @Synchronized
    fun update(change: (WakeData) -> WakeData): WakeData {
        val next = change(load())
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(next))
        } catch (_: Exception) {
        }
        return next
    }
}

internal const val SNOOZE_WINDOW_MS = 20 * 60_000L
internal const val RANG_WITHIN_MS = 90 * 60_000L

/**
 * Whether the alarm that was next ([previous]) has just rung and been stopped for good: it is past (by 90 minutes at
 * most), it was in the morning (4 h – 12 h), the new next alarm ([next], 0 if none) is not a snooze, and there was no
 * briefing today yet.
 */
internal fun isWakeUp(previous: Long, next: Long, now: Long, lastBriefDay: String, zone: ZoneId): Boolean {
    if (previous <= 0 || previous > now + 60_000L || now - previous > RANG_WITHIN_MS) return false
    val at = Instant.ofEpochMilli(previous).atZone(zone)
    if (at.hour !in 4..11) return false
    if (next > now && next - now < SNOOZE_WINDOW_MS) return false
    return lastBriefDay != at.toLocalDate().toString()
}

/** "Bonjour !" / "Bonsoir !"… then one sentence per item that has something to say. */
internal fun composeWakeBriefing(
    hour: Int,
    weather: String?,
    events: List<String>,
    reminders: List<String>,
    sleep: String?,
    extras: List<String> = emptyList(),
): String {
    val hello = when (hour) {
        in 4..11 -> "Bonjour !"
        in 12..17 -> "Bon après-midi !"
        else -> "Bonsoir !"
    }
    return buildList {
        add(hello)
        weather?.takeIf { it.isNotBlank() }?.let { add(it.trim().removeSuffix(".") + ".") }
        if (events.isNotEmpty()) add("Aujourd'hui : " + events.joinToString(" ; ") + ".")
        if (reminders.isNotEmpty()) add("Rappels : " + reminders.joinToString(" ; ") + ".")
        extras.forEach { add(it.trim().removeSuffix(".").replaceFirstChar { c -> c.uppercase() } + ".") }
        sleep?.let { add("Cette nuit : $it.") }
        if (size == 1) add("Rien de particulier au programme aujourd'hui.")
    }.joinToString(" ")
}

internal object WakeBriefing {
    private const val CHANNEL = "jarvis_wake_briefing"

    fun store(context: Context) = (context.applicationContext as JarvisApp).container.wakeStore

    /** What the briefing says now, from what the phone knows (the weather needs the position and the network). */
    suspend fun compose(ctx: JarvisContainer, now: LocalDateTime = LocalDateTime.now()): String = composeFull(ctx, now).first

    /** The briefing, and whether rain is coming within two hours (to show the radar with it). */
    suspend fun composeFull(ctx: JarvisContainer, now: LocalDateTime = LocalDateTime.now()): Pair<String, Boolean> = kotlinx.coroutines.coroutineScope {
        val context = ctx.appContext
        val zone = ZoneId.systemDefault()
        val today = now.toLocalDate()
        val off = ctx.wakeStore.load().off.toSet()
        fun on(k: String) = k !in off
        val found = withTimeoutOrNull(8_000) {
            try { com.jarvis.android.weather.locate(context, maxAgeMs = 6 * 60 * 60_000L) as? com.jarvis.android.weather.LocationOutcome.Found } catch (_: Exception) { null }
        }
        val lat = found?.fix?.latitude
        val lon = found?.fix?.longitude
        // the newer parts, side by side
        val rainJob = async { if (on("pluie") && lat != null && lon != null) BriefingExtras.rain(ctx, lat, lon) else null }
        val mailsJob = async { if (on("mails")) BriefingExtras.mails(ctx) else null }
        val issJob = async { if (on("iss") && lat != null && lon != null) BriefingExtras.iss(ctx, lat, lon) else null }
        val launchJob = async { if (on("fusees")) BriefingExtras.launches(ctx) else null }
        val auroraJob = async { if (on("aurores") && lat != null && lon != null) BriefingExtras.aurora(ctx, lat, lon) else null }
        val weather = if (!on("meteo") || found == null) null else withTimeoutOrNull(12_000) {
            try {
                com.jarvis.android.actions.weatherAt(ctx, found.fix.latitude, found.fix.longitude, com.jarvis.android.weather.positionLabel(found.place))
            } catch (_: Exception) {
                null
            }
        }
        val events = if (!on("agenda")) emptyList() else try {
            com.jarvis.android.calendar.birthdaysToday(context) + com.jarvis.android.calendar.eventsToday(context)
        } catch (_: Exception) {
            emptyList()
        }
        val reminders = if (!on("rappels")) emptyList() else try {
            com.jarvis.android.memory.remindersToday(com.jarvis.android.reminders.ReminderService.list(context), System.currentTimeMillis(), zone)
        } catch (_: Exception) {
            emptyList()
        }
        val money = if (!on("depenses")) emptyList() else com.jarvis.android.subscriptions.dueSoonLines(ctx.subscriptionStore.all(), today)
            .filter { it.endsWith("aujourd'hui") } + try { com.jarvis.android.budgets.Budgets.briefingLines(ctx) } catch (_: Exception) { emptyList() }
        val rain = rainJob.await()
        val flights = if (on("vols")) flightsLine(com.jarvis.android.space.FlightMail.trips(context), today) else null
        val extras = listOfNotNull(rain?.first, flights) + money + listOfNotNull(mailsJob.await(), issJob.await(), launchJob.await(), auroraJob.await())
        val sleep = if (!on("sommeil")) null else withTimeoutOrNull(4_000) {
            try {
                val client = com.jarvis.android.health.healthClient(context) ?: return@withTimeoutOrNull null
                val granted = com.jarvis.android.health.grantedHealth(context)
                if (androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.SleepSessionRecord::class) !in granted) return@withTimeoutOrNull null
                com.jarvis.android.health.nightEndingOn(client, today, zone)?.let { com.jarvis.android.health.describeNight(it, zone) }
            } catch (_: Exception) {
                null
            }
        }
        composeWakeBriefing(now.hour, weather, events, reminders, sleep, extras) to (rain?.second == true)
    }

    /** Says and/or shows the briefing, as the user chose (aloud also shows it). */
    suspend fun deliver(ctx: JarvisContainer, mode: Int): String {
        val (text, rainSoon) = composeFull(ctx)
        val context = ctx.appContext
        // rain coming: the radar where the face is, to see it arrive
        if (rainSoon) ctx.videoPanel.show(com.jarvis.android.video.VideoPanel.Video(title = "Radar de pluie", sky = com.jarvis.android.space.SkyModes.RADAR))
        if (mode == WAKE_SPEAK) com.jarvis.android.driving.DrivingMode.speak(context, text, thenRelease = true)
        try {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, tr("Briefing au réveil"), NotificationManager.IMPORTANCE_DEFAULT))
            NotificationManagerCompat.from(context).notify(
                7_801,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(tr("Briefing du réveil"))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (_: SecurityException) {
        }
        return text
    }

    /**
     * The next alarm changed. The clock app does that as soon as an alarm starts ringing (the next one is then
     * tomorrow's), not when it is stopped: so when the alarm that was next is the morning one now due, Jarvis only
     * starts watching, and speaks once the ringing is over and it was not a snooze (see [check]).
     */
    fun onNextAlarmChanged(context: Context) {
        val app = context.applicationContext
        val next = app.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime ?: 0L
        val now = System.currentTimeMillis()
        val before = store(app).load()
        store(app).update { it.copy(nextAlarmAt = next) }
        if (before.mode == WAKE_OFF || !isWakeUp(before.nextAlarmAt, next, now, before.lastBriefDay, ZoneId.systemDefault())) return
        store(app).update { it.copy(ringingSince = now) }
        scheduleCheck(app)
    }

    /** The phone joined the car (Android Auto, or the car's Bluetooth): in the morning, once a day, the briefing aloud. */
    fun onCarConnected(context: Context) {
        val app = context.applicationContext
        val data = store(app).load()
        val today = LocalDate.now().toString()
        if (!data.car || data.lastCarDay == today || LocalDateTime.now().hour !in 5..11) return
        store(app).update { it.copy(lastCarDay = today) }
        val ctx = (app as JarvisApp).container
        CoroutineScope(Dispatchers.Default).launch {
            kotlinx.coroutines.delay(8_000) // the car's audio settles first
            try { deliver(ctx, WAKE_SPEAK) } catch (_: Exception) {}
        }
    }

    /** Whether an alarm is sounding right now (any app's player with the alarm usage). */
    private fun alarmSounding(context: Context): Boolean = try {
        context.getSystemService(android.media.AudioManager::class.java).activePlaybackConfigurations
            .any { it.audioAttributes.usage == android.media.AudioAttributes.USAGE_ALARM }
    } catch (_: Exception) {
        false
    }

    private fun checkIntent(context: Context) = android.app.PendingIntent.getBroadcast(
        context, 7_802, Intent(context, WakeBriefingReceiver::class.java).setAction(WakeBriefingReceiver.ACTION_CHECK),
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun scheduleCheck(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + CHECK_EVERY_MS
        try {
            if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, checkIntent(context))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, checkIntent(context))
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, checkIntent(context))
        }
    }

    /** Still ringing: look again later. Stopped: a snooze waits for the next ring; otherwise, the briefing. */
    fun check(context: Context, onDone: () -> Unit) {
        val app = context.applicationContext
        val data = store(app).load()
        val now = System.currentTimeMillis()
        if (data.ringingSince == 0L || data.mode == WAKE_OFF) { onDone(); return }
        if (alarmSounding(app) && now - data.ringingSince < MAX_RINGING_MS) {
            scheduleCheck(app)
            onDone()
            return
        }
        val next = app.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime ?: 0L
        store(app).update { it.copy(ringingSince = 0, nextAlarmAt = next) }
        if (next > now && next - now < SNOOZE_WINDOW_MS) { onDone(); return } // snoozed: the next ring starts over
        store(app).update { it.copy(lastBriefDay = LocalDate.now().toString()) }
        val ctx = (app as JarvisApp).container
        CoroutineScope(Dispatchers.Default).launch {
            try { deliver(ctx, data.mode) } finally { onDone() }
        }
    }
}

internal const val CHECK_EVERY_MS = 20_000L
internal const val MAX_RINGING_MS = 30 * 60_000L

class WakeBriefingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> WakeBriefing.onNextAlarmChanged(context)
            ACTION_CHECK -> {
                val pending = goAsync()
                WakeBriefing.check(context) { pending.finish() }
            }
        }
    }

    companion object {
        const val ACTION_CHECK = "com.jarvis.android.WAKE_CHECK"
    }
}
