package com.jarvis.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.android.JarvisApp
import com.jarvis.android.actions.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Runs one tool from adb and logs the answer, to test tools inside the real app process:
 * `adb shell am broadcast -a com.jarvis.android.DEBUG_TOOL -p <package> --es tool screen_tap --es text Stopwatch`
 * then read the tag `DebugTool` in logcat. Debug builds only; protected by the DUMP permission.
 */
class DebugToolReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tool = intent.getStringExtra("tool") ?: return
        // Simple extras (--es text Stopwatch) are easier to pass through adb than JSON.
        val simple = intent.extras?.keySet().orEmpty()
            .filter { it != "tool" && it != "args" }
            .mapNotNull { key -> intent.getStringExtra(key)?.let { key to JsonPrimitive(it) } }
            .toMap()
        val args = try {
            JsonObject(Json.parseToJsonElement(intent.getStringExtra("args") ?: "{}").jsonObject + simple)
        } catch (_: Exception) {
            JsonObject(simple)
        }
        val pending = goAsync()
        val container = (context.applicationContext as JarvisApp).container
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val result = ToolRegistry.run(tool, args, container)
                result.chunked(900).forEachIndexed { i, part -> Log.i("DebugTool", "$tool#$i $part") }
                Log.i("DebugTool", "$tool END")
            } finally {
                pending.finish()
            }
        }
    }
}
