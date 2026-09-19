package com.jarvis.android.device

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/** The setting value with [component] present, keeping the services already enabled. */
internal fun withServiceEnabled(current: String?, component: String): String {
    val services = current.orEmpty().split(':').filter { it.isNotBlank() }
    return if (services.any { it.equals(component, ignoreCase = true) }) services.joinToString(":")
    else (services + component).joinToString(":")
}

internal fun isServiceEnabled(current: String?, component: String): Boolean =
    current.orEmpty().split(':').any { it.equals(component, ignoreCase = true) }

/**
 * Keeps the accessibility service switched on. Some phones (Xiaomi/MIUI in particular) switch it
 * off when the app is updated or closed. Android lets an app flip its own service back on only
 * if it was granted WRITE_SECURE_SETTINGS, which the user can allow once from a computer:
 * `adb shell pm grant <package> android.permission.WRITE_SECURE_SETTINGS`.
 * Without that grant this does nothing and the user has to re-enable the service by hand.
 */
internal object AccessibilityKeeper {
    fun canSelfEnable(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Returns true if the service is (now) enabled in the system settings. */
    fun ensureEnabled(context: Context): Boolean {
        val component = ComponentName(context, JarvisAccessibilityService::class.java).flattenToString()
        val resolver = context.contentResolver
        val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (isServiceEnabled(current, component) &&
            Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        ) return true
        if (!canSelfEnable(context)) return false
        return try {
            Settings.Secure.putString(
                resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, withServiceEnabled(current, component),
            )
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
