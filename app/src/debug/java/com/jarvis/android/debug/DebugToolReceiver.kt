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
            .filter { it != "tool" && it != "args" && it != "args_file" }
            .mapNotNull { key -> intent.getStringExtra(key)?.let { key to JsonPrimitive(it) } }
            .toMap()
        // Long or awkward arguments (Markdown with line breaks) are easier to put in a file inside the app's own folder:
        // `adb shell run-as <package> sh -c 'cat > files/args.json' < args.json`, then `--es args_file args.json`.
        val fromFile = intent.getStringExtra("args_file")?.let { java.io.File(context.filesDir, it).takeIf { f -> f.isFile }?.readText() }
        val args = try {
            JsonObject(Json.parseToJsonElement(fromFile ?: intent.getStringExtra("args") ?: "{}").jsonObject + simple.filterKeys { it != "args_file" })
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
