package com.jarvis.android.messaging

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.jarvis.android.JarvisApp
import com.jarvis.android.driving.autoReplyAllowed

/**
 * The automatic answer of driving mode ("je conduis") and of the quiet mode ("je suis en réunion"), sent with the
 * notification's own reply button — only when the user switched it on for that mode. One history for both, so a person
 * gets one automatic answer every 30 minutes whatever the mode, never in a group, and 10 an hour at most.
 */
internal object AutoReply {
    private val replies = mutableListOf<Pair<String, Long>>()

    /** Answers [sender] with [text]; true when it was sent. [why] is what the log of sent messages says ("mode conduite"). */
    fun tryReply(context: Context, n: Notification, app: String, sender: String, text: String, why: String): Boolean {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style?.isGroupConversation == true) return false
        val now = System.currentTimeMillis()
        synchronized(this) {
            replies.removeAll { now - it.second > 60 * 60_000L }
            if (!autoReplyAllowed(replies, sender, now)) return false
        }
        val action = n.actions?.firstOrNull { a ->
            a.remoteInputs?.any { it.allowFreeFormInput } == true &&
                (android.os.Build.VERSION.SDK_INT < 28 || a.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY || a.semanticAction == Notification.Action.SEMANTIC_ACTION_NONE)
        } ?: return false
        return try {
            val inputs = action.remoteInputs
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            val intent = Intent()
            RemoteInput.addResultsToIntent(inputs, intent, results)
            action.actionIntent.send(context, 0, intent)
            synchronized(this) { replies += sender to now }
            (context.applicationContext as JarvisApp).container.sentMessages.add(SentMessage(now, sender, "$app (réponse auto, $why)", text))
            true
        } catch (_: Exception) {
            false
        }
    }
}
