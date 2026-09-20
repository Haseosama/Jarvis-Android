package com.jarvis.android.memory

import com.jarvis.android.JarvisContainer
import com.jarvis.android.reminders.ReminderService
import java.time.LocalDate
import java.time.ZoneId

/**
 * Glue between the stores and the pure briefing logic: prepares the prompt block when a voice
 * session starts, and once the briefing has been asked for, records it so it is given once a day.
 */
internal class BriefingCoordinator(private val container: JarvisContainer) {
    @Volatile private var pending = false

    suspend fun prepare(): String {
        pending = false
        if (!container.configStore.snapshotBriefingEnabled()) return ""
        val block = buildBriefingBlock(
            BriefingInputs(
                today = LocalDate.now(),
                lastBriefingDate = container.configStore.snapshotLastBriefingDate(),
                lastSession = container.memoryManager.peekLastSession(),
                reminders = remindersToday(
                    ReminderService.list(container.appContext), System.currentTimeMillis(), ZoneId.systemDefault(),
                ),
            )
        )
        pending = block.isNotEmpty()
        return block
    }

    /** True once, right after a session that asked for a briefing became ready. */
    suspend fun consumeTrigger(): Boolean {
        if (!pending) return false
        pending = false
        container.configStore.setLastBriefingDate(LocalDate.now().toString())
        container.memoryManager.popLastSession()
        return true
    }
}
