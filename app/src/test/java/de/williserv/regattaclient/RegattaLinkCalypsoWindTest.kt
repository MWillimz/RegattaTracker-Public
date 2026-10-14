package de.williserv.regattaclient

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RegattaLinkCalypsoWindTest {
    @Test fun requestEncodingAndBounds() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), regattaLinkCalypso0031Request(0))
        assertArrayEquals(byteArrayOf(0, 1, 0, 0), regattaLinkCalypso0031Request(1))
        assertArrayEquals(byteArrayOf(0, 2, 0, 0), regattaLinkCalypso0031Request(2))
        assertArrayEquals(byteArrayOf(0, 0x20, 0, 0), regattaLinkCalypso0031Request(0x20))
        assertArrayEquals(byteArrayOf(0, 0x40, 6, 0), regattaLinkCalypso0031Request(0x40, 6))
        assertArrayEquals(byteArrayOf(0, 0x40, -1, -1), regattaLinkCalypso0031Request(0x40, -1))
        assertArrayEquals(byteArrayOf(0, 0x40, 76, -1), regattaLinkCalypso0031Request(0x40, -180))
        assertArrayEquals(byteArrayOf(0, 0x40, -76, 0), regattaLinkCalypso0031Request(0x40, 180))
        for (x in listOf(-181, 181)) {
            assertThrows(IllegalArgumentException::class.java) {
                regattaLinkCalypso0031Request(0x40, x)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            regattaLinkCalypso0031Request(1, 1)
        }
    }
    private fun parse(func: Int, json: String) =
        parseRegattaLinkCalypsoValue(json.toByteArray(), func)

    @Test fun strictFirmwareValuesAndStatus() {
        val zero = parse(2, """{"slot":0,"func":2,"value":0,"unit":"0.01m/s","age_ms":123,"status":"ok"}""")
        assertEquals(0, zero.value)
        assertEquals(RegattaLinkCalypsoValueStatus.OK, zero.status)
        val stale = parse(1, """{"slot":0,"func":1,"value":null,"unit":"deg","age_ms":5100,"status":"stale"}""")
        assertEquals(null, stale.value)
        assertEquals(RegattaLinkCalypsoValueStatus.STALE, stale.status)
        val offset = parse(0x20, """{"slot":0,"func":32,"value":-180,"unit":"deg","age_ms":null,"status":"ok"}""")
        assertEquals(-180, offset.value)
        for (bad in listOf(
            """{"slot":1,"func":1,"value":12,"unit":"deg","age_ms":10,"status":"ok"}""",
            """{"slot":0,"func":1,"value":12,"unit":"0.01m/s","age_ms":10,"status":"ok"}""",
            """{"slot":0,"func":1,"value":12,"unit":"deg","age_ms":10,"status":"stale"}""",
            """{"slot":0,"func":1,"value":500,"unit":"deg","age_ms":10,"status":"ok"}""",
            """{"slot":0,"func":1,"value":"3","unit":"deg","age_ms":10,"status":"ok"}""",
            """{"slot":0,"func":1,"value":null,"unit":"deg","age_ms":null,"status":"ok"}"""
        )) assertThrows(Exception::class.java) { parse(1, bad) }
    }
    @Test fun previewWrapAndCalm() {
        assertEquals(5, regattaLinkCalypsoPreviewAwa(355, 25, 10))
        assertEquals(355, regattaLinkCalypsoPreviewAwa(5, 25, -10))
        assertEquals(0, regattaLinkCalypsoPreviewAwa(360, 2, 0))
        assertEquals(null, regattaLinkCalypsoPreviewAwa(25, 0, 3))
        assertEquals(null, regattaLinkCalypsoPreviewAwa(25, 20, null))
    }
}
