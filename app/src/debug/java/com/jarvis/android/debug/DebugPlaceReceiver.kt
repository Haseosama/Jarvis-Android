package com.jarvis.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.android.places.Geofences

/**
 * Debug builds only (DUMP permission, so only adb): fires a location reminder as if its zone had been crossed, for the
 * emulator, whose network location provider is off, so Play services' low-power geofencing never sees simulated GPS fixes.
 *   adb shell am broadcast -a com.jarvis.android.DEBUG_PLACE -p <package> --ei id 1
 */
class DebugPlaceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        if (id <= 0) return
        val app = context.applicationContext
        val result = goAsync()
        Thread {
            try { Geofences.fire(app, id) } finally { result.finish() }
        }.start()
    }
}
