package com.jarvis.android.people

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.android.JarvisApp
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.Normalizer

/*
 * "La prochaine fois que Paul m'appelle, rappelle-moi de lui parler du week-end": a note tied to a contact, shown when
 * that person calls, when a message from them arrives, or when Jarvis calls or writes to them. It stays until "Fait".
 */

@Serializable
internal data class PersonReminder(
    val id: Long,
    val name: String,
    val keys: List<String>,
    val text: String,
    val createdAt: Long,
    val lastShownAt: Long = 0,
)

@Serializable
private data class PersonReminderData(val items: List<PersonReminder> = emptyList())

/** The same number however it is written: its last 9 digits ("06 12 34 56 78" and "+33612345678" match). */
internal fun numberKey(number: String): String = number.filter { it.isDigit() }.takeLast(9)

private fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

internal fun remindersForNumber(items: List<PersonReminder>, number: String): List<PersonReminder> {
    val key = numberKey(number)
    return if (key.length < 6) emptyList() else items.filter { key in it.keys }
}

/** The reminders for a message whose sender is shown as [title] ("Paul Durand", or "Paul Durand (2 messages)"). */
internal fun remindersForSender(items: List<PersonReminder>, title: String): List<PersonReminder> {
    // "Paul Durand (2 messages)" is Paul Durand; "Marie-Claire" is not Marie.
    val t = fold(title.replace(Regex("\\s*\\([^)]*\\)\\s*$"), ""))
    if (t.isEmpty()) return emptyList()
    return items.filter { r -> fold(r.name).let { it.isNotEmpty() && it == t } }
}

/** Not shown again within 30 minutes: a call and the messages around it make one reminder, not five. */
internal const val PERSON_REMINDER_QUIET_MS = 30 * 60_000L

internal class PersonReminderStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun all(): List<PersonReminder> = try {
        if (file.exists()) json.decodeFromString<PersonReminderData>(file.readText()).items else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    fun update(change: (List<PersonReminder>) -> List<PersonReminder>): List<PersonReminder> {
        val next = change(all())
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(PersonReminderData(next)))
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Exception) {
        }
        return next
    }

    fun add(name: String, numbers: List<String>, text: String, now: Long = System.currentTimeMillis()): PersonReminder {
        val r = PersonReminder(now, name, numbers.map(::numberKey).filter { it.length >= 6 }.distinct(), text.trim(), now)
        update { it + r }
        return r
    }

    fun remove(id: Long) = update { list -> list.filterNot { it.id == id } }

    /** Marks [shown] as shown now and returns those that were not shown in the last 30 minutes. */
    fun takeDue(shown: List<PersonReminder>, now: Long = System.currentTimeMillis()): List<PersonReminder> {
        val due = shown.filter { now - it.lastShownAt >= PERSON_REMINDER_QUIET_MS }.map { it.id }.toSet()
        if (due.isEmpty()) return emptyList()
        return update { list -> list.map { if (it.id in due) it.copy(lastShownAt = now) else it } }.filter { it.id in due }
    }
}

internal object PersonReminders {
    private const val CHANNEL = "jarvis_person_reminders"

    private fun store(context: Context) = (context.applicationContext as JarvisApp).container.personReminderStore

    fun onCall(context: Context, number: String) = show(context, store(context).let { s -> s.takeDue(remindersForNumber(s.all(), number)) })

    fun onMessage(context: Context, senderTitle: String) = show(context, store(context).let { s -> s.takeDue(remindersForSender(s.all(), senderTitle)) })

    /** When Jarvis itself calls or writes to [number]: the reminders to say, also shown as notifications. */
    fun onJarvisContact(context: Context, number: String): List<PersonReminder> {
        val s = store(context)
        val matching = remindersForNumber(s.all(), number)
        show(context, s.takeDue(matching))
        return matching
    }

    /** What Jarvis adds to its answer when it calls or writes to [number]: " Au fait, pour Paul : lui parler du week-end." */
    fun noteFor(context: Context, number: String): String =
        onJarvisContact(context, number).joinToString("") { " Rappel noté pour ${it.name} : « ${it.text} » (dites-le à l'utilisateur)." }

    fun show(context: Context, reminders: List<PersonReminder>) {
        if (reminders.isEmpty()) return
        try {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, tr("Rappels liés à une personne"), NotificationManager.IMPORTANCE_HIGH))
            for (r in reminders) {
                val id = (r.id % 1_000_000).toInt() + 8_000_000
                val done = PendingIntent.getBroadcast(
                    context, id, Intent(context, PersonReminderReceiver::class.java).setAction(PersonReminderReceiver.ACTION_DONE).putExtra("id", r.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                NotificationManagerCompat.from(context).notify(
                    id,
                    NotificationCompat.Builder(context, CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_popup_reminder)
                        .setContentTitle(trf("À propos de {0}", r.name))
                        .setContentText(r.text)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(r.text))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_REMINDER)
                        .addAction(0, tr("Fait"), done)
                        .build(),
                )
            }
        } catch (_: SecurityException) {
        }
    }

    fun canWatchCalls(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
}

/** Incoming (and, where Android gives the number, outgoing) calls. */
class PersonCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        if (number.isNotBlank() && (state == TelephonyManager.EXTRA_STATE_RINGING || state == TelephonyManager.EXTRA_STATE_OFFHOOK)) {
            PersonReminders.onCall(context, number)
        }
    }
}

/** The "Fait" button of a reminder. */
class PersonReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DONE) return
        val id = intent.getLongExtra("id", -1)
        (context.applicationContext as JarvisApp).container.personReminderStore.remove(id)
        NotificationManagerCompat.from(context).cancel((id % 1_000_000).toInt() + 8_000_000)
    }

    companion object {
        const val ACTION_DONE = "com.jarvis.android.PERSON_REMINDER_DONE"
    }
}
