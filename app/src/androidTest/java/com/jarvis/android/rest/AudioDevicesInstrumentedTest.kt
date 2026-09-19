package com.jarvis.android.rest

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

/** The real microphone and speaker paths (on an emulator: the virtual devices). */
@RunWith(AndroidJUnit4::class)
class AudioDevicesInstrumentedTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A quiet 440 Hz tone as 24 kHz mono 16-bit PCM. */
    private fun tone(seconds: Double): ByteArray {
        val samples = (SPEECH_SAMPLE_RATE * seconds).toInt()
        return shortsToPcm(ShortArray(samples) { (sin(2 * PI * 440 * it / SPEECH_SAMPLE_RATE) * 3000).toInt().toShort() })
    }

    private fun chunks(pcm: ByteArray, count: Int): List<ByteArray> {
        val size = (pcm.size / count) and 1.inv()
        return (0 until count).map { i ->
            val from = i * size
            pcm.copyOfRange(from, if (i == count - 1) pcm.size else from + size)
        }
    }

    @Test
    fun recorderCapturesAboutTheRecordedDuration() {
        val recorder = AudioRecorder(context)
        recorder.start()
        Thread.sleep(800)
        val samples = recorder.stop()
        // 0.8 s at 16 kHz is 12 800 samples; allow for start-up and scheduling.
        assertTrue("échantillons : ${samples.size}", samples.size in 5_000..16_000)
    }

    @Test
    fun recorderRefusesASecondStartAndCanBeReused() {
        val recorder = AudioRecorder(context)
        recorder.start()
        try {
            recorder.start()
            fail("un second démarrage doit être refusé")
        } catch (_: RestChatException) {
        }
        recorder.stop()
        recorder.start()
        Thread.sleep(400)
        assertTrue(recorder.stop().isNotEmpty())
    }

    @Test
    fun stopWithoutStartIsEmpty() {
        assertEquals(0, AudioRecorder(context).stop().size)
    }

    @Test
    fun playerPlaysStreamedChunksAndReturnsAfterTheirDuration() = runBlocking {
        val pcm = tone(0.8)
        val player = AudioPlayer(context)
        val begun = System.currentTimeMillis()
        player.play { emit -> chunks(pcm, 4).forEach { emit(it) } }
        val elapsed = System.currentTimeMillis() - begun
        // 0.8 s of sound; the drain wait is capped at duration + 3 s.
        assertTrue("durée : $elapsed ms", elapsed in 300..5_000)
    }

    @Test
    fun playerStopsEarlyOnRequest() = runBlocking {
        val pcm = tone(10.0)
        val player = AudioPlayer(context)
        val stopper = Thread {
            Thread.sleep(700)
            player.stop()
        }
        val begun = System.currentTimeMillis()
        stopper.start()
        player.play { emit -> chunks(pcm, 100).forEach { emit(it) } }
        val elapsed = System.currentTimeMillis() - begun
        stopper.join()
        assertTrue("durée : $elapsed ms", elapsed < 5_000)
    }

    @Test
    fun playerPassesOnErrorsFromTheSource() = runBlocking {
        val player = AudioPlayer(context)
        try {
            player.play { emit ->
                emit(tone(0.1))
                throw RestChatException("source en échec")
            }
            fail("erreur attendue")
        } catch (e: RestChatException) {
            assertEquals("source en échec", e.message)
        }
    }
}
