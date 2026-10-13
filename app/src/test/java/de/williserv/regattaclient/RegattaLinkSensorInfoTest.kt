package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RegattaLinkSensorInfoTest {
    private fun parse(json: String) = parseRegattaLinkSensorInfo(json.toByteArray(Charsets.UTF_8))

    @Test
    fun parsesUnknownKeysAndSkipsOnlyJsonNull() {
        val entries = parse("""{"manufacturer":"Calypso","firmware":"2.1","hardware":null,"battery":89,"active":true,"note":""}""")
        assertEquals(
            listOf("active", "battery", "firmware", "manufacturer", "note"),
            entries.map { it.key }
        )
        assertEquals("89", entries.single { it.key == "battery" }.value)
        assertEquals("true", entries.single { it.key == "active" }.value)
        assertEquals("", entries.single { it.key == "note" }.value)
    }

    @Test
    fun acceptsEscapesAndNestedFutureValues() {
        val entries = parse("""{"model":"Schiff \\"A\\"","more":{"rate":1},"list":[1,2]}""")
        assertEquals(3, entries.size)
        assertEquals("""{"rate":1}""", entries.single { it.key == "more" }.value)
        assertEquals("[1,2]", entries.single { it.key == "list" }.value)
    }

    @Test
    fun emptyOrAllNullObjectIsNotAnError() {
        assertTrue(parse("{}").isEmpty())
        assertTrue(parse("""{"id":null,"model":null}""").isEmpty())
    }

    @Test
    fun rejectsMalformedJsonAndTrailingJunk() {
        for (json in listOf("[]", "null", "{", """{"id":"x"} garbage""", "true")) {
            assertThrows(Exception::class.java) { parse(json) }
        }
    }

    @Test
    fun rejectsInvalidUtf8AndOversizeData() {
        assertThrows(Exception::class.java) { parseRegattaLinkSensorInfo(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(Exception::class.java) { parseRegattaLinkSensorInfo(ByteArray(513) { 0x20 }) }
        assertThrows(Exception::class.java) { parseRegattaLinkSensorInfo(byteArrayOf()) }
    }

    @Test
    fun acceptsJsonAt512ByteWireLimit() {
        val json = """{"future":"${"x".repeat(499)}"}"""
        assertEquals(512, json.toByteArray(Charsets.UTF_8).size)
        assertEquals(499, parse(json).single().value.length)
    }
}
