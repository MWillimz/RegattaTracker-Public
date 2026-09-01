package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceSetupPersistenceTest {

    @Test
    fun legacyDisplayPayload_removesLocalizedLabelPrefix() {
        assertEquals("planned", legacyDisplayPayload("Race: planned"))
        assertEquals("planned", legacyDisplayPayload("Regatta: planned"))
        assertEquals("2026-09-01T12:00:00Z", legacyDisplayPayload("Départ : 2026-09-01T12:00:00Z"))
        assertEquals("2026-09-01T12:00:00Z", legacyDisplayPayload("Salida: 2026-09-01T12:00:00Z"))
    }

    @Test
    fun legacyDisplayPayload_preservesColonsInsidePayload() {
        assertEquals(
            "2026-09-01T12:00:00+02:00",
            legacyDisplayPayload("Start: 2026-09-01T12:00:00+02:00")
        )
    }

    @Test
    fun legacyDisplayPayload_preservesUnprefixedValues() {
        assertEquals("planned", legacyDisplayPayload("planned"))
        assertEquals("--", legacyDisplayPayload("--"))
    }

    @Test
    fun legacyMigration_preservesShortenedCourseAndStructuredMarks() {
        val migrated = migrateLegacyRaceDisplayState(
            raceStatusText = "Race: racing",
            raceStartText = "Start: 2026-09-01T12:00:00Z",
            raceStopText = "Stop: --",
            raceInfoText = "Info: shortened at mark 2",
            raceShortenedText = "Course shortened: YES",
            raceMarksText = "Marks: 1 A, 2 B [skipped], 3 C"
        )

        assertEquals("racing", migrated.raceStatus)
        assertEquals("2026-09-01T12:00:00Z", migrated.raceStart)
        assertEquals("--", migrated.raceStop)
        assertEquals("shortened at mark 2", migrated.raceInfo)
        assertTrue(migrated.courseShortened)
        assertEquals(
            listOf(
                LegacyCourseMarkState(order = 1, label = "1 A", skipped = false),
                LegacyCourseMarkState(order = 2, label = "2 B", skipped = true),
                LegacyCourseMarkState(order = 3, label = "3 C", skipped = false)
            ),
            migrated.courseMarks
        )
    }

    @Test
    fun legacyMigration_recognizesLocalizedShortenedAndSkippedMarkers() {
        assertTrue(legacyCourseShortened("Kurs verkürzt: JA"))
        assertTrue(legacyCourseShortened("Parcours raccourci : OUI"))
        assertTrue(legacyCourseShortened("Percorso ridotto: SÌ"))
        assertTrue(legacyCourseShortened("Recorrido acortado: SÍ"))
        assertFalse(legacyCourseShortened("Course shortened: no"))

        val localizedMarks = listOf(
            "Bahnmarken: 1 A, 2 B [übersprungen]" to "2 B",
            "Marques : 1 A, 2 B [omise]" to "2 B",
            "Boe: 1 A, 2 B [omessa]" to "2 B",
            "Balizas: 1 A, 2 B [omitida]" to "2 B"
        )

        localizedMarks.forEach { (display, expectedSkippedLabel) ->
            val marks = legacyCourseMarkStates(display)
            assertEquals(2, marks.size)
            assertFalse(marks[0].skipped)
            assertTrue(marks[1].skipped)
            assertEquals(expectedSkippedLabel, marks[1].label)
            assertEquals(2, marks[1].order)
        }
    }
}
