package com.jarvis.android.core

import android.content.Context
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri

/*
 * Jarvis in a car (Android Auto).
 *
 * What went wrong there: the car's speakers are reached through Bluetooth or USB, which the app treated like a headset, so the microphone
 * stayed open while Jarvis spoke; it heard its own voice filling the cabin and answered it. The audio focus taken for the whole session
 * stopped the car's music for good; and the microphone opened in "communication" mode can make the phone switch the Bluetooth link to a
 * call, which cuts the sound.
 *
 * In car mode: the microphone is muted while Jarvis speaks (with a longer tail: the cabin echoes and Bluetooth adds delay), the phone's own
 * microphone is used in a mode that does not touch the Bluetooth link, and the audio focus is a light one (the music is lowered, not stopped)
 * taken only while Jarvis speaks, so the music comes back by itself.
 */

/** 0 = automatic (a car is detected), 1 = always in car mode, 2 = never. */
internal const val CAR_AUTO = 0
internal const val CAR_ON = 1
internal const val CAR_OFF = 2

/** The car mode from the setting and what was detected: the phone's own car mode, or an Android Auto projection. */
internal fun carModeActive(setting: Int, uiModeCar: Boolean, projection: Boolean): Boolean = when (setting) {
    CAR_ON -> true
    CAR_OFF -> false
    else -> uiModeCar || projection
}

/** The audio focus Jarvis asks for: a light one (the others are lowered or paused for a moment and come back) in a car, the lasting one elsewhere. */
internal fun focusGainFor(car: Boolean): Int = if (car) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else AudioManager.AUDIOFOCUS_GAIN

/** How long the microphone stays muted after the last word of Jarvis: the echo of a room, or of a cabin with a Bluetooth delay. */
internal fun echoTailMs(car: Boolean): Long = if (car) 1_100L else 350L

/** How long a light focus is kept after the last sound, so a sentence and the next one do not make the music jump. */
internal const val CAR_FOCUS_TAIL_MS = 1_500L

/** True when the voice comes out of something the microphone can hear: the loudspeaker, or anything in a car (its speakers are in the room). */
internal fun voiceReachesMicrophone(outputTypes: List<Int>, chosenType: Int?, car: Boolean): Boolean {
    if (car) return true
    if (chosenType != null) return chosenType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    return outputTypes.none { isPrivateOutputType(it) }
}

/** Outputs that only the user hears: a headset, earphones, hearing aids, Bluetooth audio. */
internal fun isPrivateOutputType(type: Int): Boolean = when (type) {
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_HEARING_AID -> true
    else -> false
}

internal object CarAudio {
    /** Mirror of the setting (see [CAR_AUTO]). */
    @Volatile var setting = CAR_AUTO

    private const val CONNECTION_URI = "content://androidx.car.app.connection"
    private const val STATE_COLUMN = "CarConnectionState"
    private const val STATE_PROJECTION = 2

    fun uiModeCar(context: Context): Boolean = try {
        context.getSystemService(android.app.UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_CAR
    } catch (_: Exception) {
        false
    }

    /** Whether Android Auto is projecting on a car screen, asked the way Google's car library does (a provider of the Android Auto app). */
    fun projection(context: Context): Boolean = try {
        context.contentResolver.query(Uri.parse(CONNECTION_URI), arrayOf(STATE_COLUMN), null, null, null)?.use { c ->
            val column = c.getColumnIndex(STATE_COLUMN)
            c.moveToNext() && column >= 0 && c.getInt(column) == STATE_PROJECTION
        } ?: false
    } catch (_: Exception) {
        false
    }

    fun active(context: Context): Boolean = carModeActive(setting, uiModeCar(context), projection(context))

    /** A short description for the activity log. */
    fun describe(context: Context): String =
        "réglage ${when (setting) { CAR_ON -> "activé"; CAR_OFF -> "désactivé"; else -> "automatique" }}, mode voiture d’Android : ${if (uiModeCar(context)) "oui" else "non"}, Android Auto : ${if (projection(context)) "oui" else "non"}"
}
