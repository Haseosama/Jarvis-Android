package com.jarvis.android.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Interface language. The source text of the app is French; [tr] and [trf] look the English text up in [ENGLISH]
 * and fall back to the French text when there is none. [code] is Compose state, so every screen that calls [tr]
 * redraws by itself when the language changes.
 */
object Lang {
    const val FRENCH = "fr"
    const val ENGLISH_CODE = "en"

    var code: String by mutableStateOf(FRENCH)

    val isEnglish: Boolean get() = code == ENGLISH_CODE

    private const val PREFS = "jarvis_ui"

    /** Reads the saved language. A plain preferences file is used because this runs on the main thread before the first frame. */
    fun load(context: android.content.Context) {
        code = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString("language", FRENCH) ?: FRENCH
    }

    /** Switches the language now and remembers it. */
    fun set(context: android.content.Context, value: String) {
        code = value
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString("language", value).apply()
    }
}

/** The text in the current interface language. */
fun tr(fr: String): String = if (Lang.isEnglish) ENGLISH[fr] ?: fr else fr

/** Like [tr] for a text with placeholders `{0}`, `{1}`… filled with [args]. */
fun trf(fr: String, vararg args: Any?): String {
    var text = tr(fr)
    args.forEachIndexed { i, arg -> text = text.replace("{$i}", arg.toString()) }
    return text
}

/** The French source text of an English text (or the text itself): a stable key that does not change with the language. */
fun frenchOf(text: String): String = ENGLISH.entries.firstOrNull { it.value == text }?.key ?: text
