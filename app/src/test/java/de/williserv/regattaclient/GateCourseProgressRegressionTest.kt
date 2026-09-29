package de.williserv.regattaclient

import kotlin.math.PI
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GateCourseProgressRegressionTest {

    private val origin = GeoPoint(lat = 54.0, lon = 10.0)

    @Test
    fun gateCrossingAdvancesCourseExactlyOnce() {
        withService { service ->
            configureRacing(service, listOf(gate(y = 0.0)))

            invokeMarkAndFinishState(service, localPoint(0.0, -20.0), 1_000L)
            assertEquals(0, getField<Int>(service, "passedMarks"))

            invokeMarkAndFinishState(service, localPoint(500.0, 20.0), 2_000L)
            assertEquals(1, getField<Int>(service, "passedMarks"))

            invokeMarkAndFinishState(service, localPoint(500.0, 40.0), 3_000L)
            assertEquals(1, getField<Int>(service, "passedMarks"))
        }
    }

    @Test
    fun gateUsesGeometryResolvedForCurrentSegmentEndpoint() {
        withService { service ->
            configureRacing(service, listOf(gate(y = 0.0)))

            invokeMarkAndFinishState(service, localPoint(0.0, -20.0), 1_000L)

            setField(service, "coursePositions", listOf(gate(y = 30.0)))
            invokeMarkAndFinishState(service, localPoint(0.0, 20.0), 2_000L)

            assertEquals(0, getField<Int>(service, "passedMarks"))
            assertEquals(
                localPoint(0.0, 20.0),
                getField<GeoPoint?>(service, "previousMarkDetectionPosition")
            )

            invokeMarkAndFinishState(service, localPoint(0.0, 40.0), 3_000L)
            assertEquals(1, getField<Int>(service, "passedMarks"))
        }
    }

    @Test
    fun invalidGateGeometryDoesNotSpanCrossingAcrossSamples() {
        withService { service ->
            val invalidGate = gate(y = 0.0).copy(gateDirection = null)
            configureRacing(service, listOf(invalidGate))

            invokeMarkAndFinishState(service, localPoint(0.0, -20.0), 1_000L)
            invokeMarkAndFinishState(service, localPoint(0.0, 20.0), 2_000L)

            assertEquals(0, getField<Int>(service, "passedMarks"))
            assertEquals(
                localPoint(0.0, 20.0),
                getField<GeoPoint?>(service, "previousMarkDetectionPosition")
            )

            setField(service, "coursePositions", listOf(gate(y = 0.0)))
            invokeMarkAndFinishState(service, localPoint(0.0, 40.0), 3_000L)

            assertEquals(0, getField<Int>(service, "passedMarks"))

            invokeMarkAndFinishState(service, localPoint(0.0, -20.0), 4_000L)
            assertEquals(0, getField<Int>(service, "passedMarks"))

            invokeMarkAndFinishState(service, localPoint(0.0, 20.0), 5_000L)
            assertEquals(1, getField<Int>(service, "passedMarks"))
        }
    }

    @Test
    fun finishCannotCompleteWhileGateIsPending() {
        withService { service ->
            val finishLine = StartLine(
                ref = localPoint(-50.0, 100.0),
                mark = localPoint(50.0, 100.0)
            )
            configureRacing(service, listOf(gate(y = 0.0)))
            setField(service, "finishLine", finishLine)

            invokeMarkAndFinishState(service, localPoint(0.0, 50.0), 1_000L)
            invokeMarkAndFinishState(service, localPoint(0.0, 150.0), 2_000L)
            assertEquals(0, getField<Int>(service, "passedMarks"))
            assertFalse(getField<Boolean>(service, "raceFinished"))

            invokeMarkAndFinishState(service, localPoint(0.0, -20.0), 3_000L)
            invokeMarkAndFinishState(service, localPoint(0.0, 20.0), 4_000L)
            assertEquals(1, getField<Int>(service, "passedMarks"))
            assertFalse(getField<Boolean>(service, "raceFinished"))

            invokeMarkAndFinishState(service, localPoint(0.0, 150.0), 5_000L)
            assertTrue(getField<Boolean>(service, "raceFinished"))
        }
    }

    @Test
    fun gateTargetDistanceUsesEffectiveOffsetDetectionLine() {
        withService { service ->
            val startLine = StartLine(
                ref = localPoint(-50.0, -200.0),
                mark = localPoint(50.0, -200.0)
            )
            configureRacing(service, listOf(gate(y = 0.0, offsetM = 30.0)))
            setField(service, "startLine", startLine)

            invokeMarkAndFinishState(service, localPoint(1_000.0, 0.0), 1_000L)

            val distance = getField<Double?>(service, "currentTargetDistanceM")
            assertNotNull(distance)
            assertEquals(30.0, distance ?: Double.NaN, 0.2)
        }
    }

    @Test
    fun unusableAccuracySampleIsIgnoredBeforeGateProgress() {
        withService { service ->
            configureRacing(service, listOf(gate(y = 0.0)))

            invokeLocalRaceState(service, localPoint(0.0, -20.0), accuracy = 5f)
            assertEquals(0, getField<Int>(service, "passedMarks"))

            val beforeRejected = getField<GeoPoint?>(service, "previousMarkDetectionPosition")
            invokeLocalRaceState(service, localPoint(0.0, 20.0), accuracy = 30f)

            assertEquals(0, getField<Int>(service, "passedMarks"))
            assertEquals(
                beforeRejected,
                getField<GeoPoint?>(service, "previousMarkDetectionPosition")
            )

            invokeLocalRaceState(service, localPoint(0.0, 20.0), accuracy = 5f)
            assertEquals(1, getField<Int>(service, "passedMarks"))
        }
    }

    private fun configureRacing(
        service: RegattaTrackingService,
        positions: List<CoursePosition>
    ) {
        setField(service, "coursePositions", positions)
        setField(service, "raceStarted", true)
        setField(service, "raceFinished", false)
        setField(service, "isOcs", false)
        setField(service, "passedMarks", 0)
        setField(service, "previousMarkDetectionPosition", null)
        setField(service, "markDetectionProgress", null)
    }

    private fun gate(
        y: Double,
        offsetM: Double = 0.0
    ): CoursePosition = CoursePosition(
        order = 1,
        name = "Gate",
        kind = CoursePositionKind.GATE,
        omitWhenShortened = false,
        gateRef = localPoint(-50.0, y),
        gateMark = localPoint(50.0, y),
        gateDirection = GateDirection.POSITIVE,
        gateOffsetM = offsetM
    )

    private fun invokeMarkAndFinishState(
        service: RegattaTrackingService,
        point: GeoPoint,
        nowMillis: Long
    ) {
        service.javaClass.getDeclaredMethod(
            "calculateMarkAndFinishState",
            GeoPoint::class.java,
            java.lang.Long.TYPE
        ).apply {
            isAccessible = true
            invoke(service, point, nowMillis)
        }
    }

    private fun invokeLocalRaceState(
        service: RegattaTrackingService,
        point: GeoPoint,
        accuracy: Float
    ) {
        service.javaClass.getDeclaredMethod(
            "calculateLocalRaceState",
            java.lang.Double.TYPE,
            java.lang.Double.TYPE,
            java.lang.Float.TYPE
        ).apply {
            isAccessible = true
            invoke(service, point.lat, point.lon, accuracy)
        }
    }

    private fun setField(target: Any, fieldName: String, value: Any?) {
        target.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
            set(target, value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(target: Any, fieldName: String): T {
        return target.javaClass.getDeclaredField(fieldName).let { field ->
            field.isAccessible = true
            field.get(target) as T
        }
    }

    private fun localPoint(x: Double, y: Double): GeoPoint {
        val earthRadiusM = 6_371_000.0
        val lat = origin.lat + (y / earthRadiusM) * 180.0 / PI
        val lon = origin.lon +
            (x / (earthRadiusM * cos(origin.lat * PI / 180.0))) * 180.0 / PI
        return GeoPoint(lat = lat, lon = lon)
    }

    private inline fun withService(block: (RegattaTrackingService) -> Unit) {
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        try {
            block(controller.get())
        } finally {
            controller.destroy()
        }
    }
}
