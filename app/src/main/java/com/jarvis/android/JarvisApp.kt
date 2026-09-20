package com.jarvis.android

import android.app.Application

class JarvisApp : Application() {
    lateinit var container: JarvisContainer
        private set

    override fun onCreate() {
        super.onCreate()
        com.jarvis.android.i18n.Lang.load(this)
        container = JarvisContainer(applicationContext)
    }
}
