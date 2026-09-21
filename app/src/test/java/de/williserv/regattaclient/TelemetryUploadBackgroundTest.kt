package de.williserv.regattaclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryUploadBackgroundTest {

    @Test
    fun backgroundSlice_yieldsOnlyAfterBoundedRuntime() {
        assertFalse(
            shouldYieldTelemetryUpload(
                elapsedMs = TELEMETRY_BACKGROUND_SLICE_MS - 1L
            )
        )
        assertTrue(
            shouldYieldTelemetryUpload(
                elapsedMs = TELEMETRY_BACKGROUND_SLICE_MS
            )
        )
        assertTrue(
            shouldYieldTelemetryUpload(
                elapsedMs = TELEMETRY_BACKGROUND_SLICE_MS * 2L
            )
        )
    }
}
