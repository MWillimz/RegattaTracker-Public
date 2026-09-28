package de.williserv.regattaclient

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.util.Locale

internal const val REGATTALINK_LOAD_WIRE_VERSION = 1
internal const val REGATTALINK_LOAD_WIRE_TYPE_CATALOG = 1
internal const val REGATTALINK_LOAD_WIRE_TYPE_SAMPLE = 2
internal const val REGATTALINK_LOAD_MAX_FRAME_SIZE = 20
internal const val REGATTALINK_LOAD_INVALID_VALUE = 0xffff
internal const val REGATTALINK_LOAD_MAX_ALIAS_BYTES = 48
internal const val REGATTALINK_LOAD_TRANSPORT_STALE_MS = 3_500L

data class RegattaLinkLoadSensor(
    val identityKey: String,
    val measurementKey: String,
    val defaultLabel: String,
    val alias: String? = null,
    val loadKg: Double,
    val stableIdentity: Boolean
) {
    val label: String
        get() = alias?.takeIf { it.isNotBlank() } ?: defaultLabel
}

private data class RegattaLinkLoadCatalogEntry(
    val slot: Int,
    val identityKey: String,
    val measurementKey: String,
    val defaultLabel: String,
    val stableIdentity: Boolean
)

internal class RegattaLinkLoadPacketAssembler(
    private val aliasProvider: (String) -> String? = { null }
) {
    private var catalogEpoch: Int? = null
    private val catalog = linkedMapOf<Int, RegattaLinkLoadCatalogEntry>()

    private var pendingEpoch: Int? = null
    private var pendingSequence: Int? = null
    private var pendingFragmentCount = 0
    private var pendingX10 = false
    private val pendingFragments = mutableMapOf<Int, List<Pair<Int, Int>>>()

    fun reset() {
        catalogEpoch = null
        catalog.clear()
        resetPending()
    }

    fun accept(raw: ByteArray): List<RegattaLinkLoadSensor>? {
        require(raw.isNotEmpty() && raw.size <= REGATTALINK_LOAD_MAX_FRAME_SIZE) {
            "Invalid RegattaLink load frame length " + raw.size
        }
        val header = raw[0].toInt() and 0xff
        val version = header ushr 4
        val type = (header ushr 1) and 0x07
        require(version == REGATTALINK_LOAD_WIRE_VERSION) {
            "Unsupported RegattaLink load schema " + version
        }

        return when (type) {
            REGATTALINK_LOAD_WIRE_TYPE_CATALOG -> {
                parseCatalog(raw)
                null
            }
            REGATTALINK_LOAD_WIRE_TYPE_SAMPLE ->
                parseSample(raw, x10 = header and 0x01 != 0)
            else -> throw IllegalArgumentException(
                "Unsupported RegattaLink load frame type " + type
            )
        }
    }

    private fun parseCatalog(raw: ByteArray) {
        require(raw.size >= 6) { "RegattaLink load catalog frame is too short" }
        val epoch = raw[1].toInt() and 0xff
        val slot = raw[2].toInt() and 0xff
        val identityFlags = raw[3].toInt() and 0xff
        val identityKind = identityFlags and 0x7f
        val stable = identityFlags and 0x80 != 0
        val identityLength = raw[4].toInt() and 0xff
        require(identityLength in 1..12 && raw.size == 5 + identityLength) {
            "Invalid RegattaLink load identity length " + identityLength
        }

        if (catalogEpoch != epoch) {
            catalogEpoch = epoch
            catalog.clear()
            resetPending()
        }

        catalog[slot] = catalogEntry(
            slot = slot,
            kind = identityKind,
            stable = stable,
            bytes = raw.copyOfRange(5, raw.size)
        )
    }

    private fun catalogEntry(
        slot: Int,
        kind: Int,
        stable: Boolean,
        bytes: ByteArray
    ): RegattaLinkLoadCatalogEntry {
        if (kind == 2 && bytes.size == 9) {
            val nameHex = bytes
                .copyOfRange(0, 8)
                .reversedArray()
                .joinToString("") {
                    "%02x".format(Locale.ROOT, it.toInt() and 0xff)
                }
            val instance = bytes[8].toInt() and 0xff
            return RegattaLinkLoadCatalogEntry(
                slot = slot,
                identityKey = "stable:nmea:" + nameHex + "." + instance,
                measurementKey = "nmea.load." + nameHex + "." + instance,
                defaultLabel = "Load " + instance,
                stableIdentity = stable
            )
        }

        if (kind == 1 && bytes.size == 2) {
            val source = bytes[0].toInt() and 0xff
            val instance = bytes[1].toInt() and 0xff
            return RegattaLinkLoadCatalogEntry(
                slot = slot,
                identityKey = "temporary:nmea-source:" + source + "." + instance,
                measurementKey = "nmea.load.source%02x.%d".format(
                    Locale.ROOT,
                    source,
                    instance
                ),
                defaultLabel = "Load " + instance,
                stableIdentity = false
            )
        }

        val idHex = bytes.joinToString("") {
            "%02x".format(Locale.ROOT, it.toInt() and 0xff)
        }
        return RegattaLinkLoadCatalogEntry(
            slot = slot,
            identityKey =
                (if (stable) "stable" else "temporary") +
                    ":load:" + kind + ":" + idHex,
            measurementKey = "regattalink.load." + kind + "." + idHex,
            defaultLabel = "Load " + slot,
            stableIdentity = stable
        )
    }

    private fun parseSample(
        raw: ByteArray,
        x10: Boolean
    ): List<RegattaLinkLoadSensor>? {
        require(raw.size >= 4 && (raw.size - 4) % 3 == 0) {
            "Invalid RegattaLink load sample length " + raw.size
        }
        val epoch = raw[1].toInt() and 0xff
        if (catalogEpoch != epoch) {
            resetPending()
            return null
        }

        val sequence = raw[2].toInt() and 0xff
        val fragmentByte = raw[3].toInt() and 0xff
        val fragmentCount = (fragmentByte ushr 4) + 1
        val fragmentIndex = fragmentByte and 0x0f
        require(fragmentIndex < fragmentCount) {
            "Invalid RegattaLink load fragment " +
                fragmentIndex + "/" + fragmentCount
        }

        if (
            pendingEpoch != epoch ||
            pendingSequence != sequence ||
            pendingFragmentCount != fragmentCount ||
            pendingX10 != x10
        ) {
            resetPending()
            pendingEpoch = epoch
            pendingSequence = sequence
            pendingFragmentCount = fragmentCount
            pendingX10 = x10
        }

        val entries = buildList {
            var offset = 4
            while (offset < raw.size) {
                val slot = raw[offset].toInt() and 0xff
                val encoded =
                    (raw[offset + 1].toInt() and 0xff) or
                        ((raw[offset + 2].toInt() and 0xff) shl 8)
                require(encoded != REGATTALINK_LOAD_INVALID_VALUE) {
                    "Invalid RegattaLink load value"
                }
                add(slot to encoded)
                offset += 3
            }
        }
        pendingFragments[fragmentIndex] = entries
        if (pendingFragments.size != fragmentCount) return null
        if ((0 until fragmentCount).any { it !in pendingFragments }) return null

        val scale = if (x10) 0.1 else 1.0
        val allEntries = (0 until fragmentCount)
            .flatMap { pendingFragments.getValue(it) }
        resetPending()

        val result = mutableListOf<RegattaLinkLoadSensor>()
        for ((slot, encoded) in allEntries) {
            val entry = catalog[slot] ?: return null
            val alias = if (entry.stableIdentity) {
                aliasProvider(entry.identityKey)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            } else {
                null
            }
            result += RegattaLinkLoadSensor(
                identityKey = entry.identityKey,
                measurementKey = entry.measurementKey,
                defaultLabel = entry.defaultLabel,
                alias = alias,
                loadKg = encoded * scale,
                stableIdentity = entry.stableIdentity
            )
        }
        return result.sortedBy { it.measurementKey }
    }

    private fun resetPending() {
        pendingEpoch = null
        pendingSequence = null
        pendingFragmentCount = 0
        pendingX10 = false
        pendingFragments.clear()
    }
}

internal class RegattaLinkLoadAliasStore(context: Context) {
    companion object {
        internal const val PREFS_NAME = "regattalink_load_aliases"
    }

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun get(identityKey: String): String? =
        prefs.getString(identityKey, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    fun set(identityKey: String, alias: String): Boolean {
        if (!identityKey.startsWith("stable:")) return false
        val normalized = alias.trim()
        if (
            normalized.toByteArray(Charsets.UTF_8).size >
            REGATTALINK_LOAD_MAX_ALIAS_BYTES
        ) {
            return false
        }
        if (normalized.any { it.code < 0x20 || it.code == 0x7f }) {
            return false
        }

        val editor = prefs.edit()
        if (normalized.isBlank()) {
            editor.remove(identityKey)
        } else {
            editor.putString(identityKey, normalized)
        }
        editor.apply()
        return true
    }
}

internal object RegattaLinkLoadSnapshotStore {
    @Volatile
    private var latest: List<RegattaLinkLoadSensor> = emptyList()

    @Volatile
    private var latestReceivedAtElapsedMs: Long? = null

    fun update(
        sensors: List<RegattaLinkLoadSensor>,
        receivedAtElapsedMs: Long = SystemClock.elapsedRealtime()
    ) {
        latest = sensors.toList()
        latestReceivedAtElapsedMs = receivedAtElapsedMs
    }

    fun replaceMetadata(sensors: List<RegattaLinkLoadSensor>) {
        latest = sensors.toList()
    }

    fun clear() {
        latest = emptyList()
        latestReceivedAtElapsedMs = null
    }

    fun current(): List<RegattaLinkLoadSensor> = latest

    fun measurementsJson(
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): String? {
        val receivedAt = latestReceivedAtElapsedMs ?: return null
        val ageMs = nowElapsedMs - receivedAt
        if (ageMs !in 0..REGATTALINK_LOAD_TRANSPORT_STALE_MS) {
            return null
        }

        val sensors = latest
        if (sensors.isEmpty()) return null

        val measurements = JSONObject()
        sensors.forEach { sensor ->
            if (!sensor.loadKg.isFinite()) return@forEach
            measurements.put(
                sensor.measurementKey,
                JSONObject()
                    .put("value", sensor.loadKg)
                    .put("unit", "kg")
                    .put("label", sensor.label)
                    .put("group", "load")
            )
        }
        return if (measurements.length() == 0) null else measurements.toString()
    }
}
