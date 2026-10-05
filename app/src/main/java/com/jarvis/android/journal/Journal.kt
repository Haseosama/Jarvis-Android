package com.jarvis.android.journal

import android.content.Context
import java.io.File

/** The journal on the phone (app's own files). Noting never fails the alert that notes it. */
internal object Journal {
    private val lock = Any()
    private fun file(c: Context) = File(c.applicationContext.filesDir, "week_journal.json")
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("week_journal", Context.MODE_PRIVATE)

    /** An alert Jarvis just gave about [kind] (one of [AlertKind]). */
    fun alert(c: Context, kind: String, text: String) = add(c, JournalEntry(System.currentTimeMillis(), kind, text.replace(Regex("\\s+"), " ").trim().take(200)))

    /** Driving mode went on: the start kept until it goes off, even if the app is stopped in between. */
    fun driveStarted(c: Context) {
        try { prefs(c).edit().putLong("drive_start", System.currentTimeMillis()).apply() } catch (_: Exception) {}
    }

    /** Driving mode went off: the drive noted, unless it lasted under two minutes or more than half a day (a start never closed). */
    fun driveStopped(c: Context) {
        try {
            val p = prefs(c)
            val start = p.getLong("drive_start", 0L)
            p.edit().remove("drive_start").apply()
            val now = System.currentTimeMillis()
            if (start > 0 && now - start in 120_000L..12 * 3_600_000L) add(c, JournalEntry(start, DRIVE, endAt = now))
        } catch (_: Exception) {
        }
    }

    /** The entries since [fromMs], oldest first. */
    fun since(c: Context, fromMs: Long): List<JournalEntry> = synchronized(lock) {
        try { decodeJournal(file(c).takeIf { it.exists() }?.readText()).filter { it.at >= fromMs } } catch (_: Exception) { emptyList() }
    }

    private fun add(c: Context, entry: JournalEntry) {
        synchronized(lock) {
            try {
                val f = file(c)
                val next = appendEntry(decodeJournal(f.takeIf { it.exists() }?.readText()), entry, System.currentTimeMillis())
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(encodeJournal(next))
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            } catch (_: Exception) {
            }
        }
    }
}
