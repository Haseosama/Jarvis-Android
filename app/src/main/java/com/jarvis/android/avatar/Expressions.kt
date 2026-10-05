package com.jarvis.android.avatar

import java.text.Normalizer

/**
 * What the face shows while it speaks, read from the words being said: a smile on good news, a worried brow on bad news or a warning,
 * raised brows and wide eyes on surprise, lifted brows on a question. The lip-sync moves the mouth; this sets the face around it.
 */
internal enum class Expression { NEUTRAL, JOY, CONCERN, SURPRISE, QUESTION }

/** An expression and how strongly it shows (0..1). */
internal data class Feeling(val expression: Expression, val strength: Float) {
    companion object { val NEUTRAL = Feeling(Expression.NEUTRAL, 0f) }
}

/**
 * How an expression bends the face, in the units of [HoloAvatar]: [smile] lifts (or, negative, drops) the corners of the mouth,
 * [inner] raises the inner ends of the brows (worry), [brow] lifts both brows, [widen] opens the eyes wider, [jaw] parts the lips a
 * little when the voice pauses (surprise), [tilt] tips the head.
 */
internal data class ExpressionTargets(
    val smile: Float = 0f, val inner: Float = 0f, val brow: Float = 0f, val widen: Float = 0f, val jaw: Float = 0f, val tilt: Float = 0f,
)

internal fun expressionTargets(feeling: Feeling): ExpressionTargets {
    val k = feeling.strength.coerceIn(0f, 1f)
    return when (feeling.expression) {
        Expression.NEUTRAL -> ExpressionTargets()
        Expression.JOY -> ExpressionTargets(smile = 0.85f * k, brow = 0.12f * k, widen = 0.05f * k)
        Expression.CONCERN -> ExpressionTargets(smile = -0.45f * k, inner = 0.9f * k, brow = -0.12f * k, tilt = 0.03f * k)
        Expression.SURPRISE -> ExpressionTargets(smile = 0.05f * k, brow = 0.85f * k, widen = 0.9f * k, jaw = 0.22f * k)
        Expression.QUESTION -> ExpressionTargets(smile = 0.12f * k, brow = 0.45f * k, widen = 0.15f * k, tilt = 0.045f * k)
    }
}

/** Lower case, no accents, apostrophes made plain: "Désolé, c’est" → "desole, c'est". */
internal fun plainWords(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        .replace('’', '\'').replace('‘', '\'')

// French first, English after; matched as whole words (or whole phrases) in plainWords form.
private val JOY = listOf(
    "super", "genial", "geniale", "parfait", "parfaite", "parfaitement", "excellent", "excellente", "bravo", "felicitations",
    "ravi", "ravie", "content", "contente", "heureux", "heureuse", "avec plaisir", "bonne nouvelle", "bonnes nouvelles", "magnifique",
    "formidable", "fantastique", "merveilleux", "haha", "ha ha", "hihi", "drole", "joyeux", "joyeuse", "bon anniversaire",
    "bonne journee", "bonne soiree", "bonne nuit", "bon appetit", "bon week-end", "bonne chance", "chouette", "nickel",
    "beau temps", "ensoleille", "gagne", "reussi", "reussite", "merci", "volontiers", "enchante", "enchantee", "amusant",
    "great", "glad", "happy", "awesome", "wonderful", "congratulations", "perfect", "nice", "fantastic", "good news", "lovely",
    "delighted", "enjoy", "fun", "funny", "thanks", "thank you", "brilliant", "cool",
)
private val CONCERN = listOf(
    "desole", "desolee", "malheureusement", "helas", "attention", "prudence", "prudent", "danger", "dangereux", "alerte", "vigilance",
    "orage", "orages", "tempete", "canicule", "inondation", "accident", "panne", "erreur", "echec", "echoue", "impossible",
    "probleme", "dommage", "triste", "inquiet", "inquietant", "coupure", "retard", "annule", "annulee", "rappel conso", "grave",
    "malade", "douleur", "urgence", "urgent", "perdu", "perdue", "manque", "mauvaise nouvelle", "pas pu", "n'ai pas pu", "ne peux pas",
    "sorry", "unfortunately", "warning", "careful", "danger", "error", "failed", "problem", "sad", "bad news", "cannot", "can't",
    "couldn't", "afraid", "storm", "delay", "delayed", "cancelled", "canceled",
)
private val SURPRISE = listOf(
    "oh", "ah", "wow", "waouh", "whaou", "incroyable", "etonnant", "etonnante", "surprenant", "surprenante", "ca alors",
    "eh bien", "sans blague", "impressionnant", "impressionnante", "stupefiant", "quelle surprise",
    "amazing", "incredible", "surprising", "whoa", "unbelievable", "oh my", "impressive",
)

/** Phrases that only look like bad news: "pas de problème" is reassurance. Removed before the words are counted. */
private val REASSURING = listOf(
    "pas de probleme", "aucun probleme", "sans probleme", "pas de souci", "aucun souci", "sans souci", "pas d'erreur", "aucune erreur",
    "pas de retard", "aucun retard", "pas de danger", "aucun danger", "pas d'alerte", "aucune alerte", "pas d'orage", "pas de panne",
    "aucune panne", "pas de coupure", "aucune coupure", "rien de grave", "pas grave", "ne vous inquietez pas", "pas d'inquietude",
    "no problem", "no worries", "not a problem", "no error", "no delay", "no warning", "don't worry",
)

private fun pattern(words: List<String>) =
    Regex("(?<![\\p{L}\\p{N}])(" + words.distinct().sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}])")

private val JOY_RE = pattern(JOY)
private val CONCERN_RE = pattern(CONCERN)
private val SURPRISE_RE = pattern(SURPRISE)
private val REASSURING_RE = pattern(REASSURING)

/**
 * The feeling of one sentence (or the part of it heard so far), or null when nothing in it shows one. [ended] is the sentence's last
 * mark when it is complete ('.', '!', '?'…): a question with no other feeling lifts the brows; an exclamation strengthens.
 */
internal fun feelingOf(sentence: String, ended: Char? = null): Feeling? {
    val words = plainWords(sentence)
    val reassured = REASSURING_RE.containsMatchIn(words)
    val plain = if (reassured) REASSURING_RE.replace(words, " ") else words
    val joy = JOY_RE.findAll(plain).count() + if (reassured) 1 else 0
    val concern = CONCERN_RE.findAll(plain).count()
    val surprise = SURPRISE_RE.findAll(plain).count()
    val exclaim = ended == '!' || sentence.trimEnd().endsWith('!')
    val best = maxOf(joy, concern, surprise)
    if (best == 0) {
        val question = ended == '?' || sentence.trimEnd().endsWith('?')
        return if (question) Feeling(Expression.QUESTION, 0.8f) else null
    }
    // on a tie the more telling face wins: worry over surprise over a smile
    val expression = when (best) {
        concern -> Expression.CONCERN
        surprise -> Expression.SURPRISE
        else -> Expression.JOY
    }
    val strength = (0.6f + 0.15f * (best - 1) + if (exclaim) 0.2f else 0f).coerceAtMost(1f)
    return Feeling(expression, strength)
}

private const val SENTENCE_END = ".!?…\n"

/**
 * Follows the words as they come (in pieces, from the live transcript or a whole reply) and keeps the feeling of the sentence being
 * said: a feeling found mid-sentence shows at once, a finished sentence with none brings the face back to neutral. Thread safe.
 */
internal class ExpressionTracker {
    private val sentence = StringBuilder()
    private var current = Feeling.NEUTRAL

    @get:Synchronized
    val feeling: Feeling get() = current

    @Synchronized
    fun feed(text: String) {
        for (ch in text) {
            if (ch in SENTENCE_END) {
                if (sentence.isNotBlank()) current = feelingOf(sentence.toString(), ch) ?: Feeling.NEUTRAL
                sentence.setLength(0)
            } else {
                sentence.append(ch)
            }
        }
        if (sentence.length > 400) sentence.delete(0, sentence.length - 400)
        if (sentence.isNotBlank()) feelingOf(sentence.toString())?.let { current = it }
    }

    @Synchronized
    fun reset() {
        sentence.setLength(0)
        current = Feeling.NEUTRAL
    }
}
