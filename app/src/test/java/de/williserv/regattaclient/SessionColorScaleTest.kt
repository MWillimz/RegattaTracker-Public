package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionColorScaleTest {

    @Test
    fun colorFractionClampsToConfiguredRange() {
        assertEquals(0f, sessionColorFraction(-5.0, 0.0, 10.0)!!, 0.0001f)
        assertEquals(0.5f, sessionColorFraction(5.0, 0.0, 10.0)!!, 0.0001f)
        assertEquals(1f, sessionColorFraction(15.0, 0.0, 10.0)!!, 0.0001f)
    }

    @Test
    fun absoluteColoringUsesMagnitudeAndObservedRange() {
        val values = listOf(-20.0, -5.0, 10.0)

        assertEquals(20.0, sessionColorValue(-20.0, true)!!, 0.0001)
        assertEquals(20.0, sessionColorValue(20.0, true)!!, 0.0001)

        val observed = sessionColorObservedRange(values, useAbsoluteValue = true)
        assertEquals(5.0, observed!!.start, 0.0001)
        assertEquals(20.0, observed.endInclusive, 0.0001)

        assertTrue(sessionColorHasNegativeValue(values))
        assertFalse(sessionColorHasNegativeValue(listOf(0.0, 5.0, 10.0)))
    }

    @Test
    fun selectedColorRangeIsClampedToObservedValues() {
        val clamped = clampSessionColorRange(
            selectedRange = -5f..15f,
            observedRange = 0.0..10.0
        )

        assertEquals(0f, clamped!!.start, 0.0001f)
        assertEquals(10f, clamped.endInclusive, 0.0001f)
    }
}
