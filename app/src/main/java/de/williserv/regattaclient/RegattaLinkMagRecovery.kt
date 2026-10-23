package de.williserv.regattaclient

import android.content.SharedPreferences
import org.json.JSONObject

internal enum class MagObjectKind { AUTO_2D, MANUAL_3D }
internal data class MagPending(
    val kind: MagObjectKind, val binding: RegattaLinkMagBinding,
    val requestId: UInt, val objectBytes: ByteArray, val bootId: UInt
) {
    val crc get() = magCrc32(objectBytes)
    init {
        require(requestId != 0u && objectBytes.size == if (kind == MagObjectKind.AUTO_2D) 32 else 80)
        require(magCrc32(objectBytes.copyOfRange(0, objectBytes.size-4)) ==
            java.nio.ByteBuffer.wrap(objectBytes,objectBytes.size-4,4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toUInt())
    }
}
internal enum class MagRestoreStage {
    BACKUP_SAVED, RESET_MAY_HAVE_BEEN_SENT, RESET_CONFIRMED,
    RESTORE_2D_MAY_HAVE_BEEN_SENT, RESTORE_2D_CONFIRMED, RESTORE_TRIM
}
internal data class MagBackup(
    val binding: RegattaLinkMagBinding, val objectBytes: ByteArray,
    val trimDeg: Int, val resetRequest: UInt, val stage: MagRestoreStage
)
internal data class MagJournal(val pending: MagPending? = null, val backup: MagBackup? = null)
internal enum class MagReceipt { STORED, REJECTED, UNKNOWN }

internal fun magReceipt(pending: MagPending, metadata: RegattaLinkMagAutoMetadata,
    status: RegattaLinkMagStatus?, stableId: String): MagReceipt {
    if (metadata.binding(stableId) != pending.binding || !metadata.factoryValid || !metadata.referenceValid)
        return MagReceipt.UNKNOWN
    if (pending.kind == MagObjectKind.AUTO_2D) {
        val b = java.nio.ByteBuffer.wrap(pending.objectBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val offsetMatches = (0..2).all { b.getInt(16+it*4) == metadata.offset[it].toFloat().toRawBits() }
        if (metadata.autoStored && metadata.storedRequestId == pending.requestId &&
            metadata.storedObjectCrc == pending.crc && offsetMatches) return MagReceipt.STORED
        if (metadata.requestId == pending.requestId && metadata.objectCrc == pending.crc &&
            metadata.phase == 5) return MagReceipt.REJECTED
    } else if (status != null && metadata.bootId == pending.bootId && status.factoryCrc == pending.binding.factoryCrc) {
        if (magImportConfirmed(status, pending.requestId, pending.crc)) return MagReceipt.STORED
        if (status.requestId == pending.requestId && status.phase == 5) return MagReceipt.REJECTED
    }
    return MagReceipt.UNKNOWN
}

// A single atomic synchronous record per stable device. No destructive BLE write if commit() fails.
internal interface MagJournalStore {
    fun load(stableId: String): MagJournal
    fun save(stableId: String, journal: MagJournal)
}
internal class PreferencesMagJournalStore(private val prefs: SharedPreferences): MagJournalStore {
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun bytes(hex: String): ByteArray {
        require(hex.length % 2 == 0 && hex.length <= 160)
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    private fun binding(value: JSONObject) = RegattaLinkMagBinding(value.getString("device"),
        value.getLong("factory").toUInt(), value.getLong("upright").toUInt())
    private fun json(binding: RegattaLinkMagBinding) = JSONObject().put("device",binding.stableId)
        .put("factory",binding.factoryCrc.toLong()).put("upright",binding.uprightRef.toLong())
    override fun load(stableId: String): MagJournal {
        val raw = prefs.getString("v21.$stableId",null)
        if (raw == null) {
            // Old schema-20 pending imports cannot be proved after a reboot. Keep a visible conflict.
            require(prefs.getLong("$stableId.id",0) == 0L) { "Legacy unverified 3D import; explicitly discard recovery before continuing" }
            return MagJournal()
        }
        val root = JSONObject(raw)
        require(root.getInt("version") == 1)
        val pending = root.optJSONObject("pending")?.let {
            MagPending(MagObjectKind.valueOf(it.getString("kind")),binding(it),it.getLong("request").toUInt(),
                bytes(it.getString("object")),it.getLong("boot").toUInt())
        }
        val backup = root.optJSONObject("backup")?.let {
            val objectBytes = bytes(it.getString("object"))
            val bind = binding(it)
            MagPending(MagObjectKind.AUTO_2D,bind,1u,objectBytes,0u) // validate object integrity
            val trim = it.getInt("trim"); require(trim in -180..180)
            MagBackup(bind,objectBytes,trim,it.getLong("reset").toUInt(),MagRestoreStage.valueOf(it.getString("stage")))
        }
        require((pending?.binding?.stableId ?: stableId) == stableId && (backup?.binding?.stableId ?: stableId) == stableId)
        return MagJournal(pending,backup)
    }
    override fun save(stableId: String, journal: MagJournal) {
        val root = JSONObject().put("version",1)
        journal.pending?.let { p -> root.put("pending",json(p.binding).put("kind",p.kind.name)
            .put("request",p.requestId.toLong()).put("object",p.objectBytes.hex()).put("boot",p.bootId.toLong())) }
        journal.backup?.let { b -> root.put("backup",json(b.binding).put("object",b.objectBytes.hex())
            .put("trim",b.trimDeg).put("reset",b.resetRequest.toLong()).put("stage",b.stage.name)) }
        val edit = prefs.edit().remove("$stableId.id").remove("$stableId.crc")
        if (journal.pending == null && journal.backup == null) edit.remove("v21.$stableId")
        else edit.putString("v21.$stableId",root.toString())
        check(edit.commit()) { "Could not durably save MAG recovery; no further write is safe" }
    }
}
