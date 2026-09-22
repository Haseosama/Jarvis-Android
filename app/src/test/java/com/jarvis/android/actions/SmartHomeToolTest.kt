package com.jarvis.android.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartHomeToolTest {
    private val statesBody = """
        [
          {"entity_id":"light.salon","state":"on","attributes":{"friendly_name":"Salon","brightness":128}},
          {"entity_id":"light.chambre","state":"off","attributes":{"friendly_name":"Chambre"}},
          {"entity_id":"climate.salon","state":"heat","attributes":{"friendly_name":"Chauffage salon","current_temperature":19.5}},
          {"entity_id":"sensor.temperature","state":"19.5","attributes":{"friendly_name":"Capteur"}},
          {"entity_id":"lock.porte","state":"locked","attributes":{"friendly_name":"Porte d'entrée"}}
        ]
    """.trimIndent()

    @Test
    fun `states are parsed, and entities without a friendly name fall back to their id`() {
        val entities = parseHaStates(statesBody)
        assertEquals(5, entities.size)
        val salon = entities.first { it.entityId == "light.salon" }
        assertEquals("light", salon.domain)
        assertEquals("Salon", salon.friendlyName)
        assertEquals("on", salon.state)
        val noName = parseHaStates("""[{"entity_id":"switch.x","state":"on","attributes":{}}]""")
        assertEquals("switch.x", noName.single().friendlyName)
    }

    @Test
    fun `a bad payload is refused rather than silently parsed`() {
        org.junit.Assert.assertThrows(Exception::class.java) { parseHaStates("{}") }
        org.junit.Assert.assertThrows(Exception::class.java) { parseHaStates("not json") }
    }

    @Test
    fun `matching a name is accent and case insensitive, restricted to the given domains, exact match first`() {
        val entities = parseHaStates(statesBody)
        assertEquals(listOf("Salon"), findHaEntities(entities, "salon", setOf("light")).map { it.friendlyName })
        assertEquals(listOf("Salon"), findHaEntities(entities, "SALON", setOf("light")).map { it.friendlyName })
        // A sensor is never a match: it is outside the domains passed in, even though "temperature" is in its name.
        assertTrue(findHaEntities(entities, "temperature", setOf("light")).isEmpty())
        // Two "salon" entities exist across domains; querying within HA_CONTROLLABLE_DOMAINS finds both, exact-friendly-name one first.
        val both = findHaEntities(entities, "salon", HA_CONTROLLABLE_DOMAINS)
        assertEquals("Salon", both.first().friendlyName)
        assertEquals(2, both.size)
    }

    @Test
    fun `an empty query matches nothing`() {
        assertTrue(findHaEntities(parseHaStates(statesBody), "  ", HA_CONTROLLABLE_DOMAINS).isEmpty())
    }

    @Test
    fun `set_brightness and set_temperature are scoped to their own domain`() {
        assertEquals(setOf("light"), haDomainsFor("set_brightness"))
        assertEquals(setOf("climate"), haDomainsFor("set_temperature"))
        assertEquals(HA_CONTROLLABLE_DOMAINS, haDomainsFor("turn_on"))
        assertNull(haDomainsFor("explode"))
    }

    @Test
    fun `turn_on turn_off and toggle go through the generic homeassistant service`() {
        val light = parseHaStates(statesBody).first { it.entityId == "light.salon" }
        assertEquals("homeassistant" to "turn_on", haService("turn_on", light))
        assertEquals("homeassistant" to "turn_off", haService("turn_off", light))
        assertEquals("homeassistant" to "toggle", haService("toggle", light))
    }

    @Test
    fun `set_brightness only applies to a light, set_temperature only to a climate entity`() {
        val light = parseHaStates(statesBody).first { it.entityId == "light.salon" }
        val climate = parseHaStates(statesBody).first { it.entityId == "climate.salon" }
        val lock = parseHaStates(statesBody).first { it.entityId == "lock.porte" }
        assertEquals("light" to "turn_on", haService("set_brightness", light))
        assertNull(haService("set_brightness", climate))
        assertEquals("climate" to "set_temperature", haService("set_temperature", climate))
        assertNull(haService("set_temperature", light))
        assertNull(haService("set_brightness", lock))
    }

    @Test
    fun `the service body carries the entity id and only the relevant value`() {
        val light = parseHaStates(statesBody).first { it.entityId == "light.salon" }
        val onBody = haServiceBody("turn_on", light, null)
        assertEquals("light.salon", onBody["entity_id"]?.toString()?.trim('"'))
        assertNull(onBody["brightness_pct"])
        val dimBody = haServiceBody("set_brightness", light, 40)
        assertEquals("40", dimBody["brightness_pct"].toString())
    }

    @Test
    fun `success messages name the device and, for value actions, the value`() {
        val light = parseHaStates(statesBody).first { it.entityId == "light.salon" }
        assertTrue(haSuccessMessage("turn_on", light, null).contains("allumé"))
        assertTrue(haSuccessMessage("turn_off", light, null).contains("éteint"))
        assertTrue(haSuccessMessage("set_brightness", light, 40).contains("40"))
    }

    @Test
    fun `describing an entity adds the brightness percent only for a light that is on`() {
        val entities = parseHaStates(statesBody)
        val onLight = entities.first { it.entityId == "light.salon" }
        val offLight = entities.first { it.entityId == "light.chambre" }
        assertEquals("Salon : allumé (50 %)", describeHaEntity(onLight))
        assertEquals("Chambre : éteint", describeHaEntity(offLight))
        val climate = entities.first { it.entityId == "climate.salon" }
        assertTrue(describeHaEntity(climate).contains("19.5"))
    }

    @Test
    fun `the status list joins one line per entity`() {
        val entities = parseHaStates(statesBody).filter { it.entityId in setOf("light.salon", "light.chambre") }
        assertEquals("Salon : allumé (50 %)\nChambre : éteint", formatHaStatusList(entities))
    }
}
