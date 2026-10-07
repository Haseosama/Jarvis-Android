package com.jarvis.android.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcLinkTest {
    @Test
    fun `the qr link gives the address and the key`() {
        val t = pairingTarget("https://192.168.1.20:8000/auto-login?key=XPMJJR")!!
        assertEquals("https://192.168.1.20:8000", t.baseUrl)
        assertEquals("XPMJJR", t.key)
        assertEquals("https://192.168.1.20:8000/auto-login?key=XPMJJR", t.autoLoginUrl)
    }

    @Test
    fun `a typed address gets https and port 8000 and the typed key`() {
        assertEquals(PairingTarget("https://192.168.1.20:8000", "AB12CD"), pairingTarget(" 192.168.1.20 ", "ab 12-cd"))
        assertEquals(PairingTarget("https://192.168.1.20:8001", "AB12CD"), pairingTarget("192.168.1.20:8001", "AB12CD"))
        assertEquals(PairingTarget("https://pc.local:8000", "AB12CD"), pairingTarget("https://pc.local:8000/", "AB12CD"))
    }

    @Test
    fun `a missing or wrong key or address is refused`() {
        assertNull(pairingTarget("192.168.1.20", ""))
        assertNull(pairingTarget("192.168.1.20", "ABC"))
        assertNull(pairingTarget("", "AB12CD"))
        assertNull(pairingTarget("ftp://192.168.1.20", "AB12CD"))
        assertNull(pairingTarget("192.168.1.20:99999", "AB12CD"))
    }

    /** What Jarvis PC's /auto-login really answers (dashboard/server.py), trimmed. */
    private val autoLoginPage = """
        <body>
        <script>
          sessionStorage.setItem('jarvis_token','0jVVKNmJ6swC5S0hhrK5cZ_RR4SOjz1DQJdCejXF-88');
          sessionStorage.setItem('jarvis_key','XPMJJR');
          localStorage.setItem('jarvis_device_token','DXLG5RrtxAZz36jjDg9R1lztHxfUxOJjCtnGZWykygQ');
          setTimeout(function(){location.replace('/')},400);
        </script>
        <p>Connecting to JARVIS…</p>
        </body>
    """.trimIndent()

    @Test
    fun `the auto-login page gives the tokens, the expired page nothing`() {
        assertEquals(
            AutoLogin("0jVVKNmJ6swC5S0hhrK5cZ_RR4SOjz1DQJdCejXF-88", "XPMJJR", "DXLG5RrtxAZz36jjDg9R1lztHxfUxOJjCtnGZWykygQ"),
            parseAutoLogin(autoLoginPage),
        )
        assertNull(parseAutoLogin("<body><div><h2>Link Expired</h2><p>Press Remote Control in JARVIS</p></div></body>"))
    }

    @Test
    fun `commands are encrypted the way Jarvis PC decrypts them`() {
        // made with openssl enc -aes-256-cbc, key SHA-256("AB12CD" + "JARVIS-DASHBOARD-v1"), and decrypted by server.py's _decrypt_cbc
        val iv = ByteArray(16) { it.toByte() }
        assertEquals("AAECAwQFBgcICQoLDA0ODw6ymuLVrDwcOoUFVrdeUR7qWdT1MzQeJTXLr8IxPzkd", encryptCommand("AB12CD", "ouvre Chrome sur le PC é", iv))
        assertEquals("""{"enc":"AAECAwQFBgcICQoLDA0ODw6ymuLVrDwcOoUFVrdeUR7qWdT1MzQeJTXLr8IxPzkd"}""", commandBody("AB12CD", "ouvre Chrome sur le PC é", iv))
    }

    @Test
    fun `only what Jarvis PC says is kept from the dashboard messages`() {
        assertEquals(PcEvent.Said("Chrome est ouvert."), parsePcEvent("""{"type":"log","speaker":"jarvis","text":" Chrome est ouvert. ","ts":"2026-10-07T11:00:00"}"""))
        assertNull(parsePcEvent("""{"type":"log","speaker":"user","text":"ouvre Chrome"}"""))
        assertNull(parsePcEvent("""{"type":"sys","text":"Remote connection established."}"""))
        assertEquals(PcEvent.State("sleeping"), parsePcEvent("""{"type":"status","state":"sleeping"}"""))
        assertNull(parsePcEvent("not json"))
        assertEquals("Je m'en occupe. C'est fait.", joinAnswer(listOf("Je m'en occupe.", " C'est fait.", "C'est fait.")))
    }

    @Test
    fun `device login and the stored pairing read back`() {
        assertEquals("tok" to "XPMJJR", parseDeviceLogin("""{"ok":true,"token":"tok","key":"XPMJJR"}"""))
        assertEquals("tok" to null, parseDeviceLogin("""{"ok":true,"token":"tok"}"""))
        assertNull(parseDeviceLogin("""{"ok":false}"""))
        val p = PcPairing("https://192.168.1.20:8000", "ab".repeat(32), "XPMJJR", "tok", "dev")
        assertEquals(p, PcPairing.fromJson(p.toJson()))
        assertNull(PcPairing.fromJson("{}"))
        assertNull(PcPairing.fromJson(null))
        assertEquals("AB12CD", normalizeKey("ab12cd"))
        assertNull(normalizeKey("ab12c"))
    }
}
