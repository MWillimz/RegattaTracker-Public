package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class LocalizationResourcesTest {

    @Test
    @Config(qualifiers = "de")
    fun germanLocale_usesGermanResources() {
        val resources = RuntimeEnvironment.getApplication().resources

        assertEquals("Anbieter:", resources.getString(R.string.legal_notice_body).lineSequence().first())
        assertEquals("Zurück", resources.getString(R.string.back))
        assertEquals("Regatta: nicht geladen", resources.getString(R.string.race_not_loaded))
    }

    @Test
    @Config(qualifiers = "fr")
    fun frenchLocale_usesFrenchResources() {
        val resources = RuntimeEnvironment.getApplication().resources

        assertEquals("Éditeur :", resources.getString(R.string.legal_notice_body).lineSequence().first())
        assertEquals("Retour", resources.getString(R.string.back))
        assertEquals("Course : non chargée", resources.getString(R.string.race_not_loaded))
    }

    @Test
    @Config(qualifiers = "it")
    fun italianLocale_usesItalianResources() {
        val resources = RuntimeEnvironment.getApplication().resources

        assertEquals("Fornitore:", resources.getString(R.string.legal_notice_body).lineSequence().first())
        assertEquals("Indietro", resources.getString(R.string.back))
        assertEquals("Regata: non caricata", resources.getString(R.string.race_not_loaded))
    }

    @Test
    @Config(qualifiers = "es")
    fun spanishLocale_usesSpanishResources() {
        val resources = RuntimeEnvironment.getApplication().resources

        assertEquals("Proveedor:", resources.getString(R.string.legal_notice_body).lineSequence().first())
        assertEquals("Atrás", resources.getString(R.string.back))
        assertEquals("Regata: no cargada", resources.getString(R.string.race_not_loaded))
    }

    @Test
    @Config(qualifiers = "nl")
    fun unsupportedLocale_fallsBackToEnglishDefaults() {
        val resources = RuntimeEnvironment.getApplication().resources

        assertEquals("Back", resources.getString(R.string.back))
        assertEquals("Race: not loaded", resources.getString(R.string.race_not_loaded))
    }

    @Test
    @Config(qualifiers = "de")
    fun formattedResources_preservePlaceholderTypesAndOrder() {
        val resources = RuntimeEnvironment.getApplication().resources

        assertEquals(
            "Regatta: Testregatta",
            resources.getString(R.string.race_value, "Testregatta")
        )
        assertEquals(
            "Fortschritt: 1/3 Bahnmarken · 33%",
            resources.getString(R.string.progress_value, 1, 3, 33.0)
        )
        assertEquals(
            "Übertragung: aktiv · 7 ausstehend",
            resources.getString(R.string.upload_worker_pending, "aktiv", 7)
        )
    }
}
