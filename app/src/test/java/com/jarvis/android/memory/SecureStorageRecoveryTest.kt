package com.jarvis.android.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException

class SecureStorageRecoveryTest {
    @Test
    fun `a readable store is opened once and never reset`() {
        var opens = 0
        var resets = 0
        val result = openOrReset(open = { opens++; "store" }, reset = { resets++ })
        assertEquals("store", result)
        assertEquals(1, opens)
        assertEquals(0, resets)
    }

    @Test
    fun `an undecryptable store is reset once and reopened`() {
        var opens = 0
        var resets = 0
        val result = openOrReset(
            open = {
                opens++
                if (opens == 1) throw AEADBadTagException("tag mismatch")
                "fresh"
            },
            reset = { resets++ },
        )
        assertEquals("fresh", result)
        assertEquals(2, opens)
        assertEquals(1, resets)
    }

    @Test
    fun `an unreadable keyset file is handled like a security failure`() {
        var opens = 0
        val result = openOrReset(
            open = { if (++opens == 1) throw IOException("corrupt keyset") else "fresh" },
            reset = {},
        )
        assertEquals("fresh", result)
    }

    @Test
    fun `a failure that persists after the reset is surfaced instead of looping`() {
        var opens = 0
        val failure = GeneralSecurityException("still broken")
        try {
            openOrReset(open = { opens++; throw failure }, reset = {})
            fail("l'exception aurait dû être propagée")
        } catch (e: GeneralSecurityException) {
            assertSame(failure, e)
        }
        assertEquals(2, opens)
    }

    @Test
    fun `unrelated errors are not swallowed by the recovery`() {
        var resets = 0
        try {
            openOrReset<String>(open = { throw IllegalStateException("bug") }, reset = { resets++ })
            fail("l'exception aurait dû être propagée")
        } catch (e: IllegalStateException) {
            assertEquals("bug", e.message)
        }
        assertEquals(0, resets)
    }
}
