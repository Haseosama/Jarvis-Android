package com.jarvis.android.car

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.jarvis.android.JarvisApp
import com.jarvis.android.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/*
 * Talking to Jarvis in Android Auto, the way Google lets an app hold a conversation there: as a messaging conversation. Jarvis is a
 * "contact" whose messages the car shows and reads aloud; the user answers by voice (the car's own dictation: "Répondre"), and the answer
 * goes to the same text chat as the phone's (engine/RestChat.kt: every tool, the memory, the same history), and Jarvis's reply comes back
 * in the conversation and is spoken. Nothing to look at or type: the car screen and the steering wheel's voice button are enough.
 */

/** The longest reply said in the car: past it the rest is on the phone's chat. */
internal const val CAR_SPOKEN_MAX_CHARS = 700

/** How many messages the car's conversation keeps on screen. */
internal const val CAR_CHAT_KEPT = 8

/**
 * [text] as it can be said and shown on a car screen: no Markdown (stars, hashes, back quotes, links' addresses), no list marks, one line,
 * cut at a sentence's end past [max] characters (the full answer stays in the phone's chat).
 */
internal fun carSpoken(text: String, max: Int = CAR_SPOKEN_MAX_CHARS): String {
    val plain = text
        .replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "$1")
        .replace(Regex("""https?://\S+"""), "")
        .replace(Regex("""(?m)^\s*(#{1,6}|[-*•]|\d+[.)])\s+"""), "")
        .replace("**", "").replace("__", "").replace("`", "").replace("*", "")
        .replace(Regex("""\s+"""), " ")
        .trim()
    if (plain.length <= max) return plain
    val cut = plain.take(max)
    val end = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf("! "), cut.lastIndexOf("? "))
    return (if (end > max / 3) cut.take(end + 1) else cut.substringBeforeLast(' ') + "…") + " " + tr("(La suite est sur le téléphone.)")
}

/** One line of the car's conversation; [mine] when the user said it. */
internal data class CarLine(val text: String, val mine: Boolean, val at: Long)

/** The conversation kept after adding [line]: the last [kept] lines. */
internal fun carLinesAfter(lines: List<CarLine>, line: CarLine, kept: Int = CAR_CHAT_KEPT): List<CarLine> = (lines + line).takeLast(kept)

internal object CarChat {
    private const val CHANNEL = "jarvis_car_chat"
    private const val NOTIFICATION_ID = 7_701
    const val ACTION_REPLY = "com.jarvis.android.CAR_CHAT_REPLY"
    const val ACTION_READ = "com.jarvis.android.CAR_CHAT_READ"
    const val KEY_TEXT = "text"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lines = emptyList<CarLine>()

    /** Puts the conversation on the car screen with a first word from Jarvis, once per drive (again only once it was taken away). */
    @Synchronized
    fun open(context: Context) {
        if (lines.isNotEmpty()) return
        say(context, tr("Je suis là. Répondez à ce message pour me parler : je peux faire tout ce que je fais sur le téléphone."), alert = true, speak = false)
    }

    /** The car went: the conversation leaves the notifications (the phone's chat keeps it all). */
    @Synchronized
    fun close(context: Context) {
        lines = emptyList()
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** A message from Jarvis in the car's conversation (a reply, or something it has to tell), said aloud when [speak]. */
    @Synchronized
    fun say(context: Context, text: String, alert: Boolean = false, speak: Boolean = true) {
        val spoken = carSpoken(text)
        if (spoken.isEmpty()) return
        lines = carLinesAfter(lines, CarLine(spoken, mine = false, at = System.currentTimeMillis()))
        post(context, alert)
        if (speak) com.jarvis.android.driving.DrivingMode.speak(context, spoken)
    }

    /** What the user dictated in the car: shown at once, then sent to Jarvis; its answer comes back in the conversation. */
    fun onReply(context: Context, text: String) {
        val app = context.applicationContext
        val asked = text.trim()
        if (asked.isEmpty()) return
        synchronized(this) {
            lines = carLinesAfter(lines, CarLine(asked, mine = true, at = System.currentTimeMillis()))
            post(app, alert = false)
        }
        scope.launch {
            val answer = ask(app, asked)
            say(app, answer)
        }
    }

    /** [question] asked of Jarvis's text chat (the phone's, with every tool); the answer, or what went wrong, in words. */
    suspend fun ask(context: Context, question: String): String {
        val chat = (context.applicationContext as JarvisApp).container.restChat
        val problem = try {
            chat.send(question)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            tr("Erreur inattendue. Réessayez.")
        }
        if (problem != null) return problem
        return chat.messages.value.lastOrNull { it.role == com.jarvis.android.core.ConversationRole.ASSISTANT }?.text
            ?: tr("Je n’ai pas de réponse.")
    }

    private fun post(context: Context, alert: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Jarvis en voiture"), NotificationManager.IMPORTANCE_HIGH))
        val me = Person.Builder().setName(tr("Moi")).setKey("me").build()
        val jarvis = Person.Builder().setName("Jarvis").setKey("jarvis").setBot(true).build()
        val style = NotificationCompat.MessagingStyle(me)
        lines.forEach { style.addMessage(it.text, it.at, if (it.mine) me else jarvis) }
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val replyIntent = PendingIntent.getBroadcast(
            context, 1, Intent(context, CarChatReceiver::class.java).setAction(ACTION_REPLY),
            PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        )
        val readIntent = PendingIntent.getBroadcast(
            context, 2, Intent(context, CarChatReceiver::class.java).setAction(ACTION_READ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val reply = NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, tr("Répondre"), replyIntent)
            .addRemoteInput(RemoteInput.Builder(KEY_TEXT).setLabel(tr("Parler à Jarvis")).build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val read = NotificationCompat.Action.Builder(android.R.drawable.ic_menu_view, tr("Marquer comme lu"), readIntent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addAction(reply)
            .addInvisibleAction(read)
            // a reply already said aloud does not ring again; the first word does, so that the car shows it
            .setOnlyAlertOnce(!alert)
            .setSilent(!alert)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // notifications not allowed: the car app's screen (CarScreens.kt) still talks
        }
    }
}

/** The conversation's buttons in the car: the dictated answer, and "read". */
class CarChatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CarChat.ACTION_REPLY -> RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CarChat.KEY_TEXT)?.toString()?.let { CarChat.onReply(context, it) }
            CarChat.ACTION_READ -> Unit   // the car marks it read; the conversation stays for the next answer
        }
    }
}
