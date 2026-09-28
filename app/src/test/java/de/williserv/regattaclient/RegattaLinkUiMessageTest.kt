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
            RegattaLinkUiMessage.CALIBRATION_MOTION_REJECTED,
            RegattaLinkUiMessage.CALIBRATION_ORIENTATION_REJECTED,
            RegattaLinkUiMessage.NMEA_RAW_CAN_READ_FAILED,
            RegattaLinkUiMessage.RAW_CAPTURE_START_FAILED,
            RegattaLinkUiMessage.OTA_WAIT_FACTORY_RESET,
            RegattaLinkUiMessage.FIRMWARE_CHECK_FAILED
        )
        val expected = linkedMapOf(
            "en" to listOf(
                "RegattaLink connection timed out.",
                "Calibration failed because the boat moved. Keep the boat still and try again.",
                "Calibration failed because the sensor orientation is invalid.",
                "Could not read raw CAN frames.",
                "Could not start raw CAN capture.",
                "Wait for Factory Reset to finish before updating firmware.",
                "Firmware check failed."
            ),
            "de" to listOf(
                "Zeitüberschreitung bei der RegattaLink-Verbindung.",
                "Kalibrierung fehlgeschlagen, weil sich das Boot bewegt hat. Boot ruhig halten und erneut versuchen.",
                "Kalibrierung fehlgeschlagen, weil die Sensorausrichtung ungültig ist.",
                "Raw-CAN-Frames konnten nicht gelesen werden.",
                "Raw-CAN-Aufzeichnung konnte nicht gestartet werden.",
                "Vor dem Firmware-Update den Factory Reset abschließen lassen.",
                "Firmware-Prüfung fehlgeschlagen."
            ),
            "es" to listOf(
                "Se agotó el tiempo de conexión con RegattaLink.",
                "La calibración falló porque el barco se movió. Mantén el barco quieto y vuelve a intentarlo.",
                "La calibración falló porque la orientación del sensor no es válida.",
                "No se pudieron leer las tramas CAN sin procesar.",
                "No se pudo iniciar la captura CAN.",
                "Espera a que termine el restablecimiento de fábrica antes de actualizar el firmware.",
                "La comprobación del firmware ha fallado."
            ),
            "fr" to listOf(
                "Délai de connexion au RegattaLink dépassé.",
                "L’étalonnage a échoué car le bateau a bougé. Immobilisez le bateau et réessayez.",
                "L’étalonnage a échoué car l’orientation du capteur n’est pas valide.",
                "Impossible de lire les trames CAN brutes.",
                "Impossible de démarrer la capture CAN brute.",
                "Attendez la fin de la réinitialisation d’usine avant de mettre à jour le firmware.",
                "La vérification du firmware a échoué."
            ),
            "it" to listOf(
                "Tempo scaduto per la connessione a RegattaLink.",
                "La calibrazione non è riuscita perché l’imbarcazione si è mossa. Mantienila ferma e riprova.",
                "La calibrazione non è riuscita perché l’orientamento del sensore non è valido.",
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
