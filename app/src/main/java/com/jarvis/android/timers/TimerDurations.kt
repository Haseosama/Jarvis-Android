package com.jarvis.android.timers

import java.util.Locale

object TimerDurations {
    const val MAX_SECONDS = 86_400L

    fun parse(value: String): Long {
        val input = value.trim().lowercase(Locale.ROOT)
        require(input.isNotEmpty()) { "Indiquez une durée, par exemple « 10 minutes » ou « 1h30 »." }
        val seconds = parseCompact(input)
            ?: parseSpelled(input)
            ?: parseBareMinutes(input)
            ?: throw IllegalArgumentException("Durée incomprise : utilisez par exemple « 45 secondes », « 10 minutes » ou « 1h30 ».")
        require(seconds in 1..MAX_SECONDS) { "La durée doit être comprise entre 1 seconde et 24 heures." }
        return seconds
    }

    fun format(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts += "$h h"
        if (m > 0) parts += "$m min"
        if (s > 0 || parts.isEmpty()) parts += "$s s"
        return parts.joinToString(" ")
    }

    private fun parseCompact(input: String): Long? {
        val match = Regex("^(\\d+)\\s*h\\s*(\\d+)?\\s*m?$").find(input) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: return null
        val minutes = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L
        if (minutes > 59) return null
        return hours * 3600 + minutes * 60
    }

    private fun parseSpelled(input: String): Long? {
        val hoursMinutes = Regex("^(\\d+)\\s*heures?\\s+(\\d+)\\s*(?:m(?:in(?:ute)?s?)?)?\\s*$").find(input)
        if (hoursMinutes != null) {
            val hours = hoursMinutes.groupValues[1].toLongOrNull() ?: return null
            val minutes = hoursMinutes.groupValues[2].toLongOrNull() ?: return null
            if (minutes > 59) return null
            return hours * 3600 + minutes * 60
        }
        val hoursOnly = Regex("^(\\d+)\\s*heures?\\s*$").find(input)
        if (hoursOnly != null) {
            val hours = hoursOnly.groupValues[1].toLongOrNull() ?: return null
            return hours * 3600
        }
        val minutesOnly = Regex("^(\\d+)\\s*m(?:in(?:ute)?s?)?\\s*$").find(input)
        if (minutesOnly != null) {
            val minutes = minutesOnly.groupValues[1].toLongOrNull() ?: return null
            return minutes * 60
        }
        val secondsOnly = Regex("^(\\d+)\\s*s(?:ec(?:onde)?s?)?\\s*$").find(input)
        if (secondsOnly != null) {
            return secondsOnly.groupValues[1].toLongOrNull()
        }
        return null
    }

    private fun parseBareMinutes(input: String): Long? {
        val number = Regex("^(\\d+)\\s*$").find(input)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return number * 60
    }
}
