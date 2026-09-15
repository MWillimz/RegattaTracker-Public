package de.williserv.regattaclient

internal object SampleMetadataPolicy {
    const val HEARTBEAT_INTERVAL_MS = 60_000L

    fun shouldReadBattery(lastReadAtMs: Long?, nowMs: Long): Boolean {
        return lastReadAtMs == null || nowMs - lastReadAtMs >= HEARTBEAT_INTERVAL_MS
    }

    fun shouldEmitTrackingProfile(
        lastEmittedProfile: String?,
        lastEmittedAtMs: Long?,
        currentProfile: String,
        nowMs: Long
    ): Boolean {
        return lastEmittedProfile == null ||
            lastEmittedProfile != currentProfile ||
            lastEmittedAtMs == null ||
            nowMs - lastEmittedAtMs >= HEARTBEAT_INTERVAL_MS
    }
}
