package com.jarvis.android.actions

import android.provider.Settings

/** Settings pages the assistant can open by name (Android does not let an app flip most of them itself). */
internal val SETTINGS_PAGES: Map<String, String> = mapOf(
    "wifi" to Settings.ACTION_WIFI_SETTINGS,
    "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
    "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
    "display" to Settings.ACTION_DISPLAY_SETTINGS,
    "sound" to Settings.ACTION_SOUND_SETTINGS,
    "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
    "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
    "apps" to Settings.ACTION_APPLICATION_SETTINGS,
    "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
    "nfc" to Settings.ACTION_NFC_SETTINGS,
    "date" to Settings.ACTION_DATE_SETTINGS,
    "language" to Settings.ACTION_LOCALE_SETTINGS,
    "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
    "security" to Settings.ACTION_SECURITY_SETTINGS,
    "network" to Settings.ACTION_WIRELESS_SETTINGS,
)

private val PAGE_ALIASES = mapOf(
    "wi-fi" to "wifi", "wi fi" to "wifi", "internet" to "network", "reseau" to "network", "réseau" to "network",
    "mode avion" to "airplane", "avion" to "airplane", "airplane mode" to "airplane",
    "ecran" to "display", "écran" to "display", "luminosite" to "display", "luminosité" to "display", "brightness" to "display",
    "son" to "sound", "volume" to "sound", "batterie" to "battery", "economiseur" to "battery",
    "localisation" to "location", "gps" to "location", "applications" to "apps", "stockage" to "storage",
    "date et heure" to "date", "langue" to "language", "accessibilite" to "accessibility", "accessibilité" to "accessibility",
    "securite" to "security", "sécurité" to "security",
)

/** The Settings intent action for a page name (French or English), or null when unknown. */
internal fun settingsActionFor(page: String): String? {
    val key = page.trim().lowercase()
    return SETTINGS_PAGES[PAGE_ALIASES[key] ?: key]
}

/** Media keys the assistant can press. */
internal val MEDIA_KEYS: Map<String, Int> = mapOf(
    "play_pause" to android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    "play" to android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
    "pause" to android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
    "next" to android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
    "previous" to android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    "stop" to android.view.KeyEvent.KEYCODE_MEDIA_STOP,
)

/** Brightness percent (0-100) as the 0-255 system value; never fully black so the screen stays usable. */
internal fun brightnessToSystemValue(percent: Int): Int = (percent.coerceIn(0, 100) * 255 / 100).coerceAtLeast(5)

internal fun systemValueToPercent(value: Int): Int = (value.coerceIn(0, 255) * 100 / 255)
