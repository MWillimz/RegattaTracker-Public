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
