package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CourseGateOverlayTest {

    @Test
    fun gateAddsBothEndpointsToOverviewOverlay() {
        val points = parseCourseOverlayPoints(
            courseJson = """
                {
                  "marks": [
                    {
                      "order": 1,
                      "type": "mark",
                      "name": "A",
                      "lat": 54.0,
                      "lon": 10.0
                    },
                    {
                      "order": 2,
                      "type": "gate",
                      "name": "Leegate",
                      "ref": {"lat": 54.1, "lon": 10.1},
                      "mark": {"lat": 54.2, "lon": 10.2},
                      "direction": "positive",
                      "offset_m": 0,
                      "omit_when_shortened": true
                    }
                  ]
                }
            """.trimIndent(),
            courseShortened = true
        )

        assertEquals(3, points.size)
        assertEquals(1, points.count { !it.inactive })
        val gatePoints = points.filter { it.inactive }
        assertEquals(2, gatePoints.size)
        assertTrue(gatePoints.any { it.lat == 54.1 && it.lon == 10.1 })
        assertTrue(gatePoints.any { it.lat == 54.2 && it.lon == 10.2 })
        assertTrue(gatePoints.all { it.kind == CourseOverlayKind.MARK })
    }
}
