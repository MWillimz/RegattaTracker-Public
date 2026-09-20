package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryBatchUploadTest {

    private val client = ClientBuildIdentity(
        versionCode = 20_872_000,
        buildId = "26.09.20-1700-production"
    )

    @Test
    fun batchPayload_keepsCompleteOriginalSamplePayloads() {
        val first = sample(
            localId = 11L,
            sequenceId = 101L,
            timestamp = "2026-09-20T10:00:00",
            utcOffsetMinutes = 120
        )
        val second = sample(
            localId = 12L,
            sequenceId = 102L,
            timestamp = "2026-09-20T10:00:01",
            utcOffsetMinutes = 120
        )

        val payload = buildTelemetryBatchUploadPayload(
            samples = listOf(first, second),
            client = client
        )
        val samples = payload.getJSONArray("samples")

        assertEquals(2, samples.length())
        assertEquals(101L, samples.getJSONObject(0).getLong("sequence_id"))
        assertEquals(
            "2026-09-20T10:00:00",
            samples.getJSONObject(0).getString("timestamp")
        )
        assertEquals(
            120,
            samples.getJSONObject(0).getInt("utc_offset_minutes")
        )
        assertEquals(
            client.versionCode,
            samples.getJSONObject(0).getInt("client_version_code")
        )
        assertEquals(
            client.buildId,
            samples.getJSONObject(0).getString("client_build_id")
        )
        assertEquals(102L, samples.getJSONObject(1).getLong("sequence_id"))
    }

    @Test
    fun batchResponse_marksOnlyExplicitAcceptedIndexesAsUploadable() {
        val samples = listOf(
            sample(localId = 21L, sequenceId = 1L),
            sample(localId = 22L, sequenceId = 2L),
            sample(localId = 23L, sequenceId = 3L),
            sample(localId = 24L, sequenceId = 4L)
        )

        val parsed = parseTelemetryBatchUploadResponse(
            body = """
                {
                  "status": "processed",
                  "accepted": 1,
                  "rejected": 3,
                  "results": [
                    {"index": 0, "status": "accepted"},
                    {"index": 1, "status": "rejected", "code": "server_busy"},
                    {"index": 2, "status": "rejected", "code": "invalid_sample"},
                    {"index": 3, "status": "rejected", "code": "client_version_rejected"}
                  ]
                }
            """.trimIndent(),
            samples = samples
        )

        requireNotNull(parsed)
        assertEquals(listOf(21L), parsed.acceptedLocalIds)
        assertTrue(parsed.hasTemporaryRejection)
        assertTrue(parsed.clientUpdateRequired)
        assertEquals("invalid_sample", parsed.firstOtherRejectionCode)
    }

    @Test
    fun batchResponse_usesRequestIndexEvenWhenResultsAreReordered() {
        val samples = listOf(
            sample(localId = 31L, sequenceId = 1L),
            sample(localId = 32L, sequenceId = 2L)
        )

        val parsed = parseTelemetryBatchUploadResponse(
            body = """
                {
                  "results": [
                    {"index": 1, "status": "accepted"},
                    {"index": 0, "status": "rejected", "code": "invalid_sample"}
                  ]
                }
            """.trimIndent(),
            samples = samples
        )

        requireNotNull(parsed)
        assertEquals(listOf(32L), parsed.acceptedLocalIds)
        assertFalse(parsed.hasTemporaryRejection)
        assertEquals("invalid_sample", parsed.firstOtherRejectionCode)
    }

    @Test
    fun malformedBatchResponse_confirmsNothing() {
        val samples = listOf(
            sample(localId = 41L, sequenceId = 1L),
            sample(localId = 42L, sequenceId = 2L)
        )

        assertNull(
            parseTelemetryBatchUploadResponse(
                body = """
                    {
                      "results": [
                        {"index": 0, "status": "accepted"}
                      ]
                    }
                """.trimIndent(),
                samples = samples
            )
        )
        assertNull(
            parseTelemetryBatchUploadResponse(
                body = """
                    {
                      "results": [
                        {"index": 0, "status": "accepted"},
                        {"index": 0, "status": "accepted"}
                      ]
                    }
                """.trimIndent(),
                samples = samples
            )
        )
        assertNull(
            parseTelemetryBatchUploadResponse(
                body = """
                    {
                      "results": [
                        {"index": 0, "status": "accepted"},
                        {"index": 1, "status": "rejected"}
                      ]
                    }
                """.trimIndent(),
                samples = samples
            )
        )
    }

    @Test
    fun metadataCapability_usesPublishedServerLimitWithoutClientCap() {
        for (limit in listOf(1, 100, 250, 500, 1000)) {
            val capability = telemetryBatchCapabilityFromMetadata(
                ServerMetadata(
                    operator = null,
                    publicUrl = null,
                    contactEmail = null,
                    telemetryBatchMaxSamples = limit
                )
            )

            assertEquals(
                TelemetryBatchCapabilityKind.SUPPORTED,
                capability.kind
            )
            assertEquals(limit, capability.maxSamples)
        }
    }

    @Test
    fun missingBatchMetadata_keepsLegacySequentialCapability() {
        val capability = telemetryBatchCapabilityFromMetadata(
            ServerMetadata(
                operator = null,
                publicUrl = null,
                contactEmail = null
            )
        )

        assertEquals(
            TelemetryBatchCapabilityKind.UNSUPPORTED,
            capability.kind
        )
        assertNull(capability.maxSamples)
    }

    private fun sample(
        localId: Long,
        sequenceId: Long,
        timestamp: String = "2026-09-20T10:00:00",
        utcOffsetMinutes: Int? = null
    ): PendingTrackingSample {
        return PendingTrackingSample(
            localId = localId,
            accessContext = AccessContext(
                id = 7L,
                serverUrl = "https://raceoffice.example.org",
                accessIdentifier = "Example Series",
                accessSecret = "secret",
                createdAt = 1L,
                lastUsedAt = 2L
            ),
            sequenceId = sequenceId,
            timestamp = timestamp,
            boatName = "Test Boat",
            captainName = "Test Skipper",
            hullColor = "white",
            sailNumber = "GER 123",
            yardstick = 100.0,
            boatType = "Test Type",
            lat = 53.5,
            lon = 10.0,
            accuracy = 4.0f,
            cog = 180.0f,
            sog = 3.5f,
            accelX = 0.1f,
            accelY = 0.2f,
            accelZ = 0.3f,
            gyroX = 0.4f,
            gyroY = 0.5f,
            gyroZ = 0.6f,
            batteryPercent = 87,
            batteryCharging = false,
            trackingProfile = "normal",
            utcOffsetMinutes = utcOffsetMinutes
        )
    }
}
