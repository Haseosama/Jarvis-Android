package com.jarvis.android.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume

internal sealed interface LocationOutcome {
    data class Found(val fix: Fix, val place: String?) : LocationOutcome
    data object NoPermission : LocationOutcome
    data object ServicesOff : LocationOutcome
    data object Unavailable : LocationOutcome
}

internal fun hasLocationPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * The phone's approximate position, only when the user has allowed location for Jarvis: a recent
 * known fix first, then a fresh one (up to 10 s). The position is used for one request and not stored.
 */
internal suspend fun locate(context: Context): LocationOutcome = withContext(Dispatchers.IO) {
    if (!hasLocationPermission(context)) return@withContext LocationOutcome.NoPermission
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    if (!LocationManagerCompat.isLocationEnabled(manager)) return@withContext LocationOutcome.ServicesOff
    val now = System.currentTimeMillis()
    try {
        val known = manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }
            .map { Fix(it.latitude, it.longitude, it.time, it.accuracy) }
        var fix = pickFreshest(known, now)
        if (fix == null) {
            val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .firstOrNull { manager.isProviderEnabled(it) } ?: return@withContext LocationOutcome.Unavailable
            val executor = Executors.newSingleThreadExecutor()
            val signal = CancellationSignal()
            val location = withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { signal.cancel() }
                    LocationManagerCompat.getCurrentLocation(manager, provider, signal, executor) { continuation.resume(it) }
                }
            }
            executor.shutdown()
            fix = location?.let { Fix(it.latitude, it.longitude, it.time, it.accuracy) }
        }
        if (fix == null) LocationOutcome.Unavailable else LocationOutcome.Found(fix, placeName(context, fix))
    } catch (_: SecurityException) {
        LocationOutcome.NoPermission
    }
}

/** A readable place name from Android's geocoder (the town), or null when it is not available. */
@Suppress("DEPRECATION")
private fun placeName(context: Context, fix: Fix): String? = try {
    if (!Geocoder.isPresent()) null
    else Geocoder(context, Locale.FRANCE).getFromLocation(fix.latitude, fix.longitude, 1)?.firstOrNull()
        ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
} catch (_: Exception) {
    null
}
