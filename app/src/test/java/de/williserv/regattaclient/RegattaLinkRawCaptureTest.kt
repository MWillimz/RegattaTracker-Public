package de.williserv.regattaclient

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegattaLinkRawCaptureTest {

    @Test
    fun decodesBroadcastNmea2000CanId() {
        val canId =
            (3L shl 26) or
                (129025L shl 8) or
                0x45L

        val decoded = decodeNmea2000CanId(canId)

        assertEquals(3, decoded.priority)
        assertEquals(129025L, decoded.pgn)
        assertEquals(0x45, decoded.source)
        assertEquals(null, decoded.destination)
    }

    @Test
    fun decodesAddressedNmea2000CanId() {
        val pgn = 0x00ef00L
        val destination = 0x23L
        val source = 0x45L
        val canId =
            (2L shl 26) or
                (pgn shl 8) or
                (destination shl 8) or
                source

        val decoded = decodeNmea2000CanId(canId)

        assertEquals(2, decoded.priority)
        assertEquals(pgn, decoded.pgn)
        assertEquals(source.toInt(), decoded.source)
        assertEquals(destination.toInt(), decoded.destination)
    }

    @Test
    fun csvPreservesRawIdentifierAndPayload() {
        val frame = RegattaLinkRawCanFrame(
            timestampUsLow = 0xffffffffL,
            canId = (3L shl 26) or (129025L shl 8) or 0x45L,
            dlc = 3,
            data = byteArrayOf(
                0x11,
                0x22,
                0x33,
                0x44,
                0x55,
                0x66,
                0x77,
                0x00
            )
        )

        assertEquals(
            "4294967295,234357061,129025,3,69,,3,112233",
            regattaLinkRawCaptureCsvRow(frame)
        )
    }

    @Test
    fun captureFileHasDeterministicHeaderAndRows() {
        val file = File.createTempFile("regattalink-capture-", ".csv")
        try {
            RegattaLinkRawCaptureFileSession(file).use { session ->
                session.append(
                    RegattaLinkRawCanFrame(
                        timestampUsLow = 1234L,
                        canId = (6L shl 26) or (0x00ef00L shl 8) or
                            (0x23L shl 8) or 0x45L,
                        dlc = 0,
                        data = ByteArray(8)
                    )
                )
            }

            val lines = file.readLines(Charsets.UTF_8)
            assertEquals(REGATTALINK_RAW_CAPTURE_CSV_HEADER, lines[0])
            assertEquals("1234,418325317,61184,6,69,35,0,", lines[1])
        } finally {
            file.delete()
        }
    }

    @Test
    fun flushStopsWhenQueueSnapshotIsDrainedOrBoundReached() {
        val frame = RegattaLinkRawCanFrame(
            timestampUsLow = 1L,
            canId = 0L,
            dlc = 0,
            data = ByteArray(8)
        )

        assertTrue(
            shouldContinueRawCaptureFlush(
                readsCompleted = 1,
                result = RegattaLinkRawCanReadResult(
                    remainingCount = 5,
                    frame = frame
                )
            )
        )
        assertFalse(
            shouldContinueRawCaptureFlush(
                readsCompleted = 1,
                result = RegattaLinkRawCanReadResult(
                    remainingCount = 0,
                    frame = frame
                )
            )
        )
        assertFalse(
            shouldContinueRawCaptureFlush(
                readsCompleted = REGATTALINK_RAW_CAPTURE_FLUSH_READ_LIMIT,
                result = RegattaLinkRawCanReadResult(
                    remainingCount = 5,
                    frame = frame
                )
            )
        )
    }

    @Test
    fun emptyOrDrainedFifoUsesBoundedPollDelay() {
        val frame = RegattaLinkRawCanFrame(
            timestampUsLow = 1L,
            canId = 0L,
            dlc = 0,
            data = ByteArray(8)
        )

        assertEquals(
            REGATTALINK_RAW_CAPTURE_EMPTY_POLL_MS,
            rawCapturePollDelayMs(
                RegattaLinkRawCanReadResult(
                    remainingCount = 0,
                    frame = null
                )
            )
        )
        assertEquals(
            REGATTALINK_RAW_CAPTURE_EMPTY_POLL_MS,
            rawCapturePollDelayMs(
                RegattaLinkRawCanReadResult(
                    remainingCount = 0,
                    frame = frame
                )
            )
        )
        assertEquals(
            0L,
            rawCapturePollDelayMs(
                RegattaLinkRawCanReadResult(
                    remainingCount = 3,
                    frame = frame
                )
            )
        )
    }

    @Test
    fun activeStateIsOnlyFlushOrCapture() {
        assertTrue(
            RegattaLinkRawCaptureState(
                phase = RegattaLinkRawCapturePhase.FLUSHING
            ).isActive
        )
        assertTrue(
            RegattaLinkRawCaptureState(
                phase = RegattaLinkRawCapturePhase.CAPTURING
            ).isActive
        )
        assertFalse(
            RegattaLinkRawCaptureState(
                phase = RegattaLinkRawCapturePhase.COMPLETED
            ).isActive
        )
    }
}
