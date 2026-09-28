package de.williserv.regattaclient

import android.content.res.Configuration
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RegattaLinkUiMessageTest {

    @Test
    fun everyTypedMessageMapsToAStringResource() {
        RegattaLinkUiMessage.entries.forEach { message ->
            assertNotEquals(
                "Missing string mapping for $message",
                0,
                regattaLinkUiMessageResource(message)
            )
        }
    }

    @Test
    fun representativeRuntimeMessagesAreLocalized() {
        val app = RuntimeEnvironment.getApplication()
        val messages = listOf(
            RegattaLinkUiMessage.CONNECTION_TIMEOUT,
            RegattaLinkUiMessage.NMEA_RAW_CAN_READ_FAILED,
            RegattaLinkUiMessage.RAW_CAPTURE_START_FAILED,
            RegattaLinkUiMessage.OTA_WAIT_FACTORY_RESET,
            RegattaLinkUiMessage.FIRMWARE_CHECK_FAILED
        )
        val expected = linkedMapOf(
            "en" to listOf(
                "RegattaLink connection timed out.",
                "Could not read raw CAN frames.",
                "Could not start raw CAN capture.",
                "Wait for Factory Reset to finish before updating firmware.",
                "Firmware check failed."
            ),
            "de" to listOf(
                "Zeitüberschreitung bei der RegattaLink-Verbindung.",
                "Raw-CAN-Frames konnten nicht gelesen werden.",
                "Raw-CAN-Aufzeichnung konnte nicht gestartet werden.",
                "Vor dem Firmware-Update den Factory Reset abschließen lassen.",
                "Firmware-Prüfung fehlgeschlagen."
            ),
            "es" to listOf(
                "Se agotó el tiempo de conexión con RegattaLink.",
                "No se pudieron leer las tramas CAN sin procesar.",
                "No se pudo iniciar la captura CAN.",
                "Espera a que termine el restablecimiento de fábrica antes de actualizar el firmware.",
                "La comprobación del firmware ha fallado."
            ),
            "fr" to listOf(
                "Délai de connexion au RegattaLink dépassé.",
                "Impossible de lire les trames CAN brutes.",
                "Impossible de démarrer la capture CAN brute.",
                "Attendez la fin de la réinitialisation d’usine avant de mettre à jour le firmware.",
                "La vérification du firmware a échoué."
            ),
            "it" to listOf(
                "Tempo scaduto per la connessione a RegattaLink.",
                "Impossibile leggere i frame CAN grezzi.",
                "Impossibile avviare l’acquisizione CAN grezza.",
                "Attendi il completamento del ripristino di fabbrica prima di aggiornare il firmware.",
                "Controllo del firmware non riuscito."
            )
        )

        expected.forEach { (languageTag, expectedStrings) ->
            val configuration = Configuration(app.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(languageTag))
            }
            val resources = app.createConfigurationContext(configuration).resources
            assertEquals(
                expectedStrings,
                messages.map { message ->
                    resources.getString(regattaLinkUiMessageResource(message))
                }
            )
        }
    }
}
