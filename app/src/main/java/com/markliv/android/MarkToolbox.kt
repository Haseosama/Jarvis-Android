package com.markliv.android

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

class MarkToolbox(private val context: Context) {

    fun execute(name: String, args: JSONObject): String {
        return when (name) {
            "adjust_volume" -> adjustVolume(args.optInt("level", 50))
            "launch_app" -> launchApp(args.optString("package_name", ""))
            "open_url" -> openUrl(args.optString("url", ""))
            "get_device_status" -> getDeviceStatus()
            else -> "Erreur : Outil inconnu $name"
        }
    }

    private fun adjustVolume(level: Int): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetVolume = (level * maxVolume) / 100
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, AudioManager.FLAG_SHOW_UI)
        return "Volume réglé à $level% ($targetVolume/$maxVolume)"
    }

    private fun launchApp(packageName: String): String {
        if (packageName.isBlank()) return "Erreur : Nom de package manquant."
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        return if (intent != null) {
            context.startActivity(intent)
            "Application $packageName lancée avec succès."
        } else {
            "Erreur : Impossible de trouver l'application $packageName."
        }
    }

    private fun openUrl(url: String): String {
        if (url.isBlank()) return "Erreur : URL manquante."
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "URL $url ouverte."
        } catch (e: Exception) {
            "Erreur lors de l'ouverture de l'URL : ${e.message}"
        }
    }

    private fun getDeviceStatus(): String {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        return JSONObject()
            .put("battery", "$batteryLevel%")
            .put("time", time)
            .put("status", "En ligne")
            .toString()
    }

    companion object {
        fun getToolDeclarations(): org.json.JSONArray {
            return org.json.JSONArray().apply {
                put(JSONObject().put("name", "adjust_volume")
                    .put("description", "Règle le volume multimédia du téléphone (0-100).")
                    .put("parameters", JSONObject().put("type", "object")
                        .put("properties", JSONObject().put("level", JSONObject().put("type", "integer").put("description", "Le niveau de volume de 0 à 100.")))
                        .put("required", org.json.JSONArray().put("level"))))

                put(JSONObject().put("name", "launch_app")
                    .put("description", "Lance une application installée via son nom de package (ex: com.google.android.youtube).")
                    .put("parameters", JSONObject().put("type", "object")
                        .put("properties", JSONObject().put("package_name", JSONObject().put("type", "string").put("description", "Le nom complet du package Android.")))
                        .put("required", org.json.JSONArray().put("package_name"))))

                put(JSONObject().put("name", "open_url")
                    .put("description", "Ouvre une URL dans le navigateur ou une recherche Google.")
                    .put("parameters", JSONObject().put("type", "object")
                        .put("properties", JSONObject().put("url", JSONObject().put("type", "string").put("description", "L'URL complète à ouvrir.")))
                        .put("required", org.json.JSONArray().put("url"))))

                put(JSONObject().put("name", "get_device_status")
                    .put("description", "Récupère l'état actuel du téléphone (batterie, heure).")
                    .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))
            }
        }
    }
}
