package com.jarvis.android.video

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import com.jarvis.android.JarvisApp
import com.jarvis.android.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The video as the phone's other players are: a media session (a headset's or a car's buttons, the lock screen, the quick settings'
 * player) and a notification with its buttons, while a video is shown. Pause, play, next, previous, 30 s on or 10 s back, close.
 */
internal class VideoMedia(private val context: Context, private val panel: VideoPanel, scope: CoroutineScope) {
    private val session = MediaSession(context, "JarvisVideo").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() { panel.command(VideoPanel.Command.Resume) }
            override fun onPause() { panel.command(VideoPanel.Command.Pause) }
            override fun onSkipToNext() { panel.step(1) }
            override fun onSkipToPrevious() { panel.step(-1) }
            override fun onFastForward() { panel.command(VideoPanel.Command.SeekBy(30)) }
            override fun onRewind() { panel.command(VideoPanel.Command.SeekBy(-10)) }
            override fun onStop() { panel.close() }
            override fun onSeekTo(pos: Long) { panel.command(VideoPanel.Command.SeekBy(((pos / 1000) - panel.positionS).toInt())) }
            // the car (Android Auto) or an assistant asks for something to play: an item of the car's list, or words
            override fun onPlayFromMediaId(mediaId: String?, extras: android.os.Bundle?) { mediaId?.let { playRequest(it, null) } }
            override fun onPlayFromSearch(query: String?, extras: android.os.Bundle?) { playRequest(null, query.orEmpty()) }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    }

    /** What plays an item of the car's list or a search ([mediaId] or [query]); set by the container (see car/CarLibrary.kt). */
    @Volatile var playRequest: (mediaId: String?, query: String?) -> Unit = { _, _ -> }

    /** The session the car's media service hands to Android Auto. */
    val sessionToken: MediaSession.Token get() = session.sessionToken

    /** The notification of what plays now (null: nothing), for the media service to stay in the foreground with it. */
    @Volatile var notification: Notification? = null
        private set
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Volatile private var duration = 0
    private val ticks = kotlinx.coroutines.flow.MutableStateFlow(0)

    init {
        // the video, and the moments its player says where it is (for the lock screen's progress)
        scope.launch(Dispatchers.Main) { combine(panel.video, ticks) { v, _ -> v }.collect { update(it) } }
    }

    /** The player said where it is: the lock screen's progress follows. */
    fun progress(durationS: Int) {
        duration = durationS
        ticks.value++
    }
    private var lastKey: String? = null

    private fun update(v: VideoPanel.Video?) {
        if (v == null) {
            // nothing plays: the session still takes the car's requests (play an item, a search)
            session.setPlaybackState(PlaybackState.Builder().setActions(IDLE_ACTIONS).setState(PlaybackState.STATE_STOPPED, 0L, 0f).build())
            session.isActive = false
            manager?.cancel(NOTIFICATION_ID)
            notification = null
            lastKey = null
            return
        }
        val key = v.key ?: v.title
        if (key != lastKey) {
            duration = 0
            lastKey = key
        }
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, v.title.ifBlank { tr("Vidéo") })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, v.artist.ifBlank { "Jarvis" })
                .putLong(MediaMetadata.METADATA_KEY_DURATION, if (v.isSlideshow) -1L else duration * 1000L)
                .build(),
        )
        val several = v.count > 1 || v.isSlideshow
        var actions = IDLE_ACTIONS or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        if (several) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (!v.isSlideshow) actions = actions or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_SEEK_TO
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (v.paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING,
                    if (v.isSlideshow) PlaybackState.PLAYBACK_POSITION_UNKNOWN else panel.positionS * 1000L,
                    if (v.paused) 0f else 1f,
                )
                .build(),
        )
        session.isActive = true
        post(v, several)
    }

    private fun post(v: VideoPanel.Video, several: Boolean) {
        val nm = manager ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, tr("Vidéo"), NotificationManager.IMPORTANCE_LOW))
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val buttons = buildList {
            if (several) add(action(android.R.drawable.ic_media_previous, tr("Vidéo précédente"), DO_PREVIOUS))
            add(if (v.paused) action(android.R.drawable.ic_media_play, tr("Lecture"), DO_PLAY) else action(android.R.drawable.ic_media_pause, tr("Pause"), DO_PAUSE))
            if (several) add(action(android.R.drawable.ic_media_next, tr("Vidéo suivante"), DO_NEXT))
            add(action(android.R.drawable.ic_menu_close_clear_cancel, tr("Fermer la vidéo"), DO_CLOSE))
        }
        val compact = if (several) intArrayOf(0, 1, 2) else intArrayOf(0, 1)
        val n = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(v.title.ifBlank { tr("Vidéo") })
            .setContentText(
                when {
                    v.isSlideshow -> tr("Diaporama")
                    v.radio -> tr("Radio en direct")
                    v.podcast -> v.artist.ifBlank { tr("Podcast") }
                    else -> tr("Vidéo dans Jarvis")
                },
            )
            .setContentIntent(open)
            .setOngoing(!v.paused)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(*compact))
            .apply { buttons.forEach { addAction(it) } }
            .build()
        notification = n
        // without the notification permission, nothing is shown (the media service still uses it to stay in the foreground)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        try { nm.notify(NOTIFICATION_ID, n) } catch (_: SecurityException) {}
    }

    private fun action(icon: Int, label: String, what: String): Notification.Action =
        Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(context, icon), label, controlIntent(context, what)).build()

    companion object {
        private const val CHANNEL_ID = "jarvis_video"
        const val NOTIFICATION_ID = 4711

        /** Always offered, even with nothing playing: the car's list and a search. */
        private const val IDLE_ACTIONS = PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH
        const val DO_PLAY = "play"
        const val DO_PAUSE = "pause"
        const val DO_NEXT = "next"
        const val DO_PREVIOUS = "previous"
        const val DO_CLOSE = "close"

        /** A button's intent: the video's control receiver, told what to do. */
        fun controlIntent(context: Context, what: String): PendingIntent = PendingIntent.getBroadcast(
            context, what.hashCode(),
            Intent(context, VideoControlReceiver::class.java).putExtra(VideoControlReceiver.EXTRA_DO, what),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** The video's buttons outside the app: the notification's, the picture-in-picture window's. */
class VideoControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val panel = (context.applicationContext as JarvisApp).container.videoPanel
        when (intent.getStringExtra(EXTRA_DO)) {
            VideoMedia.DO_PLAY -> panel.command(VideoPanel.Command.Resume)
            VideoMedia.DO_PAUSE -> panel.command(VideoPanel.Command.Pause)
            VideoMedia.DO_NEXT -> panel.step(1)
            VideoMedia.DO_PREVIOUS -> panel.step(-1)
            VideoMedia.DO_CLOSE -> panel.close()
        }
    }

    companion object {
        const val EXTRA_DO = "do"
    }
}
