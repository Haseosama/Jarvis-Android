package com.jarvis.android.space

import android.app.AlarmManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId

/*
 * "Préviens-moi quand l'ISS passe": an alarm 5 minutes before the next pass that can be seen (the satellite lit by the Sun in a dark sky)
 * over the place the user was when asking; then a notification, and the same words aloud if asked, saying where to look. With [repeat],
 * the next visible pass is set at once, again and again. Kept across a reboot.
 */

/** A pass alert: which satellite (as the user named it), where, and when the next alarm is. */
@Serializable
internal data class PassAlert(val name: String, val lat: Double, val lon: Double, val voice: Boolean, val repeat: Boolean, val alarmAt: Long = 0L, val riseAt: Long = 0L)

/** What a visible pass is, in words for a notification: where it appears, how high, where it goes, how long. */
internal fun passWords(satellite: String, p: Pass, zone: ZoneId = ZoneId.systemDefault()): String {
    fun hm(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().let { "%02dh%02d".format(it.hour, it.minute) }
    return "${satellite.replaceFirstChar { it.uppercase() }} passe à ${hm(p.riseMs)} : apparition ${fromDirection(p.riseAzimuth).replaceFirst("du ", "au ").replaceFirst("de l’", "à l’")}, " +
        "jusqu’à ${p.maxElevation.toInt()}° de haut vers ${hm(p.maxMs)}, disparition ${towardDirection(p.setAzimuth)} vers ${hm(p.setMs)} " +
        "(${maxOf(1, ((p.setMs - p.riseMs) / 60_000).toInt())} min). Visible à l’œil nu : un point brillant qui avance sans clignoter."
}

internal object PassAlerts {
    private const val LEAD_MS = 5 * 60_000L
    private const val RECHECK_MS = 48 * 3_600_000L
    private val json = Json { ignoreUnknownKeys = true }
    private fun prefs(context: Context) = context.getSharedPreferences("pass_alerts", Context.MODE_PRIVATE)

    fun get(context: Context): PassAlert? = try { prefs(context).getString("alert", null)?.let { json.decodeFromString<PassAlert>(it) } } catch (_: Exception) { null }

    private fun put(context: Context, a: PassAlert?) =
        prefs(context).edit().apply { if (a == null) remove("alert") else putString("alert", json.encodeToString(a)) }.apply()

    /**
     * The next visible pass of [alert]'s satellite after [fromMs] (in 5 days), found in the orbits kept on the phone (downloaded if too
     * old), and the tle it is of.
     */
    suspend fun nextVisible(context: Context, alert: PassAlert, fromMs: Long, ahead: Boolean = true): Pair<Tle, Pass>? {
        val ctx = (context.applicationContext as JarvisApp).container
        val tles = SatelliteTool.orbits(ctx, "stations") + SatelliteTool.orbits(ctx, "visual")
        val tle = SatelliteTool.find(tles, alert.name) ?: return null
        // ahead: far enough to warn 5 minutes before (setting one); else the one about to start (the alarm ringing)
        val p = passes(Sgp4(tle), Observer(alert.lat, alert.lon), fromMs, 120, 10.0).firstOrNull { it.visible && (!ahead || it.riseMs - LEAD_MS > System.currentTimeMillis()) }
            ?: return null
        return tle to p
    }

    /** Sets [alert] for its next visible pass after [fromMs]; the pass and whether the alarm is only approximate, or null when none comes. */
    suspend fun schedule(context: Context, alert: PassAlert, fromMs: Long = System.currentTimeMillis()): Pair<Pass, Boolean>? {
        val (tle, p) = nextVisible(context, alert, fromMs) ?: run {
            // none within 5 days (visible passes come in periods of a week or two): every pass asked for, so look again in 2 days
            if (alert.repeat) {
                val check = System.currentTimeMillis() + RECHECK_MS
                put(context, alert.copy(alarmAt = check, riseAt = 0L))
                program(context, check)
            } else cancel(context)
            return null
        }
        val at = p.riseMs - LEAD_MS
        put(context, alert.copy(name = alert.name.ifBlank { tle.name }, alarmAt = at, riseAt = p.riseMs))
        return p to program(context, at)
    }

    fun cancel(context: Context) {
        put(context, null)
        context.getSystemService(AlarmManager::class.java).cancel(intent(context))
    }

    /** After a reboot (the alarm is forgotten). */
    fun afterBoot(context: Context) {
        val a = get(context) ?: return
        if (a.alarmAt > System.currentTimeMillis()) program(context, a.alarmAt)
        else CoroutineScope(Dispatchers.Default).launch { try { schedule(context, a) } catch (_: Exception) {} }
    }

    private fun program(context: Context, at: Long): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) throw SecurityException("not allowed")
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context))
            false
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context))
            true
        }
    }

    private fun intent(context: Context) = PendingIntent.getBroadcast(
        context, 7_950, Intent(context, PassAlertReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The alarm rang: the pass it was for (recomputed), told, and with [PassAlert.repeat] the next one set. */
    suspend fun ring(context: Context) {
        val a = get(context) ?: return
        // an alarm to look again for a visible pass, not for one
        if (a.riseAt == 0L) { try { schedule(context, a) } catch (_: Exception) {}; return }
        val found = try { nextVisible(context, a, a.riseAt - 10 * 60_000L, ahead = false) } catch (_: Exception) { null }
        if (found != null) {
            val (tle, p) = found
            val text = passWords(friendlyName(tle.name), p)
            notify(context, text)
            if (a.voice) com.jarvis.android.driving.DrivingMode.speak(context, text, thenRelease = true)
        }
        if (a.repeat) try { schedule(context, a, (found?.second?.setMs ?: a.riseAt) + 60_000L) } catch (_: Exception) {} else put(context, null)
    }

    private fun notify(context: Context, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jarvis_space", tr("Passages de satellites"), NotificationManager.IMPORTANCE_HIGH))
        try {
            NotificationManagerCompat.from(context).notify(
                7_951,
                NotificationCompat.Builder(context, "jarvis_space")
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setContentTitle(tr("Passage dans 5 minutes"))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (_: SecurityException) {
        }
    }
}

class PassAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try { PassAlerts.ring(context.applicationContext) } finally { pending.finish() }
        }
    }
}
