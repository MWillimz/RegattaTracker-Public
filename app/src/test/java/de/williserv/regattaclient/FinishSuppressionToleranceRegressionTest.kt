package de.williserv.regattaclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinishSuppressionToleranceRegressionTest {

    @Test
    fun `finish suppression ignores tolerance zone and rearms only on approach side`() {
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()

        val finishLine = StartLine(
            ref = GeoPoint(lat = 0.0, lon = 0.0),
            mark = GeoPoint(lat = 0.0, lon = 0.01)
        )
        val approachPoint = GeoPoint(lat = 0.01, lon = 0.005)
        val tolerancePoint = GeoPoint(lat = 0.00001, lon = 0.005)
        val finishPoint = GeoPoint(lat = -0.01, lon = 0.005)

        assertTrue(
            kotlin.math.abs(
                StartLineMath.signedDistanceToStartLineM(tolerancePoint, finishLine)
            ) < 10.0
        )

        setField(service, "finishLine", finishLine)
        setField(
            service,
            "courseMarks",
            listOf(
                CourseMark(
                    order = 1,
                    name = "Last mark",
                    point = approachPoint,
                    radiusM = 10.0
                )
            )
        )
        setField(service, "raceStarted", true)
        setField(service, "raceFinished", false)
        setField(service, "finishDetectionSuppressed", true)
        setField(service, "passedMarks", 1)

        invokeMarkAndFinishState(service, finishPoint, 1_000L)
        assertTrue(getField<Boolean>(service, "finishDetectionSuppressed"))
        assertFalse(getField<Boolean>(service, "raceFinished"))

        invokeMarkAndFinishState(service, tolerancePoint, 2_000L)
        assertTrue(getField<Boolean>(service, "finishDetectionSuppressed"))
        assertFalse(getField<Boolean>(service, "raceFinished"))

        invokeMarkAndFinishState(service, finishPoint, 3_000L)
        assertTrue(getField<Boolean>(service, "finishDetectionSuppressed"))
        assertFalse(getField<Boolean>(service, "raceFinished"))

        invokeMarkAndFinishState(service, approachPoint, 4_000L)
        assertFalse(getField<Boolean>(service, "finishDetectionSuppressed"))
        assertFalse(getField<Boolean>(service, "raceFinished"))

        invokeMarkAndFinishState(service, finishPoint, 5_000L)
        assertTrue(getField<Boolean>(service, "raceFinished"))

        controller.destroy()
    }

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
}
