package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseProgressMarkStateTest {

    @Test
    fun skippedMarksRemainStructuredAndDoNotAdvanceProgress() {
        val states = buildCourseProgressMarkStates(
            listOf(
                CourseMapMark(order = 1, label = "1 A", skipped = false),
                CourseMapMark(order = 2, label = "2 B", skipped = true),
                CourseMapMark(order = 3, label = "3 C", skipped = false)
            )
        )

        assertEquals(3, states.size)

        assertEquals("1 A", states[0].label)
        assertEquals(0, states[0].passedMarks)
        assertFalse(states[0].skipped)

        assertEquals("2 B", states[1].label)
        assertEquals(0, states[1].passedMarks)
        assertTrue(states[1].skipped)

        assertEquals("3 C", states[2].label)
        assertEquals(1, states[2].passedMarks)
        assertFalse(states[2].skipped)
    }

    @Test
    fun displayLabelsDoNotControlSkippedState() {
        val states = buildCourseProgressMarkStates(
            listOf(
                CourseMapMark(order = 1, label = "1 Tonne [übersprungen]", skipped = false),
                CourseMapMark(order = 2, label = "2 Bouée [skipped]", skipped = true)
            )
        )

        assertFalse(states[0].skipped)
        assertTrue(states[1].skipped)
    }
    @Test
    fun localizedDisplayFallbackRecognizesLocalizedSkippedMarker() {
        val items = courseMarkDisplayItems(
            raceMarksText = "Bahnmarken: 1 Tonne [übersprungen], 2 Tonne",
            marksPrefix = "Bahnmarken:",
            skippedMarker = "[übersprungen]"
        )

        assertEquals("1 Tonne", items[0].label)
        assertTrue(items[0].skipped)
        assertEquals("2 Tonne", items[1].label)
        assertFalse(items[1].skipped)
    }

}
