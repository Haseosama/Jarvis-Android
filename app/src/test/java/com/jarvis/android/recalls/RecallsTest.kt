package com.jarvis.android.recalls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class RecallsTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val json = """{"total_count":4,"results":[
      {"numero_fiche":"2026-09-0412","libelle":"Comté râpé 200 g","marque_produit":"Les Prés","categorie_produit":"Alimentation",
       "sous_categorie_produit":"Lait et produits laitiers","motif_rappel":"Présence de Listeria monocytogenes. Analyse en cours.",
       "risques_encourus":"Listeria monocytogenes (agent responsable de la listériose)","conduites_a_tenir_par_le_consommateur":"Ne plus consommer. Rapporter le produit au point de vente.",
       "distributeurs":"Lidl, Carrefour","date_publication":"2026-09-30T08:12:00+00:00","gtin":3560070000001,
       "lien_vers_la_fiche_rappel":"https://rappel.conso.gouv.fr/fiche-rappel/20412/Interne"},
      {"numero_fiche":"2026-09-0412","libelle":"Comté râpé 200 g","marque_produit":"Les Prés","categorie_produit":"Alimentation",
       "motif_rappel":"Présence de Listeria monocytogenes.","date_publication":"2026-09-30T08:12:00+00:00","gtin":3560070000002},
      {"numero_fiche":"2026-09-0398","libelle":"Hochet lapin","marque_produit":"Petitou","categorie_produit":"Bébés-Enfants (hors alimentaire)",
       "sous_categorie_produit":"Jouets","motif_rappel":"Petites pièces pouvant se détacher","date_publication":"2026-09-28"},
      {"numero_fiche":"2026-08-0100","libelle":"Trottinette X2","marque_produit":"Rido","categorie_produit":"Automobiles et moyens de déplacement",
       "motif_rappel":"Rupture possible de la potence","date_publication":"2026-08-02T10:00:00+00:00"},
      {"libelle":"","marque_produit":""}]}"""

    @Test fun `the records, one per sheet`() {
        val all = parseRecalls(json)
        assertEquals(listOf("2026-09-0412", "2026-09-0398", "2026-08-0100"), all.map { it.id }) // the second barcode merged, the empty one dropped
        val comte = all[0]
        assertEquals("Alimentation", comte.category)
        assertEquals(ms("2026-09-30T08:12:00Z"), comte.publishedMs)
        assertEquals(ms("2026-09-27T22:00:00Z"), all[1].publishedMs) // a bare date, midnight in Paris
        assertTrue(parseRecalls("not json").isEmpty())
        assertTrue(parseRecalls("""{"results":[]}""").isEmpty())
    }

    @Test fun `followed words catch categories, brands and products`() {
        val (comte, hochet, trottinette) = parseRecalls(json)
        assertTrue(recallMatches(comte, "alimentation"))
        assertTrue(recallMatches(comte, "alimentaire")) // the everyday word
        assertTrue(recallMatches(comte, "Les Prés"))
        assertTrue(recallMatches(comte, "comté"))
        assertFalse(recallMatches(comte, "jouets"))
        assertTrue(recallMatches(hochet, "jouets"))
        assertTrue(recallMatches(hochet, "bébé"))
        assertTrue(recallMatches(trottinette, "voiture"))
        assertFalse(recallMatches(trottinette, "alimentation"))
        assertFalse(recallMatches(comte, "  "))
    }

    @Test fun `new recalls are those after the watch began, under a followed word, not yet told`() {
        val all = parseRecalls(json)
        val since = ms("2026-09-01T00:00:00Z")
        assertEquals(listOf("2026-09-0412", "2026-09-0398"), newRecallsFor(all, listOf("alimentation", "jouets", "voiture"), emptySet(), since).map { it.id })
        assertEquals(listOf("2026-09-0398"), newRecallsFor(all, listOf("alimentation", "jouets"), setOf("2026-09-0412"), since).map { it.id })
        assertTrue(newRecallsFor(all, emptyList(), emptySet(), 0L).isEmpty())
    }

    @Test fun `the words said`() {
        val all = parseRecalls(json)
        assertEquals(
            "Oui, un rappel pour « comté » : Comté râpé 200 g de Les Prés (alimentation), rappelé le 30 septembre 2026 ; " +
                "motif : Présence de Listeria monocytogenes ; risque : Listeria monocytogenes (agent responsable de la listériose) ; " +
                "à faire : Ne plus consommer ; vendu chez Lidl, Carrefour.",
            checkWords("comté", all.take(1), paris),
        )
        assertEquals("Aucun rappel trouvé sur RappelConso pour « yaourt ».", checkWords("yaourt", emptyList(), paris))
        assertTrue(checkWords("x", all, paris).startsWith("Oui, 3 rappels pour « x » (les plus récents) : Comté râpé"))
        assertEquals(
            "Derniers rappels (jouets) : Hochet lapin de Petitou (bébés-enfants (hors alimentaire)), rappelé le 28 septembre 2026 ; motif : Petites pièces pouvant se détacher.",
            recentWords(all.filter { recallMatches(it, "jouets") }, "jouets", paris),
        )
        assertEquals("Aucun rappel récent sur RappelConso.", recentWords(emptyList(), "", paris))
        assertEquals("\"lait  demi-écrémé\"", searchWhere("lait \"demi-écrémé\""))
    }
}
