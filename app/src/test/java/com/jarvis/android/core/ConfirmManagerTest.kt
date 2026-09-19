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

    @Test
    fun `a newer request refuses the older one and can itself be confirmed`() = runBlocking {
        val manager = ConfirmManager()
        val first = async { manager.request("A", "premier") }
        while (manager.pending.value == null) delay(10)
        val second = async { manager.request("B", "second") }
        assertFalse(first.await())
        while (manager.pending.value?.actionLabel != "B") delay(10)
        manager.confirm()
        assertTrue(second.await())
        assertNull(manager.pending.value)
    }

    @Test
    fun `a request that times out leaves nothing pending`() = runBlocking {
        val manager = ConfirmManager()
        val outcome = kotlinx.coroutines.withTimeoutOrNull(100) { manager.request("A", "x") }
        assertNull(outcome)
        assertNull(manager.pending.value)
    }
}
