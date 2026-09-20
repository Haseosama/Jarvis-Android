package com.jarvis.android.avatar

import java.text.Normalizer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mouth shapes for lip-sync, ported from Mark-LIV (core/viseme.py and _pcm_visemes in main.py, CC BY-NC 4.0, see
 * assets/avatar/NOTICE.txt). The audio gives the timing and a read of the vowels (formants); the transcript gives the
 * consonants the audio cannot show (lips pressed shut on m, b, p). Nothing here depends on a language.
 */

/** One 20 ms slice of the voice: loudness 0..1, jaw openness 0..1, lip width -1 (rounded) .. +1 (spread). */
internal data class AudioViseme(val level: Float, val open: Float, val wide: Float)

/** Mouth shape of a sound: openness, width, and how strongly the lips are forced together. */
internal data class Shape(val open: Float, val wide: Float, val closure: Float)

internal val VISEMES: Map<String, Shape> = mapOf(
    "REST" to Shape(0.00f, 0.00f, 0.00f),
    "AA" to Shape(0.92f, -0.05f, 0.00f),
    "E" to Shape(0.52f, 0.42f, 0.00f),
    "I" to Shape(0.20f, 0.62f, 0.00f),
    "O" to Shape(0.55f, -0.52f, 0.00f),
    "U" to Shape(0.26f, -0.74f, 0.00f),
    "MBP" to Shape(0.00f, 0.00f, 1.00f),
    "FV" to Shape(0.10f, 0.22f, 0.55f),
    "S" to Shape(0.16f, 0.42f, 0.00f),
    "L" to Shape(0.36f, 0.18f, 0.00f),
    "TD" to Shape(0.28f, 0.12f, 0.00f),
    "K" to Shape(0.30f, -0.04f, 0.00f),
    "R" to Shape(0.28f, -0.16f, 0.00f),
)

private val DURATION = mapOf(
    "REST" to 1.0f, "AA" to 1.15f, "E" to 1.05f, "I" to 1.0f, "O" to 1.1f, "U" to 1.05f,
    "MBP" to 0.5f, "FV" to 0.8f, "S" to 0.9f, "L" to 0.65f, "TD" to 0.5f, "K" to 0.55f, "R" to 0.55f,
)

private val LETTER = mapOf(
    'a' to "AA", 'e' to "E", 'i' to "I", 'y' to "I", 'o' to "O", 'u' to "U", 'w' to "U",
    'b' to "MBP", 'p' to "MBP", 'm' to "MBP", 'f' to "FV", 'v' to "FV",
    's' to "S", 'z' to "S", 'c' to "S", 'j' to "S", 'x' to "S", 'l' to "L",
    't' to "TD", 'd' to "TD", 'n' to "TD", 'k' to "K", 'g' to "K", 'h' to "K", 'q' to "K", 'r' to "R",
)

private val UNDECOMPOSED = mapOf(
    'ı' to "i", 'ø' to "o", 'đ' to "d", 'ħ' to "h", 'ŀ' to "l", 'ŧ' to "t", 'ß' to "s", 'æ' to "a",
    'œ' to "o", 'þ' to "t", 'ð' to "d", 'ŋ' to "n", 'ł' to "l",
)

private val CYRILLIC = mapOf(
    'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "j", 'з' to "z",
    'и' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
    'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "s", 'ч' to "s", 'ш' to "s", 'щ' to "s",
    'ъ' to "", 'ы' to "i", 'ь' to "", 'э' to "e", 'ю' to "u", 'я' to "a", 'і' to "i", 'ї' to "i", 'є' to "e",
    'ґ' to "g", 'ў' to "u",
)

private val GREEK = mapOf(
    'α' to "a", 'β' to "v", 'γ' to "g", 'δ' to "d", 'ε' to "e", 'ζ' to "z", 'η' to "i", 'θ' to "t", 'ι' to "i",
    'κ' to "k", 'λ' to "l", 'μ' to "m", 'ν' to "n", 'ξ' to "s", 'ο' to "o", 'π' to "p", 'ρ' to "r", 'σ' to "s",
    'ς' to "s", 'τ' to "t", 'υ' to "i", 'φ' to "f", 'χ' to "h", 'ψ' to "s", 'ω' to "o",
)

private val DIGRAPH = mapOf(
    "sh" to "S", "ch" to "S", "ts" to "S", "th" to "TD", "ck" to "K", "ng" to "K", "gh" to "K", "ph" to "FV",
    "oo" to "U", "ou" to "O", "ow" to "O", "wh" to "U", "ee" to "I", "ea" to "I", "ie" to "I", "qu" to "K",
)

private const val PAUSES = ".,;:!?…\n"

/** Below this share of readable letters the text is in a script we cannot read phonetically (CJK, Arabic…). */
private const val MIN_COVERAGE = 0.55f

/** Reduces any character to a bare Latin letter, or "" when it has none. One rule replaces a spelling table per language. */
internal fun toLatin(ch: Char): String {
    val c = ch.lowercaseChar()
    if (c in 'a'..'z') return c.toString()
    UNDECOMPOSED[c]?.let { return it }
    CYRILLIC[c]?.let { return it }
    GREEK[c]?.let { return it }
    val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    if (base.length == 1 && base[0] in 'a'..'z') return base
    if (base.isNotEmpty() && base != c.toString()) return toLatin(base[0])
    return ""
}

internal fun coverage(text: String): Float {
    val letters = text.filter { it.isLetter() }
    if (letters.isEmpty()) return 0f
    return letters.count { toLatin(it).isNotEmpty() }.toFloat() / letters.length
}

/** Splits a line of speech into (shape name, duration weight). Empty for scripts we cannot read phonetically. */
internal fun textToVisemes(text: String): List<Pair<String, Float>> {
    val s = text.lowercase()
    if (coverage(s) < MIN_COVERAGE) return emptyList()
    val out = ArrayList<Pair<String, Float>>()
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        if (ch in PAUSES) {
            out += "REST" to 1.4f
            i++
            continue
        }
        if (ch.isWhitespace()) {
            // A word gap is a beat, not a closed mouth: closing between words looks like chewing.
            if (out.isNotEmpty() && out.last().first != "REST") out += out.last().first to 0.35f
            i++
            continue
        }
        val two = toLatin(ch) + if (i + 1 < s.length) toLatin(s[i + 1]) else ""
        val v: String
        if (two.length == 2 && two in DIGRAPH) {
            v = DIGRAPH.getValue(two)
            i += 2
        } else {
            val base = toLatin(ch)
            i++
            if (base.isEmpty()) continue
            v = LETTER[base[0]] ?: continue
        }
        if (out.isNotEmpty() && out.last().first == v) continue // a doubled letter is one sound
        out += v to DURATION.getValue(v)
    }
    return out
}

/** Fuses the transcript's shape sequence onto the audio's timing. Thread safe: the transcript and the audio arrive on different threads. */
internal class VisemeStream {
    private val queue = ArrayDeque<Pair<String, Float>>()
    private var current: Pair<String, Float> = "REST" to 1f
    private var carry = 0f

    @Synchronized
    fun reset() {
        queue.clear()
        current = "REST" to 1f
        carry = 0f
    }

    @Synchronized
    fun feedText(text: String) {
        textToVisemes(text).forEach { queue.addLast(it) }
        while (queue.size > 600) queue.removeFirst()
    }

    @get:Synchronized
    val pending: Int get() = queue.size

    private fun stepSeconds(): Float {
        // A long backlog means speech outruns the clock: shorten the step so the mouth catches up.
        val backlog = min(1f, queue.size / 45f)
        return MAX_STEP - (MAX_STEP - MIN_STEP) * backlog
    }

    /** Blends audio frames with the text queue and returns (level, openness, width) per frame. */
    @Synchronized
    fun frames(audio: List<AudioViseme>, hop: Float): List<AudioViseme> {
        val out = ArrayList<AudioViseme>(audio.size)
        for (a in audio) {
            if (a.level <= 0f) {
                out += AudioViseme(0f, 0f, 0f) // silence: wait, do not burn through the queue
                continue
            }
            carry += hop / max(1e-3f, stepSeconds() * current.second)
            while (carry >= 1f && queue.isNotEmpty()) {
                current = queue.removeFirst()
                carry -= 1f
            }
            if (carry >= 1f) carry = 1f
            val shape = VISEMES[current.first] ?: VISEMES.getValue("REST")
            var o: Float
            var w: Float
            var closure = shape.closure
            if (queue.isNotEmpty() || current.first != "REST") {
                o = 0.72f * shape.open + 0.28f * a.open // the text leads the shape, the audio keeps it honest
                w = 0.78f * shape.wide + 0.22f * a.wide
            } else {
                o = a.open
                w = a.wide
                closure = 0f
            }
            o *= 1f - closure
            out += AudioViseme(a.level, o.coerceIn(0f, 1f), w.coerceIn(-1f, 1f))
        }
        return out
    }

    private companion object {
        const val MIN_STEP = 0.045f
        const val MAX_STEP = 0.105f
    }
}

// ── audio analysis ──────────────────────────────────────────────────────────

private const val LEVEL_FLOOR = 60f
private const val LEVEL_FULL = 2600f
internal const val VIS_WIN = 1024
internal const val VIS_HOP = 480 // 20 ms at 24 kHz

/** RMS of samples[from, to) on the 16-bit scale, mapped to 0..1 (0 below the room-noise floor). */
internal fun pcmLevel(samples: ShortArray, from: Int, to: Int): Float {
    val end = min(to, samples.size)
    if (end <= from) return 0f
    var sum = 0.0
    for (i in from until end) {
        val v = samples[i].toDouble()
        sum += v * v
    }
    val rms = sqrt(sum / (end - from)).toFloat()
    return if (rms <= LEVEL_FLOOR) 0f else min(1f, (rms - LEVEL_FLOOR) / (LEVEL_FULL - LEVEL_FLOOR))
}

/** In-place radix-2 FFT of [re] + i[im]; the size must be a power of two. */
internal fun fft(re: FloatArray, im: FloatArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j xor bit
        if (i < j) {
            val tr = re[i]; re[i] = re[j]; re[j] = tr
            val ti = im[i]; im[i] = im[j]; im[j] = ti
        }
    }
    var len = 2
    while (len <= n) {
        val ang = -2.0 * PI / len
        val wr = cos(ang).toFloat()
        val wi = sin(ang).toFloat()
        var i = 0
        while (i < n) {
            var cr = 1f
            var ci = 0f
            for (k in 0 until len / 2) {
                val a = i + k
                val b = i + k + len / 2
                val xr = re[b] * cr - im[b] * ci
                val xi = re[b] * ci + im[b] * cr
                re[b] = re[a] - xr; im[b] = im[a] - xi
                re[a] += xr; im[a] += xi
                val nr = cr * wr - ci * wi
                ci = cr * wi + ci * wr
                cr = nr
            }
            i += len
        }
        len = len shl 1
    }
}

private val HANN = FloatArray(VIS_WIN) { (0.5 - 0.5 * cos(2.0 * PI * it / (VIS_WIN - 1))).toFloat() }

/**
 * One (level, openness, width) triple per 20 ms of [samples]. Openness follows the first formant (it climbs as the jaw
 * drops), width the second (high for spread vowels, low for rounded ones), and fricatives pull the jaw nearly shut.
 */
internal fun pcmVisemes(samples: ShortArray, sampleRate: Int = 24_000): List<AudioViseme> {
    if (samples.size < VIS_HOP) return emptyList()
    val binHz = sampleRate.toFloat() / VIS_WIN
    fun bin(hz: Float) = (hz / binHz).toInt().coerceIn(0, VIS_WIN / 2)
    val f1lo = bin(150f)..bin(450f) - 1
    val f1hi = bin(450f)..bin(1100f) - 1
    val f2bk = bin(600f)..bin(1300f) - 1
    val f2fr = bin(1700f)..bin(3200f) - 1
    val hissR = bin(3800f)..bin(8000f) - 1
    val re = FloatArray(VIS_WIN)
    val im = FloatArray(VIS_WIN)
    val out = ArrayList<AudioViseme>(samples.size / VIS_HOP + 1)
    var start = 0
    while (start < samples.size) {
        val level = pcmLevel(samples, start, start + VIS_HOP)
        if (level <= 0f) {
            out += AudioViseme(0f, 0f, 0f)
            start += VIS_HOP
            continue
        }
        var mean = 0f
        var count = 0
        for (i in 0 until VIS_WIN) {
            val idx = start + i
            if (idx < samples.size) { mean += samples[idx]; count++ }
        }
        mean /= max(1, count)
        for (i in 0 until VIS_WIN) {
            val idx = start + i
            re[i] = if (idx < samples.size) (samples[idx] - mean) * HANN[i] else 0f
            im[i] = 0f
        }
        fft(re, im)
        fun band(r: IntRange): Float {
            var s = 0f
            for (k in r) s += sqrt(re[k] * re[k] + im[k] * im[k])
            return s
        }
        val f1l = band(f1lo); val f1h = band(f1hi)
        val f2b = band(f2bk); val f2f = band(f2fr)
        val hiss = band(hissR)
        var openness = f1h / (f1l + f1h + 1e-6f)
        var width = (f2f - f2b) / (f2f + f2b + 1e-6f)
        // A wide-open jaw cannot purse: openness damps the width term.
        width *= Math.pow((1f - openness).toDouble(), 0.8).toFloat()
        val h = hiss / (f1l + f1h + f2b + f2f + hiss + 1e-6f)
        openness *= 1f - 0.65f * min(1f, h * 2.5f)
        out += AudioViseme(level, openness.coerceIn(0f, 1f), width.coerceIn(-1f, 1f))
        start += VIS_HOP
    }
    return out
}

/** Converts little-endian 16-bit PCM bytes to samples. */
internal fun pcm16ToShorts(bytes: ByteArray): ShortArray {
    val n = bytes.size / 2
    return ShortArray(n) { ((bytes[2 * it + 1].toInt() shl 8) or (bytes[2 * it].toInt() and 0xFF)).toShort() }
}

internal fun absf(x: Float) = abs(x)
