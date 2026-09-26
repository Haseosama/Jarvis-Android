package com.jarvis.android.parking

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvis.android.JarvisApp
import com.jarvis.android.i18n.tr
import com.jarvis.android.weather.LocationOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/*
 * "Retiens où je me suis garé" / "où est ma voiture ?": the car's spot, with how far and which way it is, and a walking
 * route. Optionally saved by itself when the phone leaves the car's Bluetooth (the moment the engine is off and the
 * driver walks away) — Android still delivers that event to an app that is not running.
 */

@Serializable
internal data class ParkedCar(val latitude: Double, val longitude: Double, val at: Long, val label: String = "", val note: String = "", val auto: Boolean = false)

@Serializable
internal data class ParkingData(
    val car: ParkedCar? = null,
    val carBluetoothAddress: String = "",
    val carBluetoothName: String = "",
    val autoSave: Boolean = false,
)

internal class ParkingStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun load(): ParkingData = try {
        if (file.exists()) json.decodeFromString<ParkingData>(file.readText()) else ParkingData()
    } catch (_: Exception) {
        ParkingData()
    }

    @Synchronized
    fun update(change: (ParkingData) -> ParkingData): ParkingData {
        val next = change(load())
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(next))
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Exception) {
        }
        return next
    }
}

/** How old a known position may be for the car: after walking away for a minute, an older one is where you were, not where you are. */
internal const val FRESH_FIX_MS = 30_000L

private val DIRECTIONS = listOf("au nord", "au nord-est", "à l'est", "au sud-est", "au sud", "au sud-ouest", "à l'ouest", "au nord-ouest")

/** Which way [to] is from [from], in words: "au nord-est". */
internal fun directionWord(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): String {
    val φ1 = Math.toRadians(fromLat)
    val φ2 = Math.toRadians(toLat)
    val Δλ = Math.toRadians(toLon - fromLon)
    val bearing = (Math.toDegrees(atan2(sin(Δλ) * cos(φ2), cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ))) + 360) % 360
    return DIRECTIONS[((bearing + 22.5) / 45).toInt() % 8]
}

/** "à 350 m", "à 1,2 km" */
internal fun distanceWords(km: Double): String =
    if (km < 1) "à ${((km * 1000) / 10).toInt() * 10} m" else "à ${String.format(java.util.Locale.FRANCE, "%.1f", km)} km"

/** "il y a 25 min", "il y a 3 h", "il y a 2 jours" */
internal fun sinceWords(at: Long, now: Long): String {
    val min = ((now - at) / 60_000).coerceAtLeast(0)
    return when {
        min < 60 -> "il y a $min min"
        min < 48 * 60 -> "il y a ${min / 60} h"
        else -> "il y a ${min / (24 * 60)} jours"
    }
}

internal object ParkingSaver {
    private const val CHANNEL = "jarvis_parking"

    fun store(context: Context): ParkingStore = (context.applicationContext as JarvisApp).container.parkingStore

    /** Saves the phone's current position as the car's spot. The message to say, success or not. */
    suspend fun saveHere(context: Context, note: String, auto: Boolean): String {
        // A fresh position: a 20-minute-old one could be where the car was two streets ago.
        val fix = when (val outcome = com.jarvis.android.weather.locate(context, maxAgeMs = FRESH_FIX_MS)) {
            is LocationOutcome.Found -> outcome
            LocationOutcome.NoPermission -> return "Je n'ai pas accès à la position : autorisez-la dans Paramètres > Position (météo)."
            LocationOutcome.ServicesOff -> return "La localisation du téléphone est désactivée."
            LocationOutcome.Unavailable -> return "Position introuvable pour le moment."
        }
        val label = fix.place.orEmpty()
        store(context).update { it.copy(car = ParkedCar(fix.fix.latitude, fix.fix.longitude, System.currentTimeMillis(), label, note.trim().take(120), auto)) }
        val accuracy = if (fix.fix.accuracyMeters > 80) " (position approximative, à ${fix.fix.accuracyMeters.toInt()} m près)" else ""
        return "Place de la voiture enregistrée${if (label.isNotEmpty()) " ($label)" else ""}$accuracy."
    }

    fun notifySaved(context: Context, text: String) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, tr("Voiture garée"), NotificationManager.IMPORTANCE_LOW))
            NotificationManagerCompat.from(context).notify(7_601, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(if (text.startsWith("Place de la voiture enregistrée")) tr("Voiture garée") else tr("Place de la voiture non enregistrée"))
                .setContentText(text)
                .setAutoCancel(true)
                .build())
        } catch (_: SecurityException) {
        }
    }
}

/** Saves the spot when the phone leaves the chosen car's Bluetooth, if the user switched that on. */
class CarBluetoothReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission") // reading the address needs no permission; the name is never read here
    override fun onReceive(context: Context, intent: Intent) {
        val connected = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
        if (!connected && intent.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        val device: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
        val app = context.applicationContext
        val data = ParkingSaver.store(app).load()
        if (data.carBluetoothAddress.isEmpty() || !device.address.equals(data.carBluetoothAddress, ignoreCase = true)) return
        // Driving mode: on when the phone joins the car, off when it leaves (only if it was switched on by the car).
        val driving = com.jarvis.android.driving.DrivingMode
        if (connected) {
            if (driving.store(app).load().autoStart) driving.start(app, byCar = true)
            return
        }
        if (driving.active && driving.startedByCar) driving.stop(app)
        if (!data.autoSave) return
        val result = goAsync()
        Thread {
            try {
                val message = runBlocking { ParkingSaver.saveHere(app, "", auto = true) }
                ParkingSaver.notifySaved(app, message)
            } finally {
                result.finish()
            }
        }.start()
    }
}

/** True when Jarvis may list the paired Bluetooth devices by name (Android 12+ asks for "Nearby devices"). */
internal fun canListBluetooth(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
