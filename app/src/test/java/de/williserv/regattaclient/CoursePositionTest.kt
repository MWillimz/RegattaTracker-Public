package de.williserv.regattaclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoursePositionTest {

    @Test
    fun legacyMarkWithoutTypeParsesAsMark() {
        val positions = parseCoursePositions(
            JSONObject(
                """
                {
                  "marks": [{
                    "order": 2,
                    "name": "Legacy",
                    "lat": 53.1,
                    "lon": 10.2,
                    "radius_m": 75,
                    "omit_when_shortened": true
                  }]
                }
                """.trimIndent()
            )
        )

        assertEquals(1, positions.size)
        val position = positions.single()
        assertEquals(CoursePositionKind.MARK, position.kind)
        assertEquals(2, position.order)
        assertEquals("Legacy", position.name)
        assertEquals(GeoPoint(53.1, 10.2), position.markPoint)
        assertEquals(75.0, position.radiusM)
        assertTrue(position.omitWhenShortened)
    }

    @Test
    fun explicitMarkKeepsExistingSemantics() {
        val position = parseCoursePositions(
            JSONObject(
                """
                {
                  "marks": [{
                    "order": 1,
                    "type": "mark",
                    "name": "Windward",
                    "lat": 54.0,
                    "lon": 10.0
                  }]
                }
                """.trimIndent()
            )
        ).single()

        assertEquals(CoursePositionKind.MARK, position.kind)
        assertEquals(GeoPoint(54.0, 10.0), position.referencePoint())
        assertEquals(100.0, position.radiusM)
    }

    @Test
    fun gatePreservesContractAndUsesMidpointAsReference() {
        val position = parseCoursePositions(
            JSONObject(
                """
                {
                  "marks": [{
                    "order": 3,
                    "type": "gate",
                    "name": "Leegate",
                    "ref": {"lat": 53.14, "lon": 10.139, "label": "Gate links"},
                    "mark": {"lat": 53.14, "lon": 10.141, "label": "Gate rechts"},
                    "direction": "negative",
                    "offset_m": 30,
                    "omit_when_shortened": false
                  }]
                }
                """.trimIndent()
            )
        ).single()

        assertEquals(CoursePositionKind.GATE, position.kind)
        assertEquals(GeoPoint(53.14, 10.139), position.gateRef)
        assertEquals(GeoPoint(53.14, 10.141), position.gateMark)
        assertEquals(GateDirection.NEGATIVE, position.gateDirection)
        assertEquals(30.0, position.gateOffsetM)
        assertEquals(GeoPoint(53.14, 10.14), position.referencePoint())
        assertFalse(position.omitWhenShortened)
    }

    @Test
    fun invalidGateGeometryStaysInPositionListAndFailsClosed() {
        val position = parseCoursePositions(
            JSONObject(
                """
                {
                  "marks": [{
                    "order": 1,
                    "type": "gate",
                    "name": "Broken",
                    "ref": {"lat": 54.0, "lon": 10.0},
                    "mark": {"lat": 999.0, "lon": 10.1},
                    "direction": "positive",
                    "offset_m": -1
                  }]
                }
                """.trimIndent()
            )
        ).single()

        assertEquals(CoursePositionKind.GATE, position.kind)
        assertEquals(GeoPoint(54.0, 10.0), position.gateRef)
        assertNull(position.gateMark)
        assertNull(position.gateOffsetM)
        assertNull(position.referencePoint())
        assertNull(buildCourseGateDetectionGeometry(null, position))
    }

    @Test
    fun shorteningFiltersWholeGateAsOnePosition() {
        val positions = listOf(
            CoursePosition(
                order = 1,
                name = "Gate",
                kind = CoursePositionKind.GATE,
                omitWhenShortened = true
            ),
            CoursePosition(
                order = 2,
                name = "Mark",
                kind = CoursePositionKind.MARK,
                omitWhenShortened = false
            )
        )

        assertEquals(2, positions.activeCoursePositions(false).size)
        assertEquals(
            listOf(2),
            positions.activeCoursePositions(true).map { it.order }
        )
    }
}
