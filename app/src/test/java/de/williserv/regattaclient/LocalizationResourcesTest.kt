package de.williserv.regattaclient

import android.content.res.Configuration
import android.text.TextPaint
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class LocalizationResourcesTest {

    @Test
    fun regattaLinkNameValidationMessages_areLocalizedInSupportedLocales() {
        val app = RuntimeEnvironment.getApplication()
        val expectedByLocale = linkedMapOf(
            "en" to listOf(
                "Name must not be empty",
                "Name must be at most 24 UTF-8 bytes",
                "Name contains unsupported control characters"
            ),
            "de" to listOf(
                "Name darf nicht leer sein",
                "Name darf höchstens 24 UTF-8-Bytes lang sein",
                "Name enthält nicht unterstützte Steuerzeichen"
            ),
            "es" to listOf(
                "El nombre no puede estar vacío",
                "El nombre debe tener como máximo 24 bytes UTF-8",
                "El nombre contiene caracteres de control no compatibles"
            ),
            "fr" to listOf(
                "Le nom ne doit pas être vide",
                "Le nom doit contenir au maximum 24 octets UTF-8",
                "Le nom contient des caractères de contrôle non pris en charge"
            ),
            "it" to listOf(
                "Il nome non può essere vuoto",
                "Il nome deve contenere al massimo 24 byte UTF-8",
                "Il nome contiene caratteri di controllo non supportati"
            )
        )

        expectedByLocale.forEach { (languageTag, expected) ->
            val configuration = Configuration(app.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(languageTag))
            }
            val resources = app.createConfigurationContext(configuration).resources
            assertEquals(
                expected,
                listOf(
                    resources.getString(
                        R.string.regattalink_name_validation_empty
                    ),
                    resources.getString(
                        R.string.regattalink_name_validation_too_long_utf8
                    ),
                    resources.getString(
                        R.string.regattalink_name_validation_unsupported_control_character
                    )
                )
            )
        }
    }

    @Test
    @Config(qualifiers = "de")
    fun germanLocale_usesGermanResources() {
        val resources = RuntimeEnvironment.getApplication().resources

        assertEquals("Anbieter:", resources.getString(R.string.legal_notice_body).lineSequence().first())
        assertEquals("Zurück", resources.getString(R.string.back))
        assertEquals("Regatta: nicht geladen", resources.getString(R.string.race_not_loaded))
        assertEquals("Ausschreibung", resources.getString(R.string.race_notice))
        assertEquals("frei", resources.getString(R.string.clear_status))
        assertEquals("Hash der Ausschreibung fehlt", resources.getString(R.string.race_notice_hash_missing))
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
        assertEquals("non active", resources.getString(R.string.status_not_active))
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
        assertEquals("Il bando di regata è vuoto", resources.getString(R.string.race_notice_empty))
        assertEquals("Regata: accetta prima il bando di regata", resources.getString(R.string.race_accept_legal_first))
        assertEquals("non attiva", resources.getString(R.string.status_not_active))
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
        assertEquals("no activa", resources.getString(R.string.status_not_active))
    }

    @Test
    @Config(qualifiers = "nl")
    fun unsupportedLocale_fallsBackToEnglishDefaults() {
        val resources = RuntimeEnvironment.getApplication().resources

        assertEquals("Back", resources.getString(R.string.back))
        assertEquals("Race: not loaded", resources.getString(R.string.race_not_loaded))
        assertEquals("Legal Notice", resources.getString(R.string.impressum))
        assertEquals("Privacy Policy", resources.getString(R.string.datenschutz))
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
    fun configurationContexts_switchLocalesWithoutStaleValues() {
        val app = RuntimeEnvironment.getApplication()
        val localeSequence = listOf(
            "en" to "Back",
            "de" to "Zurück",
            "fr" to "Retour",
            "it" to "Indietro",
            "es" to "Atrás",
            "de" to "Zurück"
        )

        localeSequence.forEach { (languageTag, expected) ->
            val configuration = Configuration(app.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(languageTag))
            }
            val resources = app.createConfigurationContext(configuration).resources

            assertEquals(expected, resources.getString(R.string.back))
        }
    }

    @Test
    fun compactControlLabels_fitConservativeWidthGuardrailsInAllSupportedLocales() {
        val app = RuntimeEnvironment.getApplication()
        val density = app.resources.displayMetrics.density
        val scaledDensity = app.resources.displayMetrics.scaledDensity
        val paint = TextPaint().apply {
            textSize = 16f * scaledDensity
        }
        val widthBudgetsDp = linkedMapOf(
            R.string.back to 120f,
            R.string.advanced to 140f,
            R.string.hide to 120f,
            R.string.legal_about to 220f,
            R.string.results to 160f,
            R.string.course to 160f,
            R.string.map to 160f,
            R.string.setup to 160f,
            R.string.stop_tracking to 220f,
            R.string.i_agree to 180f,
            R.string.cancel to 160f,
            R.string.delete to 160f,
            R.string.confirm_override to 240f,
            R.string.show_qr_code to 200f
        )

        listOf("en", "de", "fr", "it", "es").forEach { languageTag ->
            val configuration = Configuration(app.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(languageTag))
            }
            val resources = app.createConfigurationContext(configuration).resources

            widthBudgetsDp.forEach { (resId, maxWidthDp) ->
                val label = resources.getString(resId)
                val measuredWidthDp = paint.measureText(label) / density

                assertFalse(
                    "Compact label contains a newline for $languageTag: '$label'",
                    label.contains('\n')
                )
                assertTrue(
                    "Compact label exceeds ${maxWidthDp}dp guardrail for $languageTag: '$label' (${measuredWidthDp}dp)",
                    measuredWidthDp <= maxWidthDp
                )
            }
        }
    }

    @Test
    fun regattaLinkUserFacingStrings_useBoatDataTerminology() {
        val resRoot = sequenceOf(
            File("src/main/res"),
            File("app/src/main/res")
        ).firstOrNull { File(it, "values/strings.xml").isFile }

        assertTrue("Could not locate Android string resources", resRoot != null)
        val root = requireNotNull(resRoot)

        listOf("values", "values-de", "values-fr", "values-it", "values-es").forEach { localeDir ->
            val strings = readStringResources(File(root, "$localeDir/strings.xml"))
            strings
                .filterKeys { it.startsWith("regattalink_") }
                .forEach { (key, value) ->
                    assertFalse(
                        "Legacy protocol branding leaked into $key in $localeDir: '$value'",
                        LEGACY_BOAT_DATA_BRANDING_REGEX.containsMatchIn(value)
                    )
                }
        }
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
        private val LEGACY_BOAT_DATA_BRANDING_REGEX =
            Regex("\\bNMEA(?:\\s*2000)?\\b", RegexOption.IGNORE_CASE)
    }
}
