package com.jarvis.android.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.jarvis.android.MainActivity

/**
 * Lets Jarvis be chosen as the phone's "digital assistant" (Settings > Default apps). Android then starts it
 * from the assistant gesture (long press on the home or power button, depending on the phone), also from the
 * lock screen after the phone is unlocked. The service itself does nothing else: the gesture just opens the
 * app and starts a voice session, exactly like the widget or the launcher shortcut.
 */
class JarvisInteractionService : VoiceInteractionService()

class JarvisSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = JarvisSession(this)
}

class JarvisSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            startAssistantActivity(Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_START_SESSION))
        } catch (_: Exception) {
            // Nothing else to try: the user can still start Jarvis from the app.
        }
        hide()
    }
}
