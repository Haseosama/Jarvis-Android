package com.jarvis.android.wake

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import androidx.test.rule.GrantPermissionRule
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The openWakeWord models on short recordings made with a text-to-speech voice ("hey jarvis", "ok
 * jarvis", an unrelated sentence, "hey travis"). The models are downloaded into the app's private
 * storage the first time, so run this on an emulator, not on a phone.
 */
@RunWith(AndroidJUnit4::class)
class WakeWordInstrumentedTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    companion object {
        private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
        private var models: OpenWakeWordModels? = null

        @BeforeClass
        @JvmStatic
        fun load() {
            val manager = WakeModelManager(instrumentation.targetContext, OkHttpClient())
            if (!manager.installed()) {
                val error = runBlocking { manager.download { } }
                assumeTrue("modèles non téléchargés : $error", error == null)
            }
            models = OpenWakeWordModels(manager.dir)
        }
    }

    /** The highest wake score reached while the recording plays, with silence before and after. */
    private fun maxScore(asset: String): Float {
        val bytes = instrumentation.context.assets.open(asset).use { it.readBytes() }
        val speech = ShortArray((bytes.size - 44) / 2) { i ->
            ((bytes[44 + 2 * i].toInt() and 0xFF) or (bytes[45 + 2 * i].toInt() shl 8)).toShort()
        }
        val samples = ShortArray(16_000 * 2) + speech + ShortArray(16_000 * 2)
        val pipeline = models!!.pipeline()
        var best = 0f
        var offset = 0
        while (offset + WAKE_CHUNK <= samples.size) {
            best = maxOf(best, pipeline.process(samples.copyOfRange(offset, offset + WAKE_CHUNK)))
            offset += WAKE_CHUNK
        }
        Log.i("WakeScore", "$asset -> $best")
        return best
    }

    @Test
    fun heyJarvisIsDetectedWithAnEnglishVoice() {
        val score = maxScore("wake_hey_jarvis.wav")
        assertTrue("score $score", score >= wakeThresholdFor(1))
    }

    @Test
    fun okJarvisIsDetectedWithAnEnglishVoice() {
        val score = maxScore("wake_ok_jarvis.wav")
        assertTrue("score $score", score >= wakeThresholdFor(1))
    }

    @Test
    fun unrelatedSpeechIsNotDetected() {
        assertTrue(maxScore("wake_other.wav") < wakeThresholdFor(2))
        assertTrue(maxScore("wake_other_french_voice.wav") < wakeThresholdFor(2))
    }

    @Test
    fun aSimilarNameStaysBelowTheNormalThreshold() {
        val score = maxScore("wake_travis.wav")
        assertTrue("score $score", score < wakeThresholdFor(1))
    }

    @Test
    fun aFrenchVoiceNeedsTheSensitiveLevel() {
        val score = maxScore("wake_hey_jarvis_french_voice.wav")
        assertTrue("score $score", score >= wakeThresholdFor(2))
        assertTrue("score $score", score < wakeThresholdFor(1))
    }

    @Test
    fun silenceIsNotDetected() {
        val pipeline = models!!.pipeline()
        var best = 0f
        repeat(60) { best = maxOf(best, pipeline.process(ShortArray(WAKE_CHUNK))) }
        assertTrue("score $best", best < wakeThresholdFor(2))
    }

    @Test
    fun theListeningLoopStartsAndStopsCleanlyOnTheRealMicrophone() {
        var detections = 0
        val manager = WakeModelManager(instrumentation.targetContext, OkHttpClient())
        val detector = OpenWakeWordDetector(instrumentation.targetContext, manager.dir, { wakeThresholdFor(2) }) { detections++ }
        detector.start()
        Thread.sleep(4_000)
        detector.stop()
        assertTrue("détections : $detections", detections == 0)
    }
}
