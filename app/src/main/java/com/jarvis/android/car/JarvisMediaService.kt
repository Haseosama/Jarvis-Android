package com.jarvis.android.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaDescription
import android.media.browse.MediaBrowser
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.service.media.MediaBrowserService
import com.jarvis.android.JarvisApp
import com.jarvis.android.i18n.tr
import com.jarvis.android.podcasts.PodcastSubscriptions
import com.jarvis.android.video.VideoMedia

/**
 * Jarvis as a media app: Android Auto (and the phone's own media controls) browse its list here (car/CarLibrary.kt) and control it
 * through the video panel's media session. While sound plays (video/AudioPlayer.kt), this service is in the foreground with the
 * player's notification, so the radio or the podcast goes on with the screen off, the app closed, and the phone locked in the car.
 */
class JarvisMediaService : MediaBrowserService() {
    private val container get() = (application as JarvisApp).container

    override fun onCreate() {
        super.onCreate()
        sessionToken = container.videoMedia.sessionToken
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAYING -> {
                val n = container.videoMedia.notification ?: placeholder()
                try {
                    if (Build.VERSION.SDK_INT >= 29) startForeground(VideoMedia.NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    else startForeground(VideoMedia.NOTIFICATION_ID, n)
                } catch (e: Exception) {
                    android.util.Log.w("JarvisMedia", "foreground refused", e)
                }
            }
            ACTION_STOPPED -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()   // a car still bound keeps it alive
            }
        }
        return START_NOT_STICKY
    }

    /** Only the car, the system (its media controls) and Google's assistant may browse what Jarvis keeps (podcasts, episodes). */
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        val trusted = clientUid == Process.myUid() || clientUid == Process.SYSTEM_UID ||
            (clientPackageName in TRUSTED && packageManager.getPackagesForUid(clientUid).orEmpty().contains(clientPackageName))
        if (!trusted) return null
        // the phone's media controls, after a restart: only what played last, to play it again
        if (rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT) == true) {
            return if (CarLibrary.last(this) != null) BrowserRoot(CarLibrary.RECENT, Bundle().apply { putBoolean(BrowserRoot.EXTRA_RECENT, true) }) else null
        }
        return BrowserRoot(CarLibrary.ROOT, Bundle().apply {
            putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1)   // lists
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
        })
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        val nodes = if (parentId == CarLibrary.RECENT) listOfNotNull(CarLibrary.last(this)) else CarLibrary.children(
            parentId, CarLibrary.recentRadios(this), PodcastSubscriptions(this).all(), container.videoHistory.all(),
        )
        result.sendResult(nodes.map { node ->
            MediaBrowser.MediaItem(
                MediaDescription.Builder().setMediaId(node.id).setTitle(node.title).setSubtitle(node.subtitle).build(),
                if (node.playable) MediaBrowser.MediaItem.FLAG_PLAYABLE else MediaBrowser.MediaItem.FLAG_BROWSABLE,
            )
        }.toMutableList())
    }

    /** Until the player's own notification is ready (it then takes the same place). */
    private fun placeholder(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("jarvis_video", tr("Vidéo"), NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "jarvis_video").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Jarvis").build()
    }

    companion object {
        const val ACTION_PLAYING = "com.jarvis.android.MEDIA_PLAYING"
        const val ACTION_STOPPED = "com.jarvis.android.MEDIA_STOPPED"

        private val TRUSTED = setOf(
            "com.google.android.projection.gearhead",       // Android Auto
            "com.android.car.media",                        // Android Automotive
            "com.android.systemui",                         // the phone's media controls
            "com.google.android.googlequicksearchbox",      // Google's assistant
            "com.google.android.carassistant",
        )
    }
}
