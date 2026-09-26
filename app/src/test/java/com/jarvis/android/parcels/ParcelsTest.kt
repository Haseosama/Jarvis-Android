package com.jarvis.android.parcels

import com.jarvis.android.offline.OfflineAction
import com.jarvis.android.offline.interpret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId

class ParcelsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `the carrier is recognised from the number`() {
        assertEquals(listOf(Carrier.LAPOSTE), detectCarriers("6A 1234 5678 901"))
        assertEquals(listOf(Carrier.LAPOSTE), detectCarriers("cb123456789fr"))
        assertEquals(listOf(Carrier.UPS), detectCarriers("1Z999AA10123456784"))
        assertEquals(listOf(Carrier.AMAZON), detectCarriers("TBA123456789012"))
        assertEquals(listOf(Carrier.DPD), detectCarriers("12345678901234"))
        assertEquals(listOf(Carrier.MONDIAL_RELAY), detectCarriers("12345678"))
        assertTrue(detectCarriers("ab").isEmpty())
        assertEquals("https://www.laposte.fr/outils/suivre-vos-envois?code=6A12345678901", trackingUrl(Carrier.LAPOSTE, "6a 12345678901"))
    }

    @Test
    fun `only unmistakable numbers in messages about a parcel are picked up`() {
        assertEquals(listOf("6A12345678901"), findTrackingNumbers("Votre colis 6A12345678901 a été expédié."))
        assertEquals(listOf("1Z999AA10123456784"), findTrackingNumbers("Your package 1Z999AA10123456784 has shipped"))
        assertTrue(findTrackingNumbers("Rappelle-moi au 0612345678, colis ou pas").isEmpty())
        assertTrue(findTrackingNumbers("Code CB123456789FR pour ta commande de pizza").isEmpty())
    }

    @Test
    fun `La Poste's answer gives the latest step and whether it has arrived`() {
        val body = """{"returnCode":200,"shipment":{"idShip":"6A12345678901","isFinal":false,"event":[
            {"code":"ET1","label":"Votre colis est en cours d'acheminement.","date":"2026-09-25T08:10:00+02:00"},
            {"code":"MD2","label":"Votre colis est en cours de livraison.","date":"2026-09-26T07:45:00+02:00"},
            {"code":"PC1","label":"Votre colis a été déposé.","date":"2026-09-24T17:00:00+02:00"}]}}"""
        val s = parseLaPoste(body)!!
        assertEquals("Votre colis est en cours de livraison.", s.label)
        assertFalse(s.delivered)
        assertTrue(parseLaPoste("""{"shipment":{"isFinal":true,"event":[{"code":"DI1","label":"Livré","date":"2026-09-26T12:00:00+02:00"}]}}""")!!.delivered)
        assertNull(parseLaPoste("""{"returnCode":404,"returnMessage":"introuvable"}"""))
        assertNull(parseLaPoste("pas du json"))
        assertEquals("le 26/09 à 7 h 45", eventWhen("2026-09-26T07:45:00+02:00", ZoneId.of("Europe/Paris")))
    }

    @Test
    fun `a number seen in a message is offered once, and not when already followed`() {
        val store = ParcelStore(File(tmp.root, "p.json"))
        assertTrue(store.offerOnce("6A12345678901"))
        assertFalse(store.offerOnce("6A12345678901"))
        store.update { it + Parcel("8R00000000001", Carrier.LAPOSTE) }
        assertFalse(store.offerOnce("8R00000000001"))
        assertEquals(1, ParcelStore(File(tmp.root, "p.json")).all().size)
    }

    @Test
    fun `offline, where is my parcel`() {
        assertEquals("parcel", (interpret("Où en est mon colis ?") as OfflineAction.ToolCall).name)
    }
}
