package de.williserv.regattaclient

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RegattaLinkPhoneGnssTest {

    @Test
    fun encodesGoldenVectorAtSendTime() {
        val sample = RegattaLinkPhoneGnssSample(
            observationElapsedRealtimeNanos = 10_000_000_000L,
            utcTimeMs = 1_791_316_800_123L,
            latitudeDeg = 48.1234567,
            longitudeDeg = 11.54321,
            cogDeg = 123.45,
            sogMps = 6.78,
            horizontalAccuracyM = 2.5,
            altitudeM = -12.3
        )

        val raw = requireNotNull(
            encodeRegattaLinkPhoneGnss(
                sample,
                sendElapsedRealtimeNanos = 14_321_000_000L
            )
        )
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(REGATTALINK_PHONE_GNSS_FRAME_SIZE, raw.size)
        assertArrayEquals(
            byteArrayOf(
                0x02,
                0x3f,
                0xe1.toByte(),
                0x10,
                0x87.toByte(),
                0x0e,
                0xaf.toByte(),
                0x1c,
                0xa4.toByte(),
                0x5a,
                0xe1.toByte(),
                0x06,
                0x39,
                0x30,
                0xa6.toByte(),
                0x02,
                0xfa.toByte(),
                0x00,
                0x85.toByte(),
                0xff.toByte(),
                0x7b,
                0x32,
                0xcd.toByte(),
                0x12,
                0xa1.toByte(),
                0x01,
                0x00,
                0x00
            ),
            raw
        )
        assertEquals(REGATTALINK_PHONE_GNSS_FRAME_VERSION, raw[0].toInt() and 0xff)
        assertEquals(0x3f, raw[1].toInt() and 0xff)
        assertEquals(4_321, buffer.getShort(2).toInt() and 0xffff)
        assertEquals(481_234_567, buffer.getInt(4))
        assertEquals(115_432_100, buffer.getInt(8))
        assertEquals(12_345, buffer.getShort(12).toInt() and 0xffff)
        assertEquals(678, buffer.getShort(14).toInt() and 0xffff)
        assertEquals(250, buffer.getShort(16).toInt() and 0xffff)
        assertEquals(-123, buffer.getShort(18).toInt())
        assertEquals(1_791_316_800_123L, buffer.getLong(20))
    }

    @Test
    fun optionalValidityBitsAreIndependentAndReservedBitsStayZero() {
        val raw = requireNotNull(
            encodeRegattaLinkPhoneGnss(
                RegattaLinkPhoneGnssSample(
                    observationElapsedRealtimeNanos = 1L,
                    latitudeDeg = 0.0,
                    longitudeDeg = 0.0,
                    cogDeg = Double.NaN,
                    sogMps = -1.0,
                    horizontalAccuracyM = 1_000.0,
                    altitudeM = 9_000.0
                ),
                sendElapsedRealtimeNanos = 2L
            )
        )
        val flags = raw[1].toInt() and 0xff

        assertTrue(flags and REGATTALINK_PHONE_GNSS_VALID_POSITION != 0)
        assertFalse(flags and REGATTALINK_PHONE_GNSS_VALID_COG != 0)
        assertFalse(flags and REGATTALINK_PHONE_GNSS_VALID_SOG != 0)
        assertTrue(flags and REGATTALINK_PHONE_GNSS_VALID_ACCURACY != 0)
        assertFalse(flags and REGATTALINK_PHONE_GNSS_VALID_ALTITUDE != 0)
        assertFalse(flags and REGATTALINK_PHONE_GNSS_VALID_UTC_TIME != 0)
        assertEquals(0, flags and 0xc0)

        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(65_535, buffer.getShort(16).toInt() and 0xffff)
        assertEquals(0L, buffer.getLong(20))
    }


    @Test
    fun missingCourseRetainsLastValidCourseUntilReplacementArrives() {
        assertEquals(
            123.45,
            requireNotNull(
                regattaLinkRetainedPhoneGnssCog(
                    currentCogDeg = null,
                    previousCogDeg = 123.45
                )
            ),
            0.0
        )
        assertEquals(
            123.45,
            requireNotNull(
                regattaLinkRetainedPhoneGnssCog(
                    currentCogDeg = Double.NaN,
                    previousCogDeg = 123.45
                )
            ),
            0.0
        )
        assertEquals(
            210.0,
            requireNotNull(
                regattaLinkRetainedPhoneGnssCog(
                    currentCogDeg = 210.0,
                    previousCogDeg = 123.45
                )
            ),
            0.0
        )
        assertNull(
            regattaLinkRetainedPhoneGnssCog(
                currentCogDeg = null,
                previousCogDeg = null
            )
        )
    }

    @Test
    fun utcValidityRequiresPositiveEpochMillis() {
        listOf<Long?>(null, 0L, -1L).forEach { utc ->
            val raw = requireNotNull(
                encodeRegattaLinkPhoneGnss(
                    RegattaLinkPhoneGnssSample(
                        observationElapsedRealtimeNanos = 1L,
                        utcTimeMs = utc,
                        latitudeDeg = 53.0,
                        longitudeDeg = 10.0
                    ),
                    sendElapsedRealtimeNanos = 2L
                )
            )
            assertFalse(
                raw[1].toInt() and REGATTALINK_PHONE_GNSS_VALID_UTC_TIME != 0
            )
            assertEquals(
                0L,
                ByteBuffer.wrap(raw)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getLong(20)
            )
        }

        val raw = requireNotNull(
            encodeRegattaLinkPhoneGnss(
                RegattaLinkPhoneGnssSample(
                    observationElapsedRealtimeNanos = 1L,
                    utcTimeMs = 123_456_789L,
                    latitudeDeg = 53.0,
                    longitudeDeg = 10.0
                ),
                sendElapsedRealtimeNanos = 2L
            )
        )
        assertTrue(
            raw[1].toInt() and REGATTALINK_PHONE_GNSS_VALID_UTC_TIME != 0
        )
        assertEquals(
            123_456_789L,
            ByteBuffer.wrap(raw)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getLong(20)
        )
    }

    @Test
    fun phoneGnssTransportRequiresAttMtu31() {
        assertFalse(regattaLinkPhoneGnssTransportReady(23))
        assertFalse(regattaLinkPhoneGnssTransportReady(30))
        assertTrue(regattaLinkPhoneGnssTransportReady(31))
        assertTrue(regattaLinkPhoneGnssTransportReady(83))
        assertTrue(regattaLinkPhoneGnssTransportReady(247))
    }

    @Test
    fun sampleAgeIsClampedAndSaturatedFromMonotonicClock() {
        val sample = RegattaLinkPhoneGnssSample(
            observationElapsedRealtimeNanos = 100_000_000_000L,
            latitudeDeg = 1.0,
            longitudeDeg = 2.0
        )

        val futureObservation = requireNotNull(
            encodeRegattaLinkPhoneGnss(
                sample,
                sendElapsedRealtimeNanos = 99_000_000_000L
            )
        )
        val stale = requireNotNull(
            encodeRegattaLinkPhoneGnss(
                sample,
                sendElapsedRealtimeNanos = 200_000_000_000L
            )
        )

        assertEquals(
            0,
            ByteBuffer.wrap(futureObservation)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getShort(2).toInt() and 0xffff
        )
        assertEquals(
            65_535,
            ByteBuffer.wrap(stale)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getShort(2).toInt() and 0xffff
        )
    }

    @Test
    fun rejectsInvalidPositionInsteadOfSendingPartialFrame() {
        assertNull(
            encodeRegattaLinkPhoneGnss(
                RegattaLinkPhoneGnssSample(
                    observationElapsedRealtimeNanos = 0L,
                    latitudeDeg = 91.0,
                    longitudeDeg = 0.0
                ),
                sendElapsedRealtimeNanos = 1L
            )
        )
        assertNull(
            encodeRegattaLinkPhoneGnss(
                RegattaLinkPhoneGnssSample(
                    observationElapsedRealtimeNanos = 0L,
                    latitudeDeg = 0.0,
                    longitudeDeg = Double.NaN
                ),
                sendElapsedRealtimeNanos = 1L
            )
        )
    }

    @Test
    fun forwardingGateRequiresCanTxSelectorsAndUnblockedRuntime() {
        val enabledWord =
            REGATTALINK_CONFIG_TX_MASTER or
                REGATTALINK_CONFIG_TX_PHONE_GPS or
                REGATTALINK_CONFIG_SESSION_CAN
        val ready = RegattaLinkConfigurationState(
            configWordSupported = true,
            configWord = enabledWord
        )

        assertTrue(
            regattaLinkPhoneGnssForwardingGate(
                connected = true,
                transportReady = true,
                otaActive = false,
                configurationState = ready
            )
        )

        listOf(
            ready.copy(
                configWord =
                    REGATTALINK_CONFIG_TX_MASTER or
                        REGATTALINK_CONFIG_TX_PHONE_GPS
            ),
            ready.copy(
                configWord =
                    REGATTALINK_CONFIG_TX_PHONE_GPS or
                        REGATTALINK_CONFIG_SESSION_CAN
            ),
            ready.copy(
                configWord =
                    REGATTALINK_CONFIG_TX_MASTER or
                        REGATTALINK_CONFIG_SESSION_CAN
            ),
            ready.copy(deviceControlBusy = true),
            ready.copy(restartAwaitingDisconnect = true),
            ready.copy(factoryResetAwaitingDisconnect = true),
            ready.copy(factoryResetWriteAcceptedRequestId = 7u),
            ready.copy(configWord = null)
        ).forEach { blocked ->
            assertFalse(
                regattaLinkPhoneGnssForwardingGate(
                    connected = true,
                    transportReady = true,
                    otaActive = false,
                    configurationState = blocked
                )
            )
        }

        assertFalse(
            regattaLinkPhoneGnssForwardingGate(
                connected = false,
                transportReady = true,
                otaActive = false,
                configurationState = ready
            )
        )
        assertFalse(
            regattaLinkPhoneGnssForwardingGate(
                connected = true,
                transportReady = false,
                otaActive = false,
                configurationState = ready
            )
        )
        assertFalse(
            regattaLinkPhoneGnssForwardingGate(
                connected = true,
                transportReady = true,
                otaActive = true,
                configurationState = ready
            )
        )
    }

    @Test
    fun locationRequestCadenceDoesNotChangePersistenceCadence() {
        assertEquals(
            1_000L,
            regattaLinkLocationRequestIntervalMs(30_000L, true)
        )
        assertEquals(
            30_000L,
            regattaLinkLocationRequestIntervalMs(30_000L, false)
        )
        assertEquals(
            1_000L,
            regattaLinkLocationRequestIntervalMs(1_000L, true)
        )
    }
}
