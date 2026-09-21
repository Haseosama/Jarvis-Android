package com.jarvis.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.android.JarvisApp
import com.jarvis.android.update.AppUpdater
import java.io.File

/**
 * Debug builds only (protected by the DUMP permission, so only adb can send it): installs an APK found in files/ of the app through the
 * same code as the update card.
 *   adb shell am broadcast -a com.jarvis.android.DEBUG_UPDATE -p <package> --es file update-test.apk
 * The answer goes to logcat (tags DebugUpdate and AppUpdate).
 */
class DebugUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as JarvisApp).container
        val file = File(context.filesDir, intent.getStringExtra("file") ?: "update-test.apk")
        val problem = AppUpdater(context, container.http).install(file)
        Log.i("DebugUpdate", "install(${file.name}, ${file.length()} bytes) -> ${problem ?: "committed"}")
    }
}
