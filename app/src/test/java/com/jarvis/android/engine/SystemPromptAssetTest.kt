package com.jarvis.android.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SystemPromptAssetTest {
    private val prompt = File("src/main/assets/system_prompt.txt").readText()

    @Test
    fun `the prompt lets the model chain and retry tools`() {
        assertTrue(prompt.contains("REASONING"))
        assertTrue(prompt.contains("Chain tools"))
        assertFalse(prompt.contains("No retries"))
        assertFalse(prompt.contains("Call tools exactly once"))
    }

    @Test
    fun `the prompt no longer says satellites or confirmations are missing`() {
        assertFalse(prompt.contains("Satellites are not available"))
        assertFalse(prompt.contains("they confirm each on screen"))
        assertFalse(prompt.contains("the user confirms on screen"))
    }
}
