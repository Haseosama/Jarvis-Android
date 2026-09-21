package com.jarvis.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.android.JarvisApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug builds only (protected by the DUMP permission): gives the offline mode a sentence as if it had been said, and logs the answer.
 *   adb shell am broadcast -a com.jarvis.android.DEBUG_OFFLINE -p <package> --es say "ouvre la calculatrice"
 *   adb logcat -s JarvisOffline
 */
class DebugOfflineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("say") ?: return
        val container = (context.applicationContext as JarvisApp).container
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val (reply, end) = container.engine.offlineReply(text)
                Log.i("JarvisOffline", "\"$text\" -> \"$reply\" (end=$end)")
            } catch (e: Exception) {
                Log.e("JarvisOffline", "\"$text\" failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
