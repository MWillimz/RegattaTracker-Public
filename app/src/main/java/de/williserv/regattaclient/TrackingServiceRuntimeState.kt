package de.williserv.regattaclient

internal enum class TrackingServiceRuntimeStatus {
    STOPPED,
    STARTING,
    ACTIVE
}

internal object TrackingServiceRuntimeState {
    @Volatile
    private var status = TrackingServiceRuntimeStatus.STOPPED

    fun markStarting() {
        status = TrackingServiceRuntimeStatus.STARTING
    }

    fun markActive() {
        status = TrackingServiceRuntimeStatus.ACTIVE
    }

    fun markStopped() {
        status = TrackingServiceRuntimeStatus.STOPPED
    }

    fun currentStatus(): TrackingServiceRuntimeStatus = status

    fun isActive(): Boolean = status == TrackingServiceRuntimeStatus.ACTIVE
}

internal data class EffectiveTrackingRuntimeState(
    val inRace: Boolean,
    val manualTracking: Boolean
)

internal fun effectiveTrackingRuntimeState(
    persistedInRace: Boolean,
    persistedManual: Boolean,
    runtimeStatus: TrackingServiceRuntimeStatus
): EffectiveTrackingRuntimeState {
    val serviceOwnsTrackingState = runtimeStatus != TrackingServiceRuntimeStatus.STOPPED
    return EffectiveTrackingRuntimeState(
        inRace = serviceOwnsTrackingState && persistedInRace,
        manualTracking = serviceOwnsTrackingState && persistedManual
    )
}
