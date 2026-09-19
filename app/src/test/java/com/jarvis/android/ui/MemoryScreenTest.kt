package com.jarvis.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryScreenTest {
    @Test
    fun `all supported categories have french labels`() {
        assertEquals("Identité", memoryCategoryLabel("identity"))
        assertEquals("Préférences", memoryCategoryLabel("preferences"))
        assertEquals("Projets", memoryCategoryLabel("projects"))
        assertEquals("Relations", memoryCategoryLabel("relationships"))
        assertEquals("Souhaits", memoryCategoryLabel("wishes"))
        assertEquals("Notes", memoryCategoryLabel("notes"))
        assertEquals("custom", memoryCategoryLabel("custom"))
    }
}
