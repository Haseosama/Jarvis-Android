package com.jarvis.android.text

import java.text.Normalizer
import java.util.Locale

/** Lower case, no accents, no punctuation, single spaces: "Éteins  la lampe !" becomes "eteins la lampe". */
internal fun normalize(text: String): String {
    val folded = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    return folded.replace('’', ' ').replace('\'', ' ').replace(Regex("[^a-z0-9%+ ]"), " ").replace(Regex("\\s+"), " ").trim()
}
