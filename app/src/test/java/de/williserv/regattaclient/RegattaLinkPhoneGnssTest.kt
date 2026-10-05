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
            latitudeDeg = 53.1234567,
            longitudeDeg = -9.7654321,
            cogDeg = 361.25,
            sogMps = 4.56,
            horizontalAccuracyM = 3.21,
            altitudeM = -12.3
        )

        val raw = requireNotNull(
            encodeRegattaLinkPhoneGnss(
                sample,
                sendElapsedRealtimeNanos = 11_234_000_000L
            )
        )
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(REGATTALINK_PHONE_GNSS_FRAME_SIZE, raw.size)
        assertEquals(1, raw[0].toInt() and 0xff)
        assertEquals(0x1f, raw[1].toInt() and 0xff)
        assertEquals(1_234, buffer.getShort(2).toInt() and 0xffff)
        assertEquals(531_234_567, buffer.getInt(4))
        assertEquals(-97_654_321, buffer.getInt(8))
        assertEquals(125, buffer.getShort(12).toInt() and 0xffff)
        assertEquals(456, buffer.getShort(14).toInt() and 0xffff)
        assertEquals(321, buffer.getShort(16).toInt() and 0xffff)
        assertEquals(-123, buffer.getShort(18).toInt())
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
        assertEquals(0, flags and 0xe0)

        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(65_535, buffer.getShort(16).toInt() and 0xffff)
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
    fun forwardingGateRequiresMasterPhoneSelectorConnectionAndNoOta() {
        val enabled =
            REGATTALINK_CONFIG_TX_MASTER or REGATTALINK_CONFIG_TX_PHONE_GPS

        assertTrue(regattaLinkPhoneGnssForwardingGate(true, false, enabled))
        assertFalse(
            regattaLinkPhoneGnssForwardingGate(
                true,
                false,
                REGATTALINK_CONFIG_TX_PHONE_GPS
            )
        )
        assertFalse(regattaLinkPhoneGnssForwardingGate(false, false, enabled))
        assertFalse(regattaLinkPhoneGnssForwardingGate(true, true, enabled))
        assertFalse(regattaLinkPhoneGnssForwardingGate(true, false, null))
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
