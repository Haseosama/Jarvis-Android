package com.jarvis.android.core

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmManagerTest {
    @Test
    fun `confirm resolves request true and clears pending`() = runBlocking {
        val manager = ConfirmManager()
        val result = async { manager.request("Volume", "Mettre le volume à 50 % ?") }
        while (manager.pending.value == null) delay(10)
        assertEquals(PendingConfirmation("Volume", "Mettre le volume à 50 % ?"), manager.pending.value)
        manager.confirm()
        assertTrue(result.await())
        assertNull(manager.pending.value)
    }

    @Test
    fun `cancel resolves request false and clears pending`() = runBlocking {
        val manager = ConfirmManager()
        val result = async { manager.request("Volume", "Mettre le volume à 50 % ?") }
        while (manager.pending.value == null) delay(10)
        manager.cancel()
        assertFalse(result.await())
        assertNull(manager.pending.value)
    }
}
