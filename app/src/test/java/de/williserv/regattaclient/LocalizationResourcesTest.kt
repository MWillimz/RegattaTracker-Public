package de.williserv.regattaclient

import android.content.res.Configuration
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

    private fun readStringResources(file: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val nodes = document.getElementsByTagName("string")
        val strings = linkedMapOf<String, String>()

        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
            strings[name] = node.textContent
        }

        return strings
    }

    companion object {
        private val LEGACY_BOAT_DATA_BRANDING_REGEX =
            Regex("\\bNMEA(?:\\s*2000)?\\b", RegexOption.IGNORE_CASE)
    }
}
