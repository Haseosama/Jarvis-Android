package com.jarvis.android.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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

    @Test
    fun `a browser action is sent encrypted as json, readable with the pairing code`() {
        val iv = ByteArray(16) { it.toByte() }
        val action = buildJsonObject { put("action", "fill"); put("target", "3"); put("value", "chaussettes"); put("submit", true) }
        val enc = Json.parseToJsonElement(browserBody("AB12CD", action, iv)).jsonObject["enc"]!!.jsonPrimitive.content
        val raw = Base64.getDecoder().decode(enc)
        val key = MessageDigest.getInstance("SHA-256").digest("AB12CDJARVIS-DASHBOARD-v1".toByteArray())
        val clear = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(raw.copyOfRange(0, 16)))
            String(doFinal(raw.copyOfRange(16, raw.size)))
        }
        assertEquals(action, Json.parseToJsonElement(clear))
    }

    @Test
    fun `the browser answer gives its text and its screenshot`() {
        val page = parseBrowserReply("""{"ok":true,"title":"Boutique","text":"Page : Boutique\n[1] bouton « Payer »"}""")!!
        assertTrue(page.ok)
        assertEquals("Page : Boutique\n[1] bouton « Payer »", page.text)
        assertNull(page.jpeg)
        val look = parseBrowserReply("""{"ok":true,"text":"Capture.","jpegBase64":"/9j/2w=="}""")!!
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xdb.toByte()), look.jpeg)
        val failed = parseBrowserReply("""{"ok":false,"text":"Rien trouvé sur la page pour « Payer »."}""")!!
        assertFalse(failed.ok)
        assertNull(parseBrowserReply("""{"ok":true}"""))
        assertNull(parseBrowserReply("pas du json"))
    }

    @Test
    fun `the PC's picture comes back decoded, or why it failed`() {
        val made = parseImageReply("""{"ok":true,"text":"Image créée avec ComfyUI.","png":"iVBORw0KGgo=","seed":42}""")!!
        assertTrue(made.ok)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), made.png)
        val refused = parseImageReply("""{"ok":false,"text":"Refusé : je ne crée aucune image d’enfant ni de mineur."}""")!!
        assertFalse(refused.ok)
        assertNull(refused.png)
        assertNull(parseImageReply("""{"ok":true}"""))
        assertNull(parseImageReply("<html>"))
    }
}
