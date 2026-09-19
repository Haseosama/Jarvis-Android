package com.jarvis.android.device

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.android.JarvisApp
import com.jarvis.android.JarvisContainer
import com.jarvis.android.actions.ScreenNavigateTool
import com.jarvis.android.actions.ScreenReadTool
import com.jarvis.android.actions.ScreenScrollTool
import com.jarvis.android.actions.ScreenSwipeTool
import com.jarvis.android.actions.ScreenTapTool
import com.jarvis.android.actions.OpenAppTool
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone-control tools against a real app. Needs the accessibility service to be switched on
 * for this build (the test skips itself otherwise), which on an emulator is:
 * `settings put secure enabled_accessibility_services <package>/com.jarvis.android.device.JarvisAccessibilityService`.
 * The Clock app is used because it has plain tabs and nothing sensitive.
 */
@RunWith(AndroidJUnit4::class)
class DeviceControlInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var container: JarvisContainer

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { put(it.first, it.second) } }

    @Before
    fun setUp() {
        container = (context.applicationContext as JarvisApp).container
        val deadline = System.currentTimeMillis() + 30_000
        while (JarvisAccessibilityService.instance == null && System.currentTimeMillis() < deadline) Thread.sleep(200)
        Log.i("DeviceControlTest", "service connecté : ${JarvisAccessibilityService.instance != null}")
        assumeTrue("service d'accessibilité non activé", JarvisAccessibilityService.instance != null)
    }

    private suspend fun readUntil(text: String): String {
        var out = ""
        repeat(8) {
            out = ScreenReadTool.run(args(), container)
            if (out.contains(text, ignoreCase = true)) return out
            delay(500)
        }
        return out
    }

    @Test
    fun openAnAppReadItTapATabAndGoHome() = runBlocking<Unit> {
        ScreenNavigateTool.run(args("action" to "home"), container)
        delay(800)
        assertTrue(OpenAppTool.run(args("app_name" to "Clock"), container).contains("Clock", ignoreCase = true))
        val first = readUntil("Stopwatch")
        Log.i("DeviceControlTest", first)
        assertTrue("lecture : $first", first.contains("Stopwatch", ignoreCase = true))
        assertTrue(first.contains("[0]"))

        val tap = ScreenTapTool.run(args("text" to "Stopwatch"), container)
        Log.i("DeviceControlTest", "tap : $tap")
        assertTrue("tap : $tap", tap.startsWith("Fait."))
        val second = readUntil("Start")
        Log.i("DeviceControlTest", second)
        assertTrue("après le tap : $second", second != first)

        val home = ScreenNavigateTool.run(args("action" to "home"), container)
        assertTrue(home, home.startsWith("Fait."))
    }

    @Test
    fun swipeAndScrollReportTheirOutcome() = runBlocking<Unit> {
        OpenAppTool.run(args("app_name" to "Clock"), container)
        readUntil("Stopwatch")
        val swipe = ScreenSwipeTool.run(args("direction" to "left"), container)
        assertTrue(swipe, swipe.startsWith("Fait."))
        val scroll = ScreenScrollTool.run(args("direction" to "down"), container)
        Log.i("DeviceControlTest", "scroll : $scroll")
        assertTrue(scroll, scroll.startsWith("Fait.") || scroll.contains("défiler") || scroll.contains("Défilement"))
        ScreenNavigateTool.run(args("action" to "home"), container)
    }

    @Test
    fun aTapWithoutAReadIsRefused() = runBlocking<Unit> {
        // A fresh snapshot may exist from another test; an unknown text is refused either way.
        val out = ScreenTapTool.run(args("text" to "élément-inexistant-xyz"), container)
        assertTrue(out, out.contains("Aucun élément") || out.contains("Lisez d’abord"))
    }
}
