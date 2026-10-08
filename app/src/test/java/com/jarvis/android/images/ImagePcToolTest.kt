package com.jarvis.android.images

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePcToolTest {
    @Test
    fun `anything about a child or a minor is refused, adult switch or not`() {
        for (p in listOf("une adolescente à la plage", "cute teen girl", "Loli", "une écolière", "femme de 16 ans", "girl, 15yo", "Little girl")) {
            assertEquals(p, "Refusé : je ne crée aucune image d'enfant ni de mineur.", imageRefusal(p, adult = true))
        }
    }

    @Test
    fun `explicit requests need the adult switch`() {
        assertTrue(imageRefusal("une femme nue sur un lit", adult = false)!!.contains("désactivé"))
        assertNull(imageRefusal("une femme nue sur un lit", adult = true))
        assertNull(imageRefusal("un château au coucher du soleil", adult = false))
        assertNull(imageRefusal("une femme de 25 ans", adult = true))
        assertEquals("Décrivez l'image à créer.", imageRefusal("  ", adult = true))
    }

    @Test
    fun `the request carries the size of the format and adult only as asked`() {
        val r = imageRequest("  a lighthouse in a storm ", "Paysage", "", -1, adult = false)
        assertEquals("a lighthouse in a storm", r["prompt"]!!.jsonPrimitive.content)
        assertEquals(1216, r["width"]!!.jsonPrimitive.int)
        assertEquals(832, r["height"]!!.jsonPrimitive.int)
        assertFalse(r["adult"]!!.jsonPrimitive.boolean)
        assertFalse("seed" in r || "negative" in r)
        val again = imageRequest("x", "", "blur", 42, adult = true)
        assertEquals(832, again["width"]!!.jsonPrimitive.int)
        assertEquals(42, again["seed"]!!.jsonPrimitive.int)
        assertEquals("blur", again["negative"]!!.jsonPrimitive.content)
        assertTrue(again["adult"]!!.jsonPrimitive.boolean)
        assertEquals(1024, imageRequest("x", "carré", "", -1, false)["width"]!!.jsonPrimitive.int)
    }

    @Test
    fun `the action is read in french or english`() {
        assertEquals("install", imageAction("Installer"))
        assertEquals("status", imageAction("état"))
        assertEquals("create", imageAction(""))
        assertEquals("create", imageAction("creer"))
    }
}
