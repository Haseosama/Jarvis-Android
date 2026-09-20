package com.jarvis.android.core

import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

internal const val ACTION_STANDBY = "com.jarvis.android.action.STANDBY"

/** The foreground service is kept alive, listening for the wake word, when the user asked for it and the mic is allowed. */
internal fun wantsStandby(wakeEnabled: Boolean, micGranted: Boolean): Boolean = wakeEnabled && micGranted

/** What the service notification says, in every state. */
internal fun voiceNotificationText(state: JarvisState, standby: Boolean, video: VideoSource): String {
    val base = when (state) {
        JarvisState.ASLEEP ->
            if (standby) tr("En attente du mot d’activation · microphone actif") else tr("En veille · microphone arrêté")
        JarvisState.CONNECTING -> tr("Connexion en cours · microphone arrêté")
        JarvisState.LISTENING -> tr("À l’écoute · microphone actif")
        JarvisState.THINKING -> tr("Réflexion en cours · microphone actif")
        JarvisState.SPEAKING -> tr("Réponse en cours · microphone actif")
        JarvisState.ERROR -> tr("Session interrompue · réessayez dans l’application")
    }
    return when (video) {
        VideoSource.SCREEN -> trf("{0} · écran partagé", base)
        VideoSource.CAMERA -> trf("{0} · caméra partagée", base)
        VideoSource.OFF -> base
    }
}

internal fun voiceNotificationTitle(state: JarvisState, standby: Boolean): String =
    if (state == JarvisState.ASLEEP && standby) tr("Jarvis · écoute du mot d’activation") else tr("Jarvis · session vocale")

/** Label of the notification button: in standby it turns the wake word off instead of ending a session. */
internal fun voiceNotificationAction(state: JarvisState, standby: Boolean): String =
    if (state == JarvisState.ASLEEP && standby) tr("Désactiver l’écoute") else tr("Arrêter")

internal object VoiceServiceControl {
    /**
     * Starts the service in standby (no session). Android refuses to start a microphone foreground
     * service from the background, so this only works while the app is in front; failures are
     * reported as false rather than thrown.
     */
    fun startStandby(context: Context): Boolean = try {
        ContextCompat.startForegroundService(
            context, Intent(context, JarvisVoiceService::class.java).setAction(ACTION_STANDBY),
        )
        true
    } catch (_: Exception) {
        false
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, JarvisVoiceService::class.java))
    }
}
