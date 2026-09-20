package com.jarvis.android.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jarvis.android.JarvisApp
import com.jarvis.android.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class JarvisVoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stopping = false
    private var sharedView = VideoSource.OFF

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification(JarvisState.CONNECTING))
        val engine = (application as JarvisApp).container.engine
        scope.launch {
            kotlinx.coroutines.flow.combine(engine.state, engine.videoSource) { state, video -> state to video }.collect { (state, video) ->
                sharedView = video
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopping = true
            (application as JarvisApp).container.engine.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else if (!stopping) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                (application as JarvisApp).container.engine.start()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        scope.cancel()
        (application as JarvisApp).container.engine.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(state: JarvisState): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Session vocale Jarvis", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, JarvisVoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val status = when (state) {
            JarvisState.ASLEEP -> "En veille · microphone arrêté"
            JarvisState.CONNECTING -> "Connexion en cours · microphone arrêté"
            JarvisState.LISTENING -> "À l’écoute · microphone actif"
            JarvisState.THINKING -> "Réflexion en cours · microphone actif"
            JarvisState.SPEAKING -> "Réponse en cours · microphone actif"
            JarvisState.ERROR -> "Session interrompue · réessayez dans l’application"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis · session vocale")
            .setContentText(
                when (sharedView) {
                    VideoSource.SCREEN -> "$status · écran partagé"
                    VideoSource.CAMERA -> "$status · caméra partagée"
                    VideoSource.OFF -> status
                }
            )
            .setContentIntent(openIntent)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(android.R.drawable.ic_media_pause, "Arrêter", stopIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "jarvis_voice"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.jarvis.android.action.STOP_VOICE"
    }
}
