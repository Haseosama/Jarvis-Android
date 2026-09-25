package com.jarvis.android.places

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.jarvis.android.JarvisApp
import com.jarvis.android.core.SpokenAlert
import com.jarvis.android.i18n.tr

/** What stops location reminders from working, said plainly, or null when everything is in place. */
internal fun placePermissionProblem(context: Context): String? {
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
        return "La position précise n'est pas autorisée. L'utilisateur doit l'autoriser dans Réglages de Jarvis > Rappels selon le lieu."
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
        return "La position n'est pas autorisée « tout le temps » : sans cela, Android ne prévient pas Jarvis quand il est fermé. " +
            "L'utilisateur doit choisir « Toujours autoriser » dans Réglages de Jarvis > Rappels selon le lieu."
    }
    return null
}

internal object Geofences {
    private const val REQUEST = 73_000
    private const val CHANNEL = "jarvis_place_reminders"

    fun store(context: Context): PlaceStore = (context.applicationContext as JarvisApp).container.placeStore

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST, Intent(context, GeofenceReceiver::class.java).setAction(GeofenceReceiver.ACTION),
            // Mutable: Play services fills in which geofence fired.
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
        )

    /**
     * Hands every stored reminder to Android again (after a change, at start-up and after a reboot, which clears them).
     * Registering the same ids again replaces them, so this is safe to call any time.
     */
    @SuppressLint("MissingPermission") // checked by placePermissionProblem just before
    fun registerAll(context: Context) {
        if (placePermissionProblem(context) != null) return
        val client = LocationServices.getGeofencingClient(context)
        val reminders = store(context).load().reminders
        client.removeGeofences(pendingIntent(context)).addOnCompleteListener {
            if (reminders.isEmpty()) return@addOnCompleteListener
            val fences = reminders.map { r ->
                Geofence.Builder()
                    .setRequestId(r.id.toString())
                    .setCircularRegion(r.latitude, r.longitude, r.radiusM.toFloat())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(if (r.trigger == PlaceTrigger.ARRIVE) Geofence.GEOFENCE_TRANSITION_ENTER else Geofence.GEOFENCE_TRANSITION_EXIT)
                    .build()
            }
            val request = GeofencingRequest.Builder()
                // No "fire at once because you are already inside": a reminder set at home must wait for the next arrival.
                .setInitialTrigger(0)
                .addGeofences(fences)
                .build()
            try {
                client.addGeofences(request, pendingIntent(context))
                    .addOnFailureListener { Log.w("JarvisPlaces", "Zones non enregistrées : ${it.message}") }
            } catch (e: SecurityException) {
                Log.w("JarvisPlaces", "Zones non enregistrées : permission retirée.")
            }
        }
    }

    /** Says and shows a reminder that fired, starts its task if it has one, and deletes it if it was a one-off. */
    fun fire(context: Context, id: Int) {
        val store = store(context)
        val r = store.load().reminders.firstOrNull { it.id == id } ?: return
        val now = System.currentTimeMillis()
        if (!shouldFire(r, now)) return
        if (r.repeat) store.markFired(r.id, now) else store.remove(listOf(r.id))
        notify(context, r, r.text)
        SpokenAlert.announce(context, "Rappel : ${r.text}")
        if (r.task.isNotBlank()) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<PlaceTaskWorker>().setInputData(workDataOf("task" to r.task, "id" to r.id, "place" to r.placeName, "leave" to (r.trigger == PlaceTrigger.LEAVE))).build(),
            )
        }
        if (!r.repeat) registerAll(context)
    }

    fun notify(context: Context, r: PlaceReminder, text: String, extraId: Int = 0) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, tr("Rappels selon le lieu"), NotificationManager.IMPORTANCE_HIGH))
            val title = (if (r.trigger == PlaceTrigger.ARRIVE) tr("En arrivant : ") else tr("En partant : ")) + r.placeName
            NotificationManagerCompat.from(context).notify(
                7_500 + r.id * 2 + extraId,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_map)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (_: SecurityException) {
        }
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w("JarvisPlaces", "Erreur de zone ${event.errorCode}")
            return
        }
        val ids = event.triggeringGeofences.orEmpty().mapNotNull { it.requestId.toIntOrNull() }
        val app = context.applicationContext
        val result = goAsync()
        Thread {
            try {
                ids.forEach { Geofences.fire(app, it) }
            } catch (_: Exception) {
                Log.e("JarvisPlaces", "Rappel de lieu non traité.")
            } finally {
                result.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION = "com.jarvis.android.places.GEOFENCE"
    }
}

/** Carries out a location reminder's task in the background (like a routine) and shows what came of it. */
class PlaceTaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val task = inputData.getString("task") ?: return Result.success()
        val id = inputData.getInt("id", 0)
        val container = (applicationContext as JarvisApp).container
        val outcome = try { container.agent.runOnce(task) } catch (e: Exception) { "Tâche non faite : ${e.message}" }
        val trigger = if (inputData.getBoolean("leave", false)) PlaceTrigger.LEAVE else PlaceTrigger.ARRIVE
        Geofences.notify(applicationContext, PlaceReminder(id, task, inputData.getString("place").orEmpty(), 0.0, 0.0, trigger = trigger), outcome, extraId = 1)
        return Result.success()
    }
}
