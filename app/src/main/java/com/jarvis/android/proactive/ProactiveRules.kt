package com.jarvis.android.proactive

import com.jarvis.android.i18n.trf
import com.jarvis.android.memory.BriefingInputs
import java.time.LocalDateTime

/** A snapshot of the phone's condition. */
internal data class DeviceStatus(
    val batteryPercent: Int,
    val charging: Boolean,
    val freeBytes: Long,
    val totalBytes: Long,
)

/** What has already been reported, so nothing is repeated. Persisted by the worker. */
internal data class ProactiveState(
    val batteryAlerted: Boolean = false,
    val lastStorageDay: String = "",
    val lastMorningDay: String = "",
)

internal sealed interface ProactiveAlert {
    data class Battery(val percent: Int) : ProactiveAlert
    data class Storage(val freeMegabytes: Long) : ProactiveAlert
    data class Morning(val text: String) : ProactiveAlert
}

internal const val BATTERY_LOW_PERCENT = 15
internal const val BATTERY_RECOVERED_PERCENT = 30
internal const val STORAGE_LOW_RATIO = 0.05
internal const val STORAGE_LOW_BYTES = 1_000_000_000L
internal const val MORNING_FROM_HOUR = 7
internal const val MORNING_UNTIL_HOUR = 11

/**
 * Decides which notifications to show now and the new state. Each condition is reported once:
 * the battery until it has recovered, storage once a day, the morning summary once a day and
 * only in the morning.
 */
internal fun evaluateProactive(
    status: DeviceStatus,
    state: ProactiveState,
    now: LocalDateTime,
    morningText: String?,
): Pair<List<ProactiveAlert>, ProactiveState> {
    val alerts = mutableListOf<ProactiveAlert>()
    var next = state
    val today = now.toLocalDate().toString()

    if (!status.charging && status.batteryPercent <= BATTERY_LOW_PERCENT && !state.batteryAlerted) {
        alerts += ProactiveAlert.Battery(status.batteryPercent)
        next = next.copy(batteryAlerted = true)
    } else if ((status.charging || status.batteryPercent >= BATTERY_RECOVERED_PERCENT) && state.batteryAlerted) {
        next = next.copy(batteryAlerted = false)
    }

    val storageLow = status.totalBytes > 0 &&
        (status.freeBytes < STORAGE_LOW_BYTES || status.freeBytes.toDouble() / status.totalBytes < STORAGE_LOW_RATIO)
    if (storageLow && state.lastStorageDay != today) {
        alerts += ProactiveAlert.Storage(status.freeBytes / 1_000_000)
        next = next.copy(lastStorageDay = today)
    }

    if (!morningText.isNullOrBlank() && state.lastMorningDay != today &&
        now.hour in MORNING_FROM_HOUR until MORNING_UNTIL_HOUR
    ) {
        alerts += ProactiveAlert.Morning(morningText)
        next = next.copy(lastMorningDay = today)
    }
    return alerts to next
}

/** The text of the morning notification, or null when there is nothing to say. */
internal fun morningNotificationText(inputs: BriefingInputs): String? {
    val lines = buildList {
        inputs.lastSession?.let { add(trf("Dernière session ({0}) : {1}", it.date, it.summary)) }
        if (inputs.reminders.isNotEmpty()) add(trf("Rappels du jour : {0}", inputs.reminders.joinToString(" ; ")))
    }
    return lines.joinToString("\n").ifEmpty { null }
}
