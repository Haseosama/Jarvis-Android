package com.jarvis.android.core

import com.jarvis.android.i18n.tr
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** What the picker needs to know about an audio device. */
internal data class AudioDeviceChoice(val key: String, val label: String)

/** Stable identity of a device across reconnections (the numeric id changes, the rest does not). */
internal fun deviceKey(type: Int, name: String, address: String): String = "$type|$name|$address"

internal fun deviceTypeLabel(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> tr("Micro du téléphone")
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> tr("Écouteur du téléphone")
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> tr("Haut-parleur du téléphone")
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> tr("Casque filaire")
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> tr("Écouteurs filaires")
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> tr("Bluetooth (appel)")
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> tr("Bluetooth (média)")
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE"
    AudioDeviceInfo.TYPE_HEARING_AID -> tr("Appareil auditif")
    AudioDeviceInfo.TYPE_TELEPHONY -> tr("Téléphonie")
    else -> tr("Autre")
}

/** "Type : nom", or just the type when the name adds nothing. */
internal fun deviceLabel(type: Int, name: String): String {
    val kind = deviceTypeLabel(type)
    val clean = name.trim()
    return if (clean.isEmpty() || clean.equals(kind, ignoreCase = true)) kind else "$kind : $clean"
}

/** The saved device among those currently connected, or null (automatic routing) when absent. */
internal fun <T> pickByKey(devices: List<T>, savedKey: String, keyOf: (T) -> String): T? =
    if (savedKey.isBlank()) null else devices.firstOrNull { keyOf(it) == savedKey }

/**
 * The user's choice of microphone and speaker. Empty means automatic routing. A saved device that
 * is not connected right now is ignored, so unplugging a headset falls back to the phone.
 */
internal object AudioRoute {
    @Volatile var inputKey: String = ""
    @Volatile var outputKey: String = ""

    private fun devices(context: Context, flag: Int): List<AudioDeviceInfo> =
        context.getSystemService(AudioManager::class.java).getDevices(flag).toList()

    fun input(context: Context): AudioDeviceInfo? =
        pickByKey(devices(context, AudioManager.GET_DEVICES_INPUTS), inputKey) { deviceKey(it.type, it.productName.toString(), it.address) }

    fun output(context: Context): AudioDeviceInfo? =
        pickByKey(devices(context, AudioManager.GET_DEVICES_OUTPUTS), outputKey) { deviceKey(it.type, it.productName.toString(), it.address) }

    fun choices(context: Context, inputs: Boolean): List<AudioDeviceChoice> =
        devices(context, if (inputs) AudioManager.GET_DEVICES_INPUTS else AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY }
            .map { AudioDeviceChoice(deviceKey(it.type, it.productName.toString(), it.address), deviceLabel(it.type, it.productName.toString())) }
            .distinctBy { it.key }
}
