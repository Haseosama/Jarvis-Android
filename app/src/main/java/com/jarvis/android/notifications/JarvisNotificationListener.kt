package com.jarvis.android.notifications

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * Lets Jarvis read the notifications the user can see, once the user has switched on "Notification access" for it in
 * the Android settings (Android has no other way, and only the user can do it). Notifications are kept in memory only,
 * the last few dozen, never written to disk, and forgotten when the service stops.
 */
class JarvisNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            activeNotifications?.forEach { keep(it) }
        } catch (_: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        LOG.clear()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) keep(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) LOG.remove(sbn.key)
    }

    private fun keep(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        // Not interesting: media players, downloads and other persistent entries, and group summaries that only repeat the children.
        if (sbn.isOngoing || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (n.visibility == Notification.VISIBILITY_SECRET) return
        val extras = n.extras ?: return
        val title = clean(extras.getCharSequence(Notification.EXTRA_TITLE), 120)
        val text = clean(extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT), 300)
        if (title.isEmpty() && text.isEmpty()) return
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            sbn.packageName
        }
        val (safeTitle, safeText) = redactSensitive(title, text)
        LOG.add(SeenNotification(sbn.key, clean(app, 60), sbn.packageName, sbn.postTime, safeTitle, safeText))
        // A message from someone with a reminder tied to them ("quand Paul m'écrit…"), and what driving mode reads out.
        if (n.category == Notification.CATEGORY_MESSAGE || MESSAGING_APPS.contains(sbn.packageName)) {
            try { com.jarvis.android.people.PersonReminders.onMessage(this, title) } catch (_: Exception) { }
            try { com.jarvis.android.driving.DrivingMode.onMessage(this, n, clean(app, 60), safeTitle, safeText, sbn.key) } catch (_: Exception) { }
        }
    }

    companion object {
        internal val LOG = NotificationLog()

        /** Apps whose notifications are messages from a person, even when they do not say so. */
        internal val MESSAGING_APPS = setOf(
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.whatsapp", "com.whatsapp.w4b",
            "com.facebook.orca", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.android.mms",
        )

        /** True when the user has switched on notification access for Jarvis. */
        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
