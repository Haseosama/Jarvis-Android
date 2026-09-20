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
        "Control phone settings: set_volume (0-100, asks the user to confirm), set_brightness (0-100, needs the 'modify system settings' permission), " +
            "flashlight (value 1 on / 0 off), media (command: play_pause, play, pause, next, previous, stop), lock_screen, take_screenshot, " +
            "or open a settings page (open_settings with page: wifi, bluetooth, airplane, display, sound, battery, location, apps, storage, nfc, date, language, accessibility, security, network). " +
            "open_wifi and open_brightness also work."
    override val parameters = objectSchema(required = listOf("action")) {
        string("action", "One of: set_volume, set_brightness, flashlight, media, lock_screen, take_screenshot, open_settings, open_wifi, open_brightness.")
        integer("value", "set_volume / set_brightness: percent 0-100. flashlight: 1 or 0.")
        string("page", "For open_settings: the settings page name.")
        string("command", "For media: play_pause, play, pause, next, previous or stop.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        return when (args.stringArg("action")) {
            "set_volume" -> setVolume(args.intArg("value", -1), ctx)
            "open_wifi" -> openPanel(ctx, Settings.ACTION_WIFI_SETTINGS, "Wi-Fi settings")
            "open_brightness" -> openPanel(ctx, Settings.ACTION_DISPLAY_SETTINGS, "display settings")
            "open_settings" -> {
                val page = args.stringArg("page")
                val action = settingsActionFor(page)
                    ?: return "Page inconnue : ${SETTINGS_PAGES.keys.joinToString(", ")}."
                openPanel(ctx, action, "les réglages « $page »")
            }
            "set_brightness" -> setBrightness(args.intArg("value", -1), ctx)
            "flashlight" -> flashlight(args.intArg("value", -1) == 1, args.intArg("value", -1) in 0..1, ctx)
            "media" -> media(args.stringArg("command"), ctx)
            "lock_screen", "take_screenshot" -> globalAction(args.stringArg("action"))
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

    private suspend fun setBrightness(percent: Int, ctx: JarvisContainer): String {
        if (percent !in 0..100) return "Donnez une luminosité entre 0 et 100."
        val context = ctx.appContext
        if (!Settings.System.canWrite(context)) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
            return "Je n’ai pas l’autorisation de modifier les réglages système. Je viens d’ouvrir l’écran pour l’accorder : l’utilisateur doit l’activer lui-même, puis redemander."
        }
        val resolver = context.contentResolver
        val previousValue = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val previousMode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, brightnessToSystemValue(percent))
        ctx.undoManager.push(UndoEntry("luminosité") {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, previousValue)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, previousMode)
            "Luminosité rétablie."
        })
        return "Luminosité réglée à $percent %."
    }

    private suspend fun flashlight(on: Boolean, valid: Boolean, ctx: JarvisContainer): String {
        if (!valid) return "Indiquez value = 1 pour allumer la lampe, 0 pour l’éteindre."
        val manager = ctx.appContext.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Cet appareil n’a pas de lampe torche."
        return try {
            manager.setTorchMode(id, on)
            ctx.undoManager.push(UndoEntry("lampe torche") {
                manager.setTorchMode(id, !on)
                if (on) "Lampe éteinte." else "Lampe rallumée."
            })
            if (on) "Lampe torche allumée." else "Lampe torche éteinte."
        } catch (e: Exception) {
            "Impossible de commander la lampe : ${e.message}. Elle est peut-être utilisée par la caméra."
        }
    }

    private fun media(command: String, ctx: JarvisContainer): String {
        val key = MEDIA_KEYS[command.trim().lowercase()]
            ?: return "Commande inconnue : ${MEDIA_KEYS.keys.joinToString(", ")}."
        val am = ctx.appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
        return "Commande média envoyée : $command. Je ne peux pas vérifier qu’une application l’a prise en compte."
    }

    private fun globalAction(action: String): String {
        val service = com.jarvis.android.device.JarvisAccessibilityService.instance
            ?: return "Cette action demande le contrôle du téléphone (service d’accessibilité), qui n’est pas activé."
        return when (val r = service.global(action)) {
            com.jarvis.android.device.ActionResult.Done -> if (action == "lock_screen") "Écran verrouillé." else "Capture d’écran demandée. Je ne peux pas vérifier qu’elle a été enregistrée."
            is com.jarvis.android.device.ActionResult.Failed -> r.reason
        }
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
