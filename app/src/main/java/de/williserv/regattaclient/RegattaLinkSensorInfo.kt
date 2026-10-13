package de.williserv.regattaclient

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal data class RegattaLinkSensorInfoEntry(
    val key: String,
    val value: String
)

internal data class RegattaLinkSensorInfoState(
    val loading: Boolean = false,
    val entries: List<RegattaLinkSensorInfoEntry> = emptyList(),
    val error: String = ""
)

/** Parse a complete GATT 0031 JSON object, retaining arbitrary future metadata keys. */
internal fun parseRegattaLinkSensorInfo(raw: ByteArray): List<RegattaLinkSensorInfoEntry> {
    require(raw.size in 1..512) { "RegattaLink sensor info must contain 1..512 bytes" }
    val json = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(raw))
        .toString()
    val reader = JSONTokener(json)
    val obj = reader.nextValue()
    require(obj is JSONObject) { "RegattaLink sensor info must be a JSON object" }
    while (true) {
        val trailing = reader.next()
        if (trailing == '\u0000') break
        require(trailing.isWhitespace()) { "Unexpected trailing sensor info data" }
    }
    return obj.keys().asSequence().mapNotNull { key ->
        val value = obj.opt(key)
        if (value == null || value === JSONObject.NULL) {
            null
        } else {
            RegattaLinkSensorInfoEntry(
                key,
                when (value) {
                    is JSONObject, is JSONArray -> value.toString()
                    else -> value.toString()
                }
            )
        }
    }.sortedBy { it.key }.toList()
}
