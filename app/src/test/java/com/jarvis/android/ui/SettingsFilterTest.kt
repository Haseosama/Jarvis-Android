package com.jarvis.android.ui

import com.jarvis.android.actions.readTextAnswer
import com.jarvis.android.actions.spokenReadText
import com.jarvis.android.offline.OfflineAction
import com.jarvis.android.offline.interpret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFilterTest {
    @Test
    fun `cards are found by title, by keyword, in both languages, and narrowed by theme`() {
        val all = SettingsFilter()
        assertTrue(all.accepts("Transports", "Transports"))
        assertFalse(all.active)
        assertTrue(SettingsFilter("train").accepts("Transports", "Transport"))
        assertTrue(SettingsFilter("clé").accepts("Clés API et modèles", "API keys and models"))
        assertTrue(SettingsFilter("keys").accepts("Clés API et modèles", "API keys and models"))
        assertTrue(SettingsFilter("batterie").accepts("Mot d’activation (« Hey Jarvis »)", "Wake word (« Hey Jarvis »)"))
        assertFalse(SettingsFilter("train").accepts("Colis", "Parcels"))
        assertTrue(SettingsFilter(group = SettingsGroup.CAR).accepts("Mode conduite", "Driving mode"))
        assertFalse(SettingsFilter(group = SettingsGroup.CAR).accepts("Colis", "Parcels"))
        assertFalse(SettingsFilter("colis", SettingsGroup.CAR).accepts("Colis", "Parcels"))
    }

    @Test
    fun `text read through the camera, for the model and offline`() {
        val a = readTextAnswer(listOf("SORTIE DE SECOURS", "Emergency exit"), "")
        assertTrue(a.startsWith("Texte lu sur la photo (2 lignes) :\nSORTIE DE SECOURS\nEmergency exit\n(Lisez-le"))
        assertEquals("SORTIE DE SECOURS\nEmergency exit", spokenReadText(a))
        assertTrue(readTextAnswer(listOf("Menu"), "français").contains("Traduisez-le en français."))
        assertTrue(readTextAnswer(List(300) { "ligne numéro $it du document" }, "").contains("[…]"))
        assertEquals("Pas de photo.", spokenReadText("Pas de photo."))
        assertEquals("read_text", (interpret("Qu'est-ce qui est écrit là ?") as OfflineAction.ToolCall).name)
        assertEquals("read_text", (interpret("lis-moi cette notice") as OfflineAction.ToolCall).name)
    }
}
