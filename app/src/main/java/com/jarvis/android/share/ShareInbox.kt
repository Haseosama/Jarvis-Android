package com.jarvis.android.share

import com.jarvis.android.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What another app shared with Jarvis: a text and/or the name of a file that was attached to the chat. */
internal data class Shared(val text: String?, val fileName: String?)

/** Holds one shared item until the user picks what to do with it in the chat. Nothing is sent by itself. */
internal class ShareInbox {
    private val _current = MutableStateFlow<Shared?>(null)
    val current: StateFlow<Shared?> = _current.asStateFlow()

    fun offer(text: String?, fileName: String?) {
        val clean = text?.trim()?.take(MAX_SHARED_CHARS)?.ifEmpty { null }
        _current.value = if (clean == null && fileName == null) null else Shared(clean, fileName)
    }

    fun clear() {
        _current.value = null
    }

    companion object {
        const val MAX_SHARED_CHARS = 8_000
    }
}

internal enum class ShareAction { SUMMARIZE, TRANSLATE, EXPLAIN }

/** The message sent to the chat for a shared item and the action the user tapped. Null when there is nothing to work on. */
internal fun sharePrompt(action: ShareAction, shared: Shared): String? {
    val text = shared.text
    return if (text != null) {
        val head = when (action) {
            ShareAction.SUMMARIZE -> tr("Résume ce texte en quelques phrases :")
            ShareAction.TRANSLATE -> tr("Traduis ce texte en français :")
            ShareAction.EXPLAIN -> tr("Explique-moi ce texte simplement :")
        }
        head + "\n\n" + text + if (shared.fileName != null) "\n\n" + tr("Analyse aussi le fichier joint.") else ""
    } else if (shared.fileName != null) {
        when (action) {
            ShareAction.SUMMARIZE -> tr("Analyse le fichier joint et résume-le.")
            ShareAction.TRANSLATE -> tr("Analyse le fichier joint et traduis son contenu en français.")
            ShareAction.EXPLAIN -> tr("Analyse le fichier joint et explique-le simplement.")
        }
    } else {
        null
    }
}
