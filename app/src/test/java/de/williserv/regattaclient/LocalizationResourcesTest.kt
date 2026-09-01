package de.williserv.regattaclient

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertEquals("Ausschreibung", resources.getString(R.string.race_notice))
        assertEquals("frei", resources.getString(R.string.clear_status))
    }

    @Test
    @Config(qualifiers = "fr")
    fun frenchLocale_usesFrenchResources() {
        val resources = RuntimeEnvironment.getApplication().resources

        assertEquals("Éditeur :", resources.getString(R.string.legal_notice_body).lineSequence().first())
        assertEquals("Retour", resources.getString(R.string.back))
        assertEquals("Course : non chargée", resources.getString(R.string.race_not_loaded))
        assertEquals("en règle", resources.getString(R.string.clear_status))
        assertEquals("Bouée — omise", resources.getString(R.string.mark_skipped, "Bouée"))
    }

    @Test
    @Config(qualifiers = "it")
    fun italianLocale_usesItalianResources() {
        val resources = RuntimeEnvironment.getApplication().resources

        assertEquals("Fornitore:", resources.getString(R.string.legal_notice_body).lineSequence().first())
        assertEquals("Indietro", resources.getString(R.string.back))
        assertEquals("Regata: non caricata", resources.getString(R.string.race_not_loaded))
        assertEquals("Bando di regata", resources.getString(R.string.race_notice))
        assertEquals("Tracciamento manuale interrotto", resources.getString(R.string.manual_tracking_stopped))
    }

    @Test
    @Config(qualifiers = "es")
    fun spanishLocale_usesSpanishResources() {
        val resources = RuntimeEnvironment.getApplication().resources

        assertEquals("Proveedor:", resources.getString(R.string.legal_notice_body).lineSequence().first())
        assertEquals("Atrás", resources.getString(R.string.back))
        assertEquals("Regata: no cargada", resources.getString(R.string.race_not_loaded))
        assertEquals("Anuncio de regata", resources.getString(R.string.race_notice))
        assertEquals("Envío", resources.getString(R.string.upload))
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

    @Test
    fun localizedResourceFiles_matchTranslatableDefaultKeysAndPlaceholders() {
        val resRoot = sequenceOf(
            File("src/main/res"),
            File("app/src/main/res")
        ).firstOrNull { File(it, "values/strings.xml").isFile }

        assertTrue("Could not locate Android string resources", resRoot != null)
        val root = requireNotNull(resRoot)
        val defaultStrings = readStringResources(
            file = File(root, "values/strings.xml"),
            includeNonTranslatable = false
        )

        listOf("values-de", "values-fr", "values-it", "values-es").forEach { localeDir ->
            val localizedStrings = readStringResources(File(root, "$localeDir/strings.xml"))
            assertEquals("String key mismatch in $localeDir", defaultStrings.keys, localizedStrings.keys)

            defaultStrings.forEach { (key, defaultValue) ->
                assertEquals(
                    "Format placeholder mismatch for $key in $localeDir",
                    formatPlaceholders(defaultValue),
                    formatPlaceholders(localizedStrings.getValue(key))
                )
            }
        }
    }

    private fun readStringResources(
        file: File,
        includeNonTranslatable: Boolean = true
    ): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val nodes = document.getElementsByTagName("string")
        val strings = linkedMapOf<String, String>()

        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
            val explicitlyNonTranslatable =
                node.attributes?.getNamedItem("translatable")?.nodeValue == "false"

            if (!includeNonTranslatable && explicitlyNonTranslatable) {
                continue
            }

            strings[name] = node.textContent
        }

        return strings
    }

    private fun formatPlaceholders(value: String): List<String> {
        return FORMAT_PLACEHOLDER_REGEX.findAll(value)
            .map { it.value }
            .toList()
    }

    companion object {
        private val FORMAT_PLACEHOLDER_REGEX =
            Regex("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%]")
    }
}
