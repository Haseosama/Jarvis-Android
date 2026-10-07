package com.jarvis.android.instagram

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class InstagramPublishTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `photos are cropped to a ratio instagram accepts, centred`() {
        // 3:4 portrait is taller than 4:5: cut top and bottom
        assertEquals(CropBox(0, 150, 3000, 3750), instagramCrop(3000, 4050))
        assertEquals(CropBox(0, 125, 3000, 3750), instagramCrop(3000, 4000))
        // a panorama is cut on the sides
        assertEquals(CropBox(1090, 0, 1910, 1000), instagramCrop(4090, 1000))
        // 4:3 and square stay whole
        assertEquals(CropBox(0, 0, 4000, 3000), instagramCrop(4000, 3000))
        assertEquals(CropBox(0, 0, 1000, 1000), instagramCrop(1000, 1000))
    }

    @Test
    fun `sizes stay under 1440 pixels wide and photos are decoded small enough`() {
        assertEquals(1440 to 1800, instagramSize(3000, 3750))
        assertEquals(800 to 1000, instagramSize(800, 1000))
        assertEquals(2, instagramSampleSize(4000, 3000))
        assertEquals(1, instagramSampleSize(2000, 1500))
        assertEquals(4, instagramSampleSize(8000, 6000))
    }

    private fun jpeg(vararg segments: ByteArray): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + segments.fold(ByteArray(0)) { a, b -> a + b } +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2, 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    @Test
    fun `a jpeg with exif, xmp or a comment is refused, a bare re-encoded one passes`() {
        val jfif = segment(0xE0, "JFIF\u0000\u0001\u0001".toByteArray())
        val quant = segment(0xDB, ByteArray(65))
        assertFalse(jpegHasMetadata(jpeg(jfif, quant)))
        assertTrue(jpegHasMetadata(jpeg(jfif, segment(0xE1, "Exif\u0000\u0000GPS".toByteArray()), quant)))
        assertTrue(jpegHasMetadata(jpeg(segment(0xE1, "http://ns.adobe.com/xap/1.0/".toByteArray()))))
        assertTrue(jpegHasMetadata(jpeg(segment(0xFE, "Lieu secret".toByteArray()))))
        assertTrue(jpegHasMetadata(jpeg(segment(0xED, "Photoshop 3.0".toByteArray()))))
        assertTrue(jpegHasMetadata("pas une image".toByteArray()))
    }

    @Test
    fun `the caption keeps its own hashtags and adds the missing urbex ones up to thirty`() {
        val c = buildCaption("Le temps s’est arrêté ici.\n\n#Urbex #rouille")
        assertTrue(c.startsWith("Le temps s’est arrêté ici.\n\n#Urbex #rouille #urbexfrance"))
        assertEquals(1, hashtagsIn(c).count { it == "#urbex" })
        assertEquals(11, hashtagsIn(c).size)

        val plain = buildCaption("Lumière du soir dans la salle des machines.")
        assertEquals("Lumière du soir dans la salle des machines.\n\n" + URBEX_HASHTAGS.joinToString(" "), plain)
        assertEquals(URBEX_HASHTAGS.joinToString(" "), buildCaption(""))

        val many = buildCaption((1..35).joinToString(" ") { "#tag$it" })
        assertEquals(30, hashtagsIn(many).size)
        assertTrue("#tag31" !in many)
        assertTrue(buildCaption("a".repeat(3000)).length <= IG_MAX_CAPTION)
    }

    @Test
    fun `the caption request carries the photos and the user's idea`() {
        val r = buildCaptionRequest(listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), byteArrayOf(4)), "ambiance brume")
        val parts = r["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals(4, parts.size) // three photos at most, then the text
        assertTrue(parts.last().jsonObject["text"]!!.jsonPrimitive.content.contains("ambiance brume"))
        assertTrue(r.toString().contains("restent secrets"))
    }

    @Test
    fun `the latest outing not yet posted is picked, oldest first`() {
        val zone = ZoneId.of("Europe/Paris")
        fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant().toEpochMilli()
        val photos = listOf(
            PickedPhoto(5, at(6, 16)), PickedPhoto(4, at(6, 15)), PickedPhoto(3, at(6, 14)),
            PickedPhoto(2, at(1, 10)), PickedPhoto(1, at(1, 9)),
        )
        assertEquals(listOf(3L, 4L, 5L), pickOuting(photos, emptySet(), zone, 10).map { it.id })
        assertEquals(listOf(4L, 5L), pickOuting(photos, emptySet(), zone, 2).map { it.id })
        assertEquals(listOf(1L, 2L), pickOuting(photos, setOf(3, 4, 5), zone, 10).map { it.id })
        assertEquals(listOf(3L, 4L, 5L), pickOuting(photos, setOf(3, 4, 5), zone, 10, onlyNew = false).map { it.id })
        assertTrue(pickOuting(photos, setOf(1, 2, 3, 4, 5), zone, 10).isEmpty())
    }

    @Test
    fun `posted ids round trip`() {
        assertEquals(setOf(1L, 2L, 3L), decodePublished(encodePublished(setOf(1, 2), listOf(3))))
        assertTrue(decodePublished("").isEmpty())
    }

    @Test
    fun `the page linked to an instagram account is found, with its token`() {
        val accounts = parseAccounts(obj("""{"data":[
            {"id":"1","name":"Sans insta","access_token":"t1"},
            {"id":"2","name":"Haseo Urbex","access_token":"t2","instagram_business_account":{"id":"178","username":"haseo.urbex"}}
        ]}"""))
        assertEquals(listOf(InstagramAccount("2", "Haseo Urbex", "t2", "178", "haseo.urbex")), accounts)
        val page = parsePageItself(obj("""{"id":"2","name":"P","instagram_business_account":{"id":"178"}}"""), "pagetoken")
        assertEquals("pagetoken", page!!.pageToken)
        assertNull(parsePageItself(obj("""{"id":"2","name":"P"}"""), "x"))
        assertEquals(accounts[0], decodeAccount(obj(encodeAccount(accounts[0]))))
    }

    @Test
    fun `graph answers are read`() {
        assertEquals("https://b", largestImageUrl(obj("""{"images":[{"width":720,"source":"https://a"},{"width":1440,"source":"https://b"}]}""")))
        assertNull(largestImageUrl(obj("""{"id":"1"}""")))
        assertEquals("FINISHED", containerStatus(obj("""{"status_code":"FINISHED","id":"1"}""")))
        val expired = parseGraphError(obj("""{"error":{"message":"Error validating access token","type":"OAuthException","code":190,"error_subcode":463}}"""))
        assertEquals(GraphError(190, 463, "Error validating access token"), expired)
        assertTrue(graphErrorMessage(expired, 400).contains("plus valable"))
        assertTrue(graphErrorMessage(GraphError(10, 0, ""), 403).contains("autorisation"))
        assertTrue(graphErrorMessage(GraphError(9, 2207042, ""), 400).contains("limite"))
        assertTrue(graphErrorMessage(null, 502).contains("502"))
        assertNull(parseGraphError(obj("""{"id":"1"}""")))
    }
}
