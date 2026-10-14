package de.williserv.regattaclient

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Firmware GATT 0031 slot-0 selectors. SET is signed, absolute degrees. */
internal fun regattaLinkCalypso0031Request(func: Int, value: Int = 0): ByteArray {
    require(
        when (func) {
            0x00, 0x01, 0x02, 0x20 -> value == 0
            0x40 -> value in -180..180
            else -> false
        }
    ) { "Unsupported Calypso 0031 command/value" }
    return byteArrayOf(
        0,
        func.toByte(),
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte()
    )
}

internal enum class RegattaLinkCalypsoValueStatus {
    OK, STALE, NOT_AVAILABLE
}

internal data class RegattaLinkCalypsoValue(
    val func: Int,
    val value: Int?,
    val unit: String,
    val ageMs: Long?,
    val status: RegattaLinkCalypsoValueStatus
)

data class RegattaLinkCalypsoOffsetState(
    val valueDeg: Int? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String = ""
)

data class RegattaLinkCalypsoRawWindState(
    val awa: RegattaLinkCalypsoValue? = null,
    val aws: RegattaLinkCalypsoValue? = null,
    val receivedAtElapsedMs: Long? = null,
    val error: String = ""
)

/** Uses the existing strict GATT JSON rules; rejects misleading partial values. */
internal fun parseRegattaLinkCalypsoValue(
    raw: ByteArray,
    expectedFunc: Int
): RegattaLinkCalypsoValue {
    require(expectedFunc in listOf(0x01, 0x02, 0x20))
    require(raw.size in 1..512)
    val json = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(raw)).toString()
    JsonReader(StringReader(json)).use { reader ->
        reader.isLenient = false
        reader.beginObject()
        while (reader.hasNext()) {
            reader.nextName()
            reader.skipValue()
        }
        reader.endObject()
        require(reader.peek() == JsonToken.END_DOCUMENT)
    }
    val obj = JSONObject(json)
    fun integer(name: String): Long? {
        require(obj.has(name))
        val rawValue = obj.opt(name)
        if (rawValue == JSONObject.NULL) return null
        require(rawValue is Number && rawValue.toDouble().isFinite() &&
            rawValue.toDouble() == rawValue.toLong().toDouble()) {
            "Invalid Calypso value field: $name"
        }
        return rawValue.toLong()
    }
    require(integer("slot") == 0L)
    require(integer("func") == expectedFunc.toLong())
    val valueLong = integer("value")
    val age = integer("age_ms")
    require(age == null || age >= 0)
    val value = valueLong?.also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }?.toInt()
    val unit = obj.getString("unit")
    require(unit == if (expectedFunc == 0x02) "0.01m/s" else "deg")
    val status = when (obj.getString("status")) {
        "ok" -> RegattaLinkCalypsoValueStatus.OK
        "stale" -> RegattaLinkCalypsoValueStatus.STALE
        "not_available" -> RegattaLinkCalypsoValueStatus.NOT_AVAILABLE
        else -> throw IllegalArgumentException("Invalid Calypso value status")
    }
    if (expectedFunc == 0x20) {
        require(status == RegattaLinkCalypsoValueStatus.OK &&
            age == null && value != null && value in -180..180)
    } else {
        require(if (status == RegattaLinkCalypsoValueStatus.OK) {
            value != null && age != null &&
                value in (if (expectedFunc == 0x01) 0..360 else 0..4000)
        } else value == null)
    }
    return RegattaLinkCalypsoValue(expectedFunc, value, unit, age, status)
}

/** Preview only; the RLink firmware owns the real wind correction. */
internal fun regattaLinkCalypsoPreviewAwa(
    rawAwaDeg: Int?,
    rawAwsCentiMps: Int?,
    offsetDeg: Int?
): Int? {
    if (rawAwaDeg == null || rawAwaDeg !in 0..360 ||
        rawAwsCentiMps == null || rawAwsCentiMps <= 0 ||
        offsetDeg == null || offsetDeg !in -180..180
    ) return null
    return ((rawAwaDeg % 360 + offsetDeg) % 360 + 360) % 360
}
