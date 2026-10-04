package com.jarvis.android.reminders

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The alarm that fires a reminder, and the checks on it. Its receiver stays where it always was (actions/ReminderReceiver: the alarms
 * already set name it), so it is named here by its class name, which keeps this package from depending on the actions.
 */
internal object ReminderAlarm {
    const val CHANNEL_ID = "jarvis_reminders"
    const val EXTRA_ID = "id"
    private const val EXTRA_TOKEN = "reminder_token"
    private const val ACTION_FIRE = "com.jarvis.android.reminders.FIRE"

    /** The receiver of the alarms (a test checks it is still the class's name). */
    const val RECEIVER_CLASS = "com.jarvis.android.actions.ReminderReceiver"

    private fun identity(id: Int, token: String): Uri = Uri.Builder()
        .scheme("jarvis-reminder").authority("local").appendPath(id.toString()).appendPath(token).build()

    private fun intent(context: Context, id: Int, token: String) =
        Intent().setClassName(context, RECEIVER_CLASS).apply {
            action = ACTION_FIRE
            data = identity(id, token)
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_TOKEN, token)
        }

    fun pendingIntent(context: Context, id: Int, token: String): PendingIntent =
        PendingIntent.getBroadcast(context, id, intent(context, id, token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun existingPendingIntent(context: Context, id: Int, token: String): PendingIntent? =
        PendingIntent.getBroadcast(context, id, intent(context, id, token),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

    /** The reminder an alarm names (its id and token), or null when [intent] is not one of these alarms. */
    fun parse(intent: Intent): Pair<Int, String>? {
        if (intent.action != ACTION_FIRE) return null
        val id = intent.getIntExtra(EXTRA_ID, 0)
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return null
        if (id <= 0 || token.length != 36 || intent.data != identity(id, token)) return null
        return id to token
    }
}
