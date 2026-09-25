package com.jarvis.android.parking

import com.jarvis.android.offline.OfflineAction
import com.jarvis.android.offline.interpret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ParkingTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `the direction is said as a compass point`() {
        // From Paris: London is to the north-west, Lyon to the south-east, Strasbourg to the east.
        assertEquals("au nord-ouest", directionWord(48.8566, 2.3522, 51.5074, -0.1278))
        assertEquals("au sud-est", directionWord(48.8566, 2.3522, 45.764, 4.8357))
        assertEquals("à l'est", directionWord(48.8566, 2.3522, 48.5734, 7.7521))
        assertEquals("au nord", directionWord(48.0, 2.0, 48.01, 2.0))
    }

    @Test
    fun `distances and durations read naturally`() {
        assertEquals("à 350 m", distanceWords(0.356))
        assertEquals("à 1,2 km", distanceWords(1.24))
        assertEquals("il y a 25 min", sinceWords(0, 25 * 60_000L))
        assertEquals("il y a 3 h", sinceWords(0, 3 * 3_600_000L + 5))
        assertEquals("il y a 2 jours", sinceWords(0, 50 * 3_600_000L))
    }

    @Test
    fun `the store keeps the car and the chosen Bluetooth device`() {
        val s = ParkingStore(tmp.newFile())
        assertNull(s.load().car)
        s.update { it.copy(car = ParkedCar(48.0, 2.0, 10, "Paris"), autoSave = true, carBluetoothAddress = "AA:BB") }
        val d = s.load()
        assertEquals("Paris", d.car?.label)
        assertEquals("AA:BB", d.carBluetoothAddress)
        s.update { it.copy(car = null) }
        assertNull(s.load().car)
        assertEquals(true, s.load().autoSave)
    }

    private fun call(text: String) = interpret(text) as OfflineAction.ToolCall

    @Test
    fun `offline, saving and finding the car are understood`() {
        assertEquals("save", call("Retiens où je me suis garé").args["action"])
        assertEquals("niveau -2, place 45", call("retiens où je me suis garé niveau -2, place 45").args["note"])
        assertEquals("save", call("Je me suis garé ici").args["action"])
        assertEquals("find", call("Où est ma voiture ?").args["action"])
        assertEquals("true", call("Ramène-moi à ma voiture").args["open"])
    }
}
