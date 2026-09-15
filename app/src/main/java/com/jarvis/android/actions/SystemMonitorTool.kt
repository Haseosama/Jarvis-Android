package com.jarvis.android.actions

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.StatFs
import com.jarvis.android.JarvisContainer
import kotlinx.serialization.json.JsonObject

/** Device telemetry — Android port of `actions/system_monitor.py` (battery/storage; no CPU temp API on Android). */
object SystemMonitorTool : Tool {
    override val name = "system_monitor"
    override val description = "Report battery level and free storage on the phone."

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val batteryStatus = ctx.appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val charging = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            ?.let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL } ?: false

        val stat = StatFs(ctx.appContext.filesDir.path)
        val freeGb = stat.availableBytes / (1024.0 * 1024.0 * 1024.0)
        val totalGb = stat.totalBytes / (1024.0 * 1024.0 * 1024.0)

        val batteryStr = if (pct >= 0) "$pct%${if (charging) " (charging)" else ""}" else "unknown"
        return "Battery: $batteryStr. Storage: ${"%.1f".format(freeGb)} GB free of ${"%.1f".format(totalGb)} GB."
    }
}
