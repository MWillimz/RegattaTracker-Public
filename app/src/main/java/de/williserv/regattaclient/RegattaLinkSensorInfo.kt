package de.williserv.regattaclient

import org.json.JSONObject
import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class RegattaLinkSensorInfoEntry(
    val key: String,
    val value: String
)

data class RegattaLinkSensorInfoState(
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
    // Android's JSONObject parser accepts several non-JSON extensions. Validate
    // against the platform's strict streaming parser before building display rows.
    JsonReader(StringReader(json)).use { reader ->
        reader.isLenient = false
        reader.beginObject()
        while (reader.hasNext()) {
            reader.nextName()
            reader.skipValue()
        }
        reader.endObject()
        require(reader.peek() == JsonToken.END_DOCUMENT) {
            "Unexpected trailing sensor info data"
        }
    }
    val obj = JSONObject(json)
    return obj.keys().asSequence().mapNotNull { key ->
        val value = obj.opt(key)
        if (value == null || value === JSONObject.NULL) {
            null
        } else {
            RegattaLinkSensorInfoEntry(
                key,
                value.toString()
            )
        }
    }.sortedBy { it.key }.toList()
}
