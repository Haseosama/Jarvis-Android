package com.jarvis.android.actions

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import com.jarvis.android.JarvisContainer
import com.jarvis.android.core.UndoEntry
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/**
 * Device controls — Android port of `actions/computer_settings.py`. Volume is a
 * direct, reversible change. Brightness needs the user to grant "Modify system
 * settings" once (Android gates it behind a special permission, unlike volume).
 * Wi-Fi cannot be toggled programmatically since Android 10 — Google removed
 * that API for privacy reasons — so this opens the Wi-Fi settings panel instead
 * of pretending to flip it, matching how the desktop app's real confirmation
 * gate treats anything the model cannot actually verify happened.
 */
object DeviceSettingsTool : Tool {
    override val name = "device_settings"
    override val description =
        "Control phone settings: volume (set_volume, 0-100, asks the user to confirm first), or open the Wi-Fi / brightness settings panel."
    override val parameters = objectSchema(required = listOf("action")) {
        string("action", "One of: set_volume, open_wifi, open_brightness.")
        integer("value", "For set_volume: percent 0-100.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        return when (args.stringArg("action")) {
            "set_volume" -> setVolume(args.intArg("value", -1), ctx)
            "open_wifi" -> openPanel(ctx, Settings.ACTION_WIFI_SETTINGS, "Wi-Fi settings")
            "open_brightness" -> openPanel(ctx, Settings.ACTION_DISPLAY_SETTINGS, "display settings")
            else -> "Unknown device_settings action."
        }
    }

    private suspend fun setVolume(percent: Int, ctx: JarvisContainer): String {
        if (percent < 0 || percent > 100) return "Give a volume percentage between 0 and 100."
        val confirmed = try {
            withTimeout(CONFIRM_TIMEOUT_MS) {
                ctx.confirmManager.request("Volume", "Mettre le volume à $percent % ?")
            }
        } catch (_: TimeoutCancellationException) {
            return "Confirmation expirée : volume inchangé."
        }
        if (!confirmed) return "Volume inchangé : action refusée."
        val am = ctx.appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val previous = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (max * percent / 100.0).toInt().coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        ctx.undoManager.push(UndoEntry("volume change") {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, previous, 0)
            "Volume restored."
        })
        return "Volume set to $percent%."
    }

    private fun openPanel(ctx: JarvisContainer, action: String, label: String): String {
        val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return try {
            ctx.appContext.startActivity(intent)
            "Opening $label."
        } catch (e: Exception) {
            "Could not open $label: ${e.message}"
        }
    }
}

private const val CONFIRM_TIMEOUT_MS = 60_000L
