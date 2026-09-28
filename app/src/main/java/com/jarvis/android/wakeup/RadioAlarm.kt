package com.jarvis.android.wakeup

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.jarvis.android.JarvisApp
import com.jarvis.android.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/*
 * Waking up to the radio: at the time asked, a foreground service plays the station at the alarm's volume, rising slowly, with
 * « Arrêter » and « 10 min de plus » in its notification. It is set as an alarm clock (the phone shows it, it rings in Doze), so the
 * wake briefing, which watches the phone's next alarm and speaks once an alarm has stopped sounding, follows it by itself. If the
 * station does not answer, it is looked for again by its name, and if that fails the phone's own alarm sound rings: an alarm must.
 */

/** The radio alarm: [hour]:[minute], on [days] (ISO, 1 Monday … 7 Sunday; none: once), the station and its stream. */
@Serializable
internal data class RadioAlarmSpec(
    val hour: Int, val minute: Int, val days: List<Int> = emptyList(), val station: String, val stream: String, val snoozeAt: Long = 0L,
    /** False once a one-time alarm has rung: kept only so that « 10 min de plus » still knows the station. */
    val active: Boolean = true,
)

/** When [spec] next rings after [now]: today or a following day, on one of its days when it has some. */
internal fun nextRing(spec: RadioAlarmSpec, now: LocalDateTime, zone: ZoneId): Long {
    var day = now.toLocalDate()
    repeat(8) {
        val at = day.atTime(LocalTime.of(spec.hour, spec.minute))
        if (at.isAfter(now) && (spec.days.isEmpty() || day.dayOfWeek.value in spec.days)) return at.atZone(zone).toInstant().toEpochMilli()
        day = day.plusDays(1)
    }
    return day.atTime(LocalTime.of(spec.hour, spec.minute)).atZone(zone).toInstant().toEpochMilli()
}

/** "tous les jours", "en semaine", "le week-end", "lun., mer." or "une fois". */
internal fun daysWords(days: List<Int>): String = when (days.toSortedSet().toList()) {
    emptyList<Int>() -> "une fois"
    (1..7).toList() -> "tous les jours"
    (1..5).toList() -> "en semaine"
    listOf(6, 7) -> "le week-end"
    else -> days.sorted().joinToString(", ") { listOf("lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim.")[it - 1] }
}

internal object RadioAlarms {
    private val json = Json { ignoreUnknownKeys = true }
    private fun prefs(context: Context) = context.getSharedPreferences("radio_alarm", Context.MODE_PRIVATE)

    /** The alarm as stored, rung or not (see [current]). */
    fun get(context: Context): RadioAlarmSpec? =
        try { prefs(context).getString("spec", null)?.let { json.decodeFromString<RadioAlarmSpec>(it) } } catch (_: Exception) { null }

    /** The alarm still to ring (a one-time alarm that rang is not, unless it was put off). */
    fun current(context: Context): RadioAlarmSpec? = get(context)?.takeIf { it.active || it.snoozeAt > System.currentTimeMillis() }

    private fun put(context: Context, spec: RadioAlarmSpec?) =
        prefs(context).edit().apply { if (spec == null) remove("spec") else putString("spec", json.encodeToString(spec)) }.apply()

    /** Sets [spec] (replacing the one there was) and schedules it; the time it rings and whether that time is only approximate. */
    fun set(context: Context, spec: RadioAlarmSpec): Pair<Long, Boolean> {
        put(context, spec)
        return schedule(context, spec)
    }

    fun cancel(context: Context) {
        put(context, null)
        context.getSystemService(AlarmManager::class.java).cancel(ringIntent(context))
    }

    /** Rings again in [minutes] (the rest of the alarm stays as it was). */
    fun snooze(context: Context, minutes: Int) {
        val spec = get(context) ?: return
        val at = System.currentTimeMillis() + minutes * 60_000L
        put(context, spec.copy(snoozeAt = at))
        program(context, at)
    }

    /** After it rang: the next day it has, or nothing more for a one-time alarm. */
    fun afterRing(context: Context) {
        val spec = get(context) ?: return
        if (spec.snoozeAt > System.currentTimeMillis()) return
        if (spec.days.isEmpty()) put(context, spec.copy(active = false, snoozeAt = 0L)) else schedule(context, spec.copy(snoozeAt = 0L))
    }

    /** After a reboot (every alarm is forgotten then). */
    fun reschedule(context: Context) {
        val spec = get(context) ?: return
        when {
            spec.snoozeAt > System.currentTimeMillis() -> program(context, spec.snoozeAt)
            spec.active -> schedule(context, spec.copy(snoozeAt = 0L))
        }
    }

    private fun schedule(context: Context, spec: RadioAlarmSpec): Pair<Long, Boolean> {
        put(context, spec)
        val at = nextRing(spec, LocalDateTime.now(), ZoneId.systemDefault())
        return at to program(context, at)
    }

    /** Programs the ring at [at] as an alarm clock; true when only an approximate alarm was allowed. */
    private fun program(context: Context, at: Long): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        val show = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.let { PendingIntent.getActivity(context, 7_901, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        return try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) throw SecurityException("exact alarms not allowed")
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), ringIntent(context))
            false
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, ringIntent(context))
            true
        }
    }

    private fun ringIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 7_900, Intent(context, RadioAlarmReceiver::class.java).setAction(RadioAlarmReceiver.ACTION_RING),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class RadioAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RING) return
        val app = context.applicationContext
        // the station goes with the intent: a one-time alarm is forgotten just below, before the service reads anything
        val spec = RadioAlarms.get(app)
        try {
            ContextCompat.startForegroundService(
                app, Intent(app, RadioAlarmService::class.java)
                    .putExtra(RadioAlarmService.EXTRA_STATION, spec?.station).putExtra(RadioAlarmService.EXTRA_STREAM, spec?.stream),
            )
        } catch (_: Exception) {
            // Android refused to start the player from the background (an approximate alarm): a notification that plays on a tap
            RadioAlarmService.notifyOnly(app)
        }
        RadioAlarms.afterRing(app)
    }

    companion object {
        const val ACTION_RING = "com.jarvis.android.RADIO_ALARM"
    }
}

/** Plays the alarm's station, at the alarm's volume, rising for a minute; stops by itself after an hour. */
class RadioAlarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var ramp: Job? = null
    private var triedAgain = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAll(); return START_NOT_STICKY }
            ACTION_SNOOZE -> { RadioAlarms.snooze(this, SNOOZE_MIN); stopAll(); return START_NOT_STICKY }
        }
        val station = intent?.getStringExtra(EXTRA_STATION) ?: RadioAlarms.get(this)?.station
        val stream = intent?.getStringExtra(EXTRA_STREAM) ?: RadioAlarms.get(this)?.stream
        val notification = notification(this, station ?: tr("Réveil"), withActions = true)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION_ID, notification)
        if (player == null) {
            if (station != null && stream != null) play(stream, station) else fallback()
            scope.launch { delay(MAX_PLAY_MS); stopAll() }
        }
        return START_NOT_STICKY
    }

    private fun play(url: String, station: String) {
        release()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(url)
            mp.setOnPreparedListener { it.setVolume(START_VOLUME, START_VOLUME); it.start(); rise(it) }
            mp.setOnErrorListener { _, _, _ -> retry(station); true }
            mp.setOnCompletionListener { retry(station) }
            mp.prepareAsync()
        } catch (_: Exception) {
            retry(station)
        }
    }

    /** The stream failed: look for the station again by its name (its address may have changed), else the phone's alarm sound. */
    private fun retry(station: String) {
        if (triedAgain) { fallback(); return }
        triedAgain = true
        scope.launch {
            val found = try {
                withContext(Dispatchers.IO) { com.jarvis.android.actions.RadioTool.findStations((application as JarvisApp).container.http, station, null) }.firstOrNull()
            } catch (_: Exception) {
                null
            }
            if (found != null) play(found.stream, station) else fallback()
        }
    }

    private fun fallback() {
        release()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                setWakeMode(this@RadioAlarmService, PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(this@RadioAlarmService, uri)
                isLooping = true
                setOnPreparedListener { it.start(); rise(it) }
                prepareAsync()
            }
        } catch (_: Exception) {
        }
    }

    /** From a low volume to full, over a minute. */
    private fun rise(mp: MediaPlayer) {
        ramp?.cancel()
        ramp = scope.launch {
            val steps = 30
            for (i in 1..steps) {
                delay(RISE_MS / steps)
                val v = START_VOLUME + (1f - START_VOLUME) * i / steps
                try { mp.setVolume(v, v) } catch (_: IllegalStateException) { return@launch }
            }
        }
    }

    private fun release() {
        ramp?.cancel()
        player?.let { try { it.release() } catch (_: Exception) {} }
        player = null
    }

    private fun stopAll() {
        release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "jarvis_radio_alarm"
        private const val NOTIFICATION_ID = 7_903
        const val ACTION_STOP = "com.jarvis.android.RADIO_ALARM_STOP"
        const val ACTION_SNOOZE = "com.jarvis.android.RADIO_ALARM_SNOOZE"
        const val EXTRA_STATION = "station"
        const val EXTRA_STREAM = "stream"
        private const val SNOOZE_MIN = 10
        private const val START_VOLUME = 0.12f
        private const val RISE_MS = 60_000L
        private const val MAX_PLAY_MS = 60 * 60_000L

        private fun action(context: Context, what: String, code: Int): PendingIntent =
            PendingIntent.getService(context, code, Intent(context, RadioAlarmService::class.java).setAction(what), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun notification(context: Context, station: String, withActions: Boolean): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Réveil radio"), NotificationManager.IMPORTANCE_HIGH))
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.let { PendingIntent.getActivity(context, 7_904, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(tr("Réveil radio"))
                .setContentText(station)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(
                    if (withActions) open
                    else PendingIntent.getForegroundService(context, 7_907, Intent(context, RadioAlarmService::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
                .apply {
                    if (withActions) {
                        addAction(Notification.Action.Builder(null, tr("Arrêter"), action(context, ACTION_STOP, 7_905)).build())
                        addAction(Notification.Action.Builder(null, trf("{0} min de plus", SNOOZE_MIN.toString()), action(context, ACTION_SNOOZE, 7_906)).build())
                    }
                }
                .build()
        }

        /** When the player could not be started in the background: a notification whose tap starts it. */
        fun notifyOnly(context: Context) {
            val station = RadioAlarms.get(context)?.station ?: tr("Réveil")
            try { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(context, station, withActions = false)) } catch (_: SecurityException) {}
        }

        private fun trf(pattern: String, vararg args: String) = com.jarvis.android.i18n.trf(pattern, *args)
    }
}
