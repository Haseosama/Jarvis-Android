package com.jarvis.android.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.android.i18n.tr

internal const val RING_DEFAULT_SECONDS = 60
internal const val RING_MAX_SECONDS = 180

/**
 * "Où es-tu ?": the phone rings as loud as it can for a minute so it can be found under a cushion. It plays on the
 * ALARM stream, which silent mode does not mute and Do Not Disturb lets through by default, turned all the way up for
 * the ring and put back to where it was afterwards; it vibrates too. It stops by itself, by voice ("arrête de sonner"),
 * or with the "Trouvé" button of its notification.
 */
internal object PhoneRinger {
    private const val CHANNEL = "jarvis_find_phone"
    private const val NOTIFICATION_ID = 7_401
    private val main = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var tone: android.media.ToneGenerator? = null
    private var previousVolume: Int? = null
    private val stopper = Runnable { stopNow() }
    private lateinit var appContext: Context

    val ringing: Boolean @Synchronized get() = player != null || tone != null

    /** Starts ringing for [seconds] (a new call restarts the countdown). False when no sound could be played at all. */
    @Synchronized
    fun start(context: Context, seconds: Int = RING_DEFAULT_SECONDS): Boolean {
        appContext = context.applicationContext
        val duration = seconds.coerceIn(10, RING_MAX_SECONDS)
        main.removeCallbacks(stopper)
        if (!ringing) {
            val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (previousVolume == null) previousVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            try {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            } catch (_: SecurityException) {
                // Do Not Disturb may refuse a volume change: it still rings at the current alarm volume.
            }
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            player = uri?.let {
                try {
                    MediaPlayer().apply {
                        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        setDataSource(appContext, it)
                        isLooping = true
                        prepare()
                        start()
                    }
                } catch (_: Exception) {
                    null
                }
            }
            if (player == null) {
                // No usable ringtone on the phone (or it failed to play): a generated, continuous alarm tone instead.
                tone = try {
                    android.media.ToneGenerator(AudioManager.STREAM_ALARM, android.media.ToneGenerator.MAX_VOLUME)
                        .apply { startTone(android.media.ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK) }
                } catch (_: RuntimeException) {
                    null
                }
            }
            vibrate(appContext)
            showNotification(appContext)
        }
        main.postDelayed(stopper, duration * 1000L)
        return ringing
    }

    /** Stops the ring, the vibration and the notification, and puts the alarm volume back. */
    @Synchronized
    fun stopNow() {
        main.removeCallbacks(stopper)
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { tone?.stopTone(); tone?.release() } catch (_: Exception) {}
        tone = null
        if (!::appContext.isInitialized) return
        previousVolume?.let { v ->
            try {
                (appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_ALARM, v, 0)
            } catch (_: SecurityException) {
            }
        }
        previousVolume = null
        vibrator(appContext)?.cancel()
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)

    private fun vibrate(context: Context) {
        try {
            vibrator(context)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
        } catch (_: Exception) {
        }
    }

    private fun showNotification(context: Context) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, tr("Retrouver le téléphone"), NotificationManager.IMPORTANCE_HIGH))
            val stop = PendingIntent.getBroadcast(
                context, NOTIFICATION_ID, Intent(context, PhoneRingerReceiver::class.java).setAction(PhoneRingerReceiver.ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(tr("Je suis ici !"))
                    .setContentText(tr("Touchez « Trouvé » pour arrêter la sonnerie."))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setOngoing(true)
                    .setContentIntent(stop)
                    .addAction(0, tr("Trouvé"), stop)
                    .build(),
            )
        } catch (_: SecurityException) {
            // Notifications refused: it still rings, and stops by itself or by voice.
        }
    }
}

class PhoneRingerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) PhoneRinger.stopNow()
    }

    companion object {
        const val ACTION_STOP = "com.jarvis.android.findphone.STOP"
    }
}
