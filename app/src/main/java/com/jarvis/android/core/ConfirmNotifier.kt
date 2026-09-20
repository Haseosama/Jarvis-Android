package com.jarvis.android.core

import com.jarvis.android.i18n.tr
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jarvis.android.JarvisApp
import com.jarvis.android.R

private const val CHANNEL_ID = "jarvis_confirm"
private const val NOTIFICATION_ID = 4242
private const val ACTION_CONFIRM = "com.jarvis.android.CONFIRM_YES"
private const val ACTION_CANCEL = "com.jarvis.android.CONFIRM_NO"

/**
 * Shows a pending confirmation as a notification with Confirm / Cancel buttons, so the user can
 * answer while another app is in front (the in-app banner would be hidden). The answer still
 * only ever comes from a tap by the user, never from the model.
 */
internal class ConfirmNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun show(pending: PendingConfirmation?) {
        if (pending == null) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, tr("Confirmations Jarvis"), NotificationManager.IMPORTANCE_HIGH)
        )
        fun action(name: String, code: Int) = PendingIntent.getBroadcast(
            context, code, Intent(context, ConfirmActionReceiver::class.java).setAction(name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Jarvis : ${pending.actionLabel}")
            .setContentText(pending.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(pending.detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .addAction(0, tr("Confirmer"), action(ACTION_CONFIRM, 1))
            .addAction(0, tr("Annuler"), action(ACTION_CANCEL, 2))
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notifications not allowed: the banner inside the app remains available.
        }
    }
}

class ConfirmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val confirm = (context.applicationContext as JarvisApp).container.confirmManager
        android.util.Log.i("JarvisConfirm", "Réponse de l’utilisateur : ${intent.action}")
        when (intent.action) {
            ACTION_CONFIRM -> confirm.confirm()
            ACTION_CANCEL -> confirm.cancel()
        }
    }
}
