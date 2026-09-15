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
class FinishDirectionRegressionTest {

    @Test
    fun `approach through tolerance to finish side marks race finished`() {
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        configureFinishedCourse(service)

        invokeMarkAndFinishState(service, APPROACH_POINT, 1_000L)
        assertFalse(getField(service, "raceFinished"))

        invokeMarkAndFinishState(service, TOLERANCE_POINT, 2_000L)
        assertFalse(getField(service, "raceFinished"))

        invokeMarkAndFinishState(service, FINISH_POINT, 3_000L)
        assertTrue(getField(service, "raceFinished"))

        controller.destroy()
    }

    @Test
    fun `finish side back to approach side does not mark race finished`() {
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        configureFinishedCourse(service)

        invokeMarkAndFinishState(service, FINISH_POINT, 1_000L)
        assertFalse(getField(service, "raceFinished"))

        invokeMarkAndFinishState(service, APPROACH_POINT, 2_000L)
        assertFalse(getField(service, "raceFinished"))

        controller.destroy()
    }

    @Test
    fun `finish side without observed approach does not mark race finished`() {
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        configureFinishedCourse(service)

        invokeMarkAndFinishState(service, FINISH_POINT, 1_000L)

        assertFalse(getField(service, "raceFinished"))

        controller.destroy()
    }

    private fun configureFinishedCourse(service: RegattaTrackingService) {
        setField(service, "finishLine", FINISH_LINE)
        setField(
            service,
            "courseMarks",
            listOf(
                CourseMark(
                    order = 1,
                    name = "Last mark",
                    point = APPROACH_POINT,
                    radiusM = 10.0
                )
            )
        )
        setField(service, "raceStarted", true)
        setField(service, "raceFinished", false)
        setField(service, "finishDetectionSuppressed", false)
        setField(service, "passedMarks", 1)
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

    private companion object {
        val FINISH_LINE = StartLine(
            ref = GeoPoint(lat = 0.0, lon = 0.0),
            mark = GeoPoint(lat = 0.0, lon = 0.01)
        )
        val APPROACH_POINT = GeoPoint(lat = 0.01, lon = 0.005)
        val TOLERANCE_POINT = GeoPoint(lat = 0.00001, lon = 0.005)
        val FINISH_POINT = GeoPoint(lat = -0.01, lon = 0.005)
    }
}
