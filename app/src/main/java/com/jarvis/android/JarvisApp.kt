package com.jarvis.android

import android.app.Application

class JarvisApp : Application() {
    lateinit var container: JarvisContainer
        private set

    override fun onCreate() {
        super.onCreate()
        com.jarvis.android.i18n.Lang.load(this)
        container = JarvisContainer(applicationContext)
        // Android Auto projecting: the Jarvis conversation appears in the car's notifications, and leaves with the car
        try {
            androidx.car.app.connection.CarConnection(this).type.observeForever { type ->
                if (type == androidx.car.app.connection.CarConnection.CONNECTION_TYPE_PROJECTION) com.jarvis.android.car.CarChat.open(this)
                else if (type == androidx.car.app.connection.CarConnection.CONNECTION_TYPE_NOT_CONNECTED) com.jarvis.android.car.CarChat.close(this)
            }
        } catch (_: Exception) {
        }
    }
}
