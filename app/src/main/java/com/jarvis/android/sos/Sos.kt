package com.jarvis.android.sos

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import com.jarvis.android.messaging.SendOutcome
import com.jarvis.android.messaging.sendSms
import com.jarvis.android.weather.Fix
import com.jarvis.android.weather.LocationOutcome
import com.jarvis.android.weather.locate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

/*
 * "Au secours" / "SOS": after a 10-second countdown that a tap or "annule" stops, an SMS with the phone's position goes
 * to the (at most 3) trusted contacts the user chose in the settings, and, if they asked for it, the first one is called.
 * Nothing happens until contacts are set; emergency services are never called by Jarvis.
 */

internal const val SOS_MAX_CONTACTS = 3
internal const val SOS_COUNTDOWN_SECONDS = 10
/** A second alert this soon after one went is refused: a loop must not flood the contacts. */
internal const val SOS_COOLDOWN_MS = 60_000L

@Serializable
internal data class SosContact(val name: String, val number: String)

@Serializable
internal data class SosData(
    val contacts: List<SosContact> = emptyList(),
    val callFirst: Boolean = false,
    val lastSentAt: Long = 0,
)

internal class SosStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun load(): SosData = try {
        if (file.exists()) json.decodeFromString<SosData>(file.readText()) else SosData()
    } catch (_: Exception) {
        SosData()
    }

    @Synchronized
    fun update(change: (SosData) -> SosData): SosData {
        val next = change(load())
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(next))
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Exception) {
        }
        return next
    }
}

private fun numberKey(number: String): String = number.filter { it.isDigit() }.takeLast(9)

/** [data] with [contact] added: the same number is not added twice, and there are never more than [SOS_MAX_CONTACTS]. */
internal fun withContact(data: SosData, contact: SosContact): SosData {
    val key = numberKey(contact.number)
    if (key.length < 6 || data.contacts.any { numberKey(it.number) == key } || data.contacts.size >= SOS_MAX_CONTACTS) return data
    return data.copy(contacts = data.contacts + contact.copy(name = contact.name.trim().ifBlank { contact.number.trim() }, number = contact.number.trim()))
}

/** The text sent to the contacts: who asks for help, and where the phone is (a map link) or that the position is unknown. */
internal fun sosMessage(fix: Fix?, nowMs: Long): String {
    val head = "SOS : j'ai besoin d'aide, appelle-moi dès que possible. (Message envoyé par mon assistant Jarvis.)"
    if (fix == null) return "$head Position indisponible."
    val lat = String.format(Locale.ROOT, "%.5f", fix.latitude)
    val lon = String.format(Locale.ROOT, "%.5f", fix.longitude)
    val minutes = ((nowMs - fix.timeMs) / 60_000L).coerceAtLeast(0)
    val age = if (minutes < 2) "" else ", il y a $minutes min"
    return "$head Ma position : https://maps.google.com/?q=$lat,$lon (à ${fix.accuracyMeters.toInt()} m près$age)."
}

/** Names read out loud: "Marie", "Marie et Paul", "Marie, Paul et Léa". */
internal fun namesList(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " et " + names.last()
}

internal sealed interface SosArm {
    data class Armed(val names: List<String>, val seconds: Int) : SosArm
    data object NoContacts : SosArm
    data object AlreadyArmed : SosArm
    data class TooSoon(val secondsAgo: Long) : SosArm
}

/** Why an alert cannot start now, or null when it can. */
internal fun sosRefusal(data: SosData, armed: Boolean, nowMs: Long): SosArm? = when {
    data.contacts.isEmpty() -> SosArm.NoContacts
    armed -> SosArm.AlreadyArmed
    nowMs - data.lastSentAt in 0 until SOS_COOLDOWN_MS -> SosArm.TooSoon((nowMs - data.lastSentAt) / 1000)
    else -> null
}

internal object SosAlarm {
    private const val CHANNEL = "jarvis_sos"
    private const val NOTIFICATION_ID = 7_501
    private const val RESULT_ID = 7_502
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var countdown: Job? = null

    val armed: Boolean @Synchronized get() = countdown?.isActive == true

    /** Starts the countdown; when it ends without being cancelled, the alert goes. */
    @Synchronized
    fun arm(context: Context, store: SosStore, seconds: Int = SOS_COUNTDOWN_SECONDS, nowMs: Long = System.currentTimeMillis()): SosArm {
        val data = store.load()
        sosRefusal(data, armed, nowMs)?.let { return it }
        val app = context.applicationContext
        val delaySec = seconds.coerceIn(5, 30)
        showCountdown(app, delaySec)
        countdown = scope.launch {
            delay(delaySec * 1000L)
            NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
            send(app, store)
        }
        return SosArm.Armed(data.contacts.map { it.name }, delaySec)
    }

    /** Stops a countdown in progress. False when there was none (the alert may already have gone). */
    @Synchronized
    fun cancel(context: Context): Boolean {
        val was = armed
        countdown?.cancel()
        countdown = null
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
        return was
    }

    private suspend fun send(context: Context, store: SosStore) {
        val data = store.update { it.copy(lastSentAt = System.currentTimeMillis()) }
        // A position up to 10 minutes old is still worth sending; a fresh one is looked for first.
        val fix = (locate(context, maxAgeMs = 10 * 60_000L) as? LocationOutcome.Found)?.fix
        val text = sosMessage(fix, System.currentTimeMillis())
        val sent = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (c in data.contacts) {
            if (sendSms(context, c.number, text) is SendOutcome.Sent) sent += c.name else failed += c.name
        }
        val first = data.contacts.first()
        var called = false
        if (data.callFirst && ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            called = try {
                context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(first.number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: Exception) {
                false
            }
        }
        val lines = buildList {
            if (sent.isNotEmpty()) add(trf("SOS envoyé à {0}.", namesList(sent)))
            if (failed.isNotEmpty()) add(trf("Échec de l’envoi à {0}.", namesList(failed)))
            if (fix == null) add(tr("Position introuvable : elle n’était pas dans le message."))
            if (called) add(trf("Appel de {0}.", first.name))
        }
        showResult(context, lines.joinToString(" "), first)
    }

    private fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, tr("Alerte SOS"), NotificationManager.IMPORTANCE_HIGH))
    }

    private fun showCountdown(context: Context, seconds: Int) {
        try {
            channel(context)
            val cancel = PendingIntent.getBroadcast(
                context, NOTIFICATION_ID, Intent(context, SosReceiver::class.java).setAction(SosReceiver.ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE,
            )
            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(trf("Alerte SOS dans {0} secondes", seconds))
                    .setContentText(tr("Touchez « Annuler » ou dites « annule » pour l’arrêter."))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setWhen(System.currentTimeMillis() + seconds * 1000L)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setOngoing(true)
                    .setTimeoutAfter(seconds * 1000L + 2_000L)
                    .addAction(0, tr("Annuler"), cancel)
                    .build(),
            )
        } catch (_: SecurityException) {
            // Notifications refused: the countdown still runs and stops by voice.
        }
    }

    private fun showResult(context: Context, text: String, first: SosContact) {
        try {
            channel(context)
            val dial = PendingIntent.getActivity(
                context, RESULT_ID, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(first.number))),
                PendingIntent.FLAG_IMMUTABLE,
            )
            NotificationManagerCompat.from(context).notify(
                RESULT_ID,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(tr("Alerte SOS"))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .addAction(0, trf("Appeler {0}", first.name), dial)
                    .build(),
            )
        } catch (_: SecurityException) {
        }
    }
}

class SosReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_CANCEL) SosAlarm.cancel(context)
    }

    companion object {
        const val ACTION_CANCEL = "com.jarvis.android.SOS_CANCEL"
    }
}
