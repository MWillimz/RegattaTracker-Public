package de.williserv.regattaclient

enum class RegattaLinkOtaPhase {
    IDLE,
    PREPARING,
    STARTING,
    TRANSFERRING,
    VERIFYING,
    REBOOTING,
    RECONNECTING,
    VALIDATING,
    SUCCESS,
    CANCELLING,
    CANCELLED,
    ERROR
}

data class RegattaLinkOtaUiState(
    val phase: RegattaLinkOtaPhase = RegattaLinkOtaPhase.IDLE,
    val installedBuild: String = "",
    val targetBuild: String = "",
    val committedBytes: Int = 0,
    val totalBytes: Int = 0,
    val throughputKibPerSec: Double? = null,
    val transport: String = "",
    val detail: String = "",
    val error: String = ""
) {
    val progress: Float
        get() = if (totalBytes > 0) {
            (committedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val isActive: Boolean
        get() = phase in setOf(
            RegattaLinkOtaPhase.PREPARING,
            RegattaLinkOtaPhase.STARTING,
            RegattaLinkOtaPhase.TRANSFERRING,
            RegattaLinkOtaPhase.VERIFYING,
            RegattaLinkOtaPhase.REBOOTING,
            RegattaLinkOtaPhase.RECONNECTING,
            RegattaLinkOtaPhase.VALIDATING,
            RegattaLinkOtaPhase.CANCELLING
        )
}
