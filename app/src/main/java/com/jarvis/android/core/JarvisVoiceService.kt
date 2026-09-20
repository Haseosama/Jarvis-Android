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
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@OptIn(DelicateCoroutinesApi::class)
class JarvisVoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stopping = false
    private var sharedView = VideoSource.OFF
    private var standby = false
    private var currentState = JarvisState.CONNECTING

    private val container get() = (application as JarvisApp).container

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        val engine = container.engine
        scope.launch {
            kotlinx.coroutines.flow.combine(
                engine.state, engine.videoSource, container.configStore.wakeWordEnabled,
            ) { state, video, wake -> Triple(state, video, wake) }.collect { (state, video, wake) ->
                currentState = state
                sharedView = video
                standby = wantsStandby(wake, container.micGranted())
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == ACTION_STOP -> handleStop()
            // Standby only keeps the process alive and listening for the wake word: the engine's own
            // detector does the listening while it sleeps. No session is opened.
            intent?.action == ACTION_STANDBY -> Unit
            !stopping -> scope.launch(start = CoroutineStart.UNDISPATCHED) { container.engine.start() }
        }
        return START_NOT_STICKY
    }

    /** The notification button: ends a session (back to standby if the wake word is on), or leaves standby for good. */
    private fun handleStop() {
        val idle = container.engine.state.value == JarvisState.ASLEEP
        container.engine.stop()
        if (idle) {
            // Nothing was running: the button means "stop listening for the wake word".
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { container.configStore.setWakeWordEnabled(false) }
        }
        if (idle || !wantsStandby(container.wakeEnabled, container.micGranted())) {
            stopping = true
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopping = true
        scope.cancel()
        container.engine.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(voiceNotificationTitle(currentState, standby))
            .setContentText(voiceNotificationText(currentState, standby, sharedView))
            .setContentIntent(openIntent)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(android.R.drawable.ic_media_pause, voiceNotificationAction(currentState, standby), stopIntent)
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
