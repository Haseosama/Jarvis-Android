package com.jarvis.android.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Jarvis's screens in Android Auto (car/CarScreens.kt), next to its media app (JarvisMediaService). Opening them also puts the Jarvis
 * conversation in the car's notifications (car/CarChat.kt).
 */
class JarvisCarAppService : CarAppService() {
    /** Only Google's car hosts may open it, except in a debug build (Google's desktop car-screen emulator). */
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            CarChat.open(carContext)
            return CarHomeScreen(carContext)
        }
    }
}
