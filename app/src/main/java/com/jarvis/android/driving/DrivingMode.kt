package com.jarvis.android.driving

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.android.JarvisApp
import com.jarvis.android.i18n.tr
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

/*
 * Driving mode: on when the phone joins the car's Bluetooth (if the user asked for it) or by voice ("mode conduite"),
 * off when it leaves or by voice. While it is on, Jarvis keeps its answers short, reads out the messages that arrive,
 * and, only if the user switched it on, answers each person once with "Je conduis…" through the notification's own
 * reply button (never in a group, at most once per person every 30 minutes and 10 times an hour).
 */

internal const val DEFAULT_DRIVING_REPLY = "Je conduis, je te réponds dès que possible. (Réponse automatique)"

@Serializable
internal data class DrivingSettings(
    val autoStart: Boolean = false,
    val readMessages: Boolean = true,
    val autoReply: Boolean = false,
    val replyText: String = DEFAULT_DRIVING_REPLY,
)

internal class DrivingStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun load(): DrivingSettings = try {
        if (file.exists()) json.decodeFromString<DrivingSettings>(file.readText()) else DrivingSettings()
    } catch (_: Exception) {
        DrivingSettings()
    }

    @Synchronized
    fun update(change: (DrivingSettings) -> DrivingSettings): DrivingSettings {
        val next = change(load())
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(next))
        } catch (_: Exception) {
        }
        return next
    }
}

/** What is said for a message: "Message WhatsApp de Paul : j'arrive." (the text cut at 300 characters). */
internal fun announcement(app: String, sender: String, text: String): String {
    val body = text.replace(Regex("\\s+"), " ").trim().let { if (it.length > 300) it.take(300).substringBeforeLast(' ') + "…" else it }
    val from = sender.ifBlank { "quelqu'un" }
    return if (body.isEmpty()) "Message $app de $from." else "Message $app de $from : $body"
}

internal object DrivingMode {
    private const val CHANNEL = "jarvis_driving"
    private const val NOTIFICATION_ID = 7_601

    @Volatile var active = false
        private set
    @Volatile var startedByCar = false
        private set

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pending = mutableListOf<String>()
    private val spoken = ArrayDeque<String>()

    fun store(context: Context) = (context.applicationContext as JarvisApp).container.drivingStore

    @Synchronized
    fun start(context: Context, byCar: Boolean) {
        val app = context.applicationContext
        if (!active) startedByCar = byCar
        active = true
        showNotification(app)
        speak(app, tr("Mode conduite activé."))
    }

    @Synchronized
    fun stop(context: Context) {
        val app = context.applicationContext
        val was = active
        active = false
        startedByCar = false
        NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
        if (was) speak(app, tr("Mode conduite désactivé."), thenRelease = true)
    }

    /** A message notification arrived (from the notification listener). */
    fun onMessage(context: Context, notification: Notification, app: String, sender: String, text: String, key: String) {
        if (!active) return
        val settings = store(context).load()
        val id = "$key|$text"
        synchronized(this) {
            if (id in spoken) return
            spoken.addLast(id)
            while (spoken.size > 60) spoken.removeFirst()
        }
        if (settings.readMessages) speak(context, announcement(app, sender, text))
        if (settings.autoReply) {
            com.jarvis.android.messaging.AutoReply.tryReply(context, notification, app, sender, settings.replyText.ifBlank { DEFAULT_DRIVING_REPLY }, "mode conduite")
        }
    }

    @Synchronized
    fun speak(context: Context, text: String, thenRelease: Boolean = false) {
        val engine = tts ?: TextToSpeech(context.applicationContext) { status ->
            synchronized(this) {
                ttsReady = status == TextToSpeech.SUCCESS
                val t = tts ?: return@synchronized
                if (ttsReady) {
                    t.language = if (com.jarvis.android.i18n.Lang.isEnglish) Locale.UK else Locale.FRANCE
                    pending.forEach { t.speak(it, TextToSpeech.QUEUE_ADD, null, "driving") }
                }
                pending.clear()
            }
        }.also {
            tts = it
            it.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
        }
        if (ttsReady) engine.speak(text, TextToSpeech.QUEUE_ADD, null, "driving") else pending += text
        if (thenRelease) {
            // Let the last words be said, then free the speech engine.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                synchronized(this) { if (!active) { tts?.shutdown(); tts = null; ttsReady = false } }
            }, 6_000)
        }
    }

    private fun showNotification(context: Context) {
        try {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, tr("Mode conduite"), NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getBroadcast(
                context, NOTIFICATION_ID, Intent(context, DrivingReceiver::class.java).setAction(DrivingReceiver.ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            )
            val s = store(context).load()
            val what = listOfNotNull(
                tr("réponses courtes"),
                if (s.readMessages) tr("messages lus à voix haute") else null,
                if (s.autoReply) tr("réponse automatique") else null,
            ).joinToString(", ")
            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_directions)
                    .setContentTitle(tr("Mode conduite"))
                    .setContentText(what)
                    .setOngoing(true)
                    .addAction(0, tr("Arrêter"), stop)
                    .build(),
            )
        } catch (_: SecurityException) {
        }
    }
}

class DrivingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) DrivingMode.stop(context)
    }

    companion object {
        const val ACTION_STOP = "com.jarvis.android.DRIVING_STOP"
    }
}
