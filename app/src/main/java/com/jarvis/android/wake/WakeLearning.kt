package com.jarvis.android.wake

import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.sqrt

/*
 * Teaching Jarvis a new wake word on the phone.
 *
 * openWakeWord trains one classifier per phrase on thousands of synthetic voices; that needs a computer. What can be done on the phone is
 * to reuse its speech embedding (the same models as the detector) and compare what is heard with the user's own repetitions of the phrase:
 * each repetition gives a few "templates" (the 16 embeddings that end with the word), and the detector fires when the last 1.3 seconds are
 * close enough to a template (cosine similarity, after subtracting the mean of the user's ordinary speech and room). The threshold is
 * worked out from the repetitions themselves (how well each one matches the others) and from the ordinary speech (how close it gets).
 *
 * Checked with synthetic voices on the real openWakeWord models: the same voice at other speeds was always recognised, ordinary sentences
 * scored about 0.3, another voice about 0.4; only phrases that contain the words themselves ("Jarvis débranche la prise" for "Debout Jarvis")
 * came close. It is a personal detector: it is made for the voice that taught it.
 */

/** Numbers in one window: the last 16 embeddings, side by side. */
internal const val WINDOW_FLOATS = EMBEDDING_WINDOW * EMBEDDING_SIZE

/** Steps (80 ms) of silence to let the models settle before the first repetition, and before each one. */
internal const val TEACH_WARMUP_STEPS = 25
internal const val TEACH_LEAD_STEPS = 20
internal const val TEACH_SPEAK_STEPS = 38
internal const val TEACH_BACKGROUND_STEPS = 150
internal const val TEACH_REPETITIONS = 6
internal const val MIN_GOOD_REPETITIONS = 4

private const val MIN_LOUD_RMS = 250f
private const val MAX_WORD_STEPS = 20
private const val MIN_WORD_STEPS = 3
private const val WORD_GAP_STEPS = 3
private const val THRESHOLD_MIN = 0.35f
private const val THRESHOLD_MAX = 0.92f

/** One 80 ms step of a recording: how loud it was, and its embedding once the models are warm (from the tenth step on). */
internal class TeachStep(val rms: Float, val embedding: FloatArray?)

/** A repetition: the steps recorded around it and the index (in [steps]) where the user was invited to speak. */
internal class TeachClip(val steps: List<TeachStep>, val prompt: Int)

/** The 16 embeddings that end at step [end], as one vector; null while the models are not warm or the history is too short. */
internal fun windowEndingAt(steps: List<TeachStep>, end: Int): FloatArray? {
    if (end < EMBEDDING_WINDOW - 1 || end >= steps.size) return null
    val out = FloatArray(WINDOW_FLOATS)
    for (i in 0 until EMBEDDING_WINDOW) {
        val e = steps[end - EMBEDDING_WINDOW + 1 + i].embedding ?: return null
        e.copyInto(out, i * EMBEDDING_SIZE)
    }
    return out
}

/** Where the word was said: steps [start] until [end] (exclusive). */
internal data class Speech(val start: Int, val end: Int)

/**
 * Finds the word in a repetition: the longest run of loud steps after the invitation, allowing the short pauses inside a phrase. Loud is
 * three times the ambient level measured just before. Null when nothing was said, when it is too short or too long to be the phrase, or when
 * the sound is still going at the end of the recording (a noise, not a word).
 */
internal fun findSpeech(clip: TeachClip): Speech? {
    val steps = clip.steps
    if (clip.prompt < 5 || clip.prompt >= steps.size) return null
    val ambient = steps.subList(maxOf(0, clip.prompt - TEACH_LEAD_STEPS), clip.prompt).map { it.rms }.sorted()
    val floor = ambient[ambient.size / 2]
    val loud = maxOf(floor * 3f, MIN_LOUD_RMS)
    val runs = mutableListOf<Speech>()
    var start = -1
    var last = -1
    for (i in clip.prompt until steps.size) {
        if (steps[i].rms <= loud) continue
        if (start >= 0 && i - last <= WORD_GAP_STEPS + 1) {
            last = i
        } else {
            if (start >= 0) runs += Speech(start, last + 1)
            start = i
            last = i
        }
    }
    if (start >= 0) runs += Speech(start, last + 1)
    val word = runs.maxByOrNull { it.end - it.start } ?: return null
    if (word.end - word.start !in MIN_WORD_STEPS..MAX_WORD_STEPS) return null
    if (word.end >= steps.size - 1) return null
    return word
}

private fun norm(v: FloatArray): Float {
    var s = 0f
    for (x in v) s += x * x
    return sqrt(s)
}

/** [v] minus [mean], scaled to length one. */
internal fun centredUnit(v: FloatArray, mean: FloatArray): FloatArray {
    val out = FloatArray(v.size) { v[it] - mean[it] }
    val n = norm(out).coerceAtLeast(1e-9f)
    for (i in out.indices) out[i] /= n
    return out
}

private fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}

/** How the detector's threshold follows the sensitivity setting (0.75 prudent, 0.5 normal, 0.15 sensitive for the built-in models). */
internal fun adjustLearnedThreshold(learned: Float, settingThreshold: Float): Float =
    (learned + (settingThreshold - WAKE_THRESHOLD) * 0.5f).coerceIn(THRESHOLD_MIN, 0.97f)

/** A wake word learned from the user's repetitions. */
internal class LearnedWord(val label: String, val mean: FloatArray, val templates: List<FloatArray>, val threshold: Float) {
    /** Highest cosine similarity between the window and a template, from -1 to 1 (1 = the same sound). */
    fun score(window: FloatArray): Float {
        val w = centredUnit(window, mean)
        var best = -1f
        for (t in templates) best = maxOf(best, dot(t, w))
        return best
    }

    fun toBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(MAGIC)
            d.writeUTF(label)
            d.writeFloat(threshold)
            d.writeInt(templates.size)
            for (x in mean) d.writeFloat(x)
            for (t in templates) for (x in t) d.writeFloat(x)
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAGIC = 0x4A574C31   // "JWL1"

        /** Reads a learned word; null when the file is not one or is damaged. */
        fun fromBytes(bytes: ByteArray): LearnedWord? = try {
            DataInputStream(ByteArrayInputStream(bytes)).use { d ->
                if (d.readInt() != MAGIC) return null
                val label = d.readUTF()
                val threshold = d.readFloat()
                val count = d.readInt()
                if (count !in 1..400 || !threshold.isFinite()) return null
                val mean = FloatArray(WINDOW_FLOATS) { d.readFloat() }
                val templates = List(count) { FloatArray(WINDOW_FLOATS) { d.readFloat() } }
                LearnedWord(label, mean, templates, threshold)
            }
        } catch (_: java.io.IOException) {
            null
        }

        /** Just the label, for a list, without reading the templates. */
        fun labelOf(bytes: ByteArray): String? = try {
            DataInputStream(ByteArrayInputStream(bytes)).use { d -> if (d.readInt() == MAGIC) d.readUTF() else null }
        } catch (_: java.io.IOException) {
            null
        }
    }
}

internal enum class LearnQuality { SOLID, CORRECT, WEAK }

/** What teaching produced: a word with a verdict, or the reason it did not work. */
internal sealed interface LearnResult {
    data class Learned(val word: LearnedWord, val quality: LearnQuality, val repetitions: Int, val posMin: Float, val negMax: Float) : LearnResult
    data class Failed(val reason: String) : LearnResult
}

/**
 * Builds the word from the repetitions and from [background], a stretch of the user's ordinary speech and room noise.
 * The threshold sits just under the weakest repetition (as scored by the others), and never within 0.12 of the highest score the ordinary speech reached.
 */
internal fun learnWord(label: String, clips: List<TeachClip>, background: List<TeachStep>): LearnResult {
    val perClip = mutableListOf<List<FloatArray>>()
    for (clip in clips) {
        val speech = findSpeech(clip) ?: continue
        val windows = (speech.end - 1..speech.end + 3).mapNotNull { windowEndingAt(clip.steps, it) }
        if (windows.size >= 3) perClip += windows
    }
    if (perClip.size < MIN_GOOD_REPETITIONS) {
        return LearnResult.Failed(trf("Je n’ai bien entendu que {0} répétition(s) sur {1} : parlez plus fort et plus près du micro, dans le calme.", perClip.size, clips.size))
    }
    val ordinary = (EMBEDDING_WINDOW - 1 until background.size).mapNotNull { windowEndingAt(background, it) }
    if (ordinary.size < 40) return LearnResult.Failed(tr("L’enregistrement de votre voix habituelle est trop court."))
    val mean = FloatArray(WINDOW_FLOATS)
    for (w in ordinary) for (i in mean.indices) mean[i] += w[i] / ordinary.size

    val templates = perClip.map { windows -> windows.map { centredUnit(it, mean) } }
    fun best(w: FloatArray, from: List<List<FloatArray>>): Float {
        val u = centredUnit(w, mean)
        var b = -1f
        for (clip in from) for (t in clip) b = maxOf(b, dot(t, u))
        return b
    }
    // how well each repetition matches the others
    val posMin = perClip.indices.minOf { i ->
        val others = templates.filterIndexed { j, _ -> j != i }
        perClip[i].maxOf { best(it, others) }
    }
    val all = templates
    val negMax = ordinary.maxOf { best(it, all) }
    if (posMin < 0.45f) return LearnResult.Failed(trf("Vos répétitions ne se ressemblent pas assez ({0}) : dites le mot toujours de la même façon, à la même vitesse.", "%.2f".format(posMin)))
    val gap = posMin - negMax
    if (gap < 0.05f) return LearnResult.Failed(tr("Ce mot ressemble trop à votre voix de fond : choisissez une expression plus longue et plus particulière (trois syllabes au moins)."))
    val threshold = (posMin - 0.06f).coerceIn(negMax + 0.12f, THRESHOLD_MAX).coerceAtLeast(THRESHOLD_MIN)
    val quality = when {
        gap >= 0.30f -> LearnQuality.SOLID
        gap >= 0.15f -> LearnQuality.CORRECT
        else -> LearnQuality.WEAK
    }
    return LearnResult.Learned(LearnedWord(label.trim().take(40), mean, templates.flatten(), threshold), quality, perClip.size, posMin, negMax)
}
