package de.williserv.regattaclient

enum class RegattaLinkMagPhase {
    IDLE, READING, CAPTURING, CAPTURE_READY, FITTING_2D, PROPOSAL_2D,
    EXPORTING, ARCHIVE_READY, FITTING, FIT_READY,
    TRANSFERRING, VALIDATING, PERSISTING, STORED, RESTORING, UNKNOWN, ERROR
}

data class RegattaLinkMagState(
    val supported: Boolean = false,
    val phase: RegattaLinkMagPhase = RegattaLinkMagPhase.IDLE,
    val status: RegattaLinkMagStatus? = null,
    val metadata: RegattaLinkMagAutoMetadata? = null,
    val archive: RegattaLinkMagArchive? = null,
    val fit: RegattaLinkMagFit? = null,
    val autoFit: RegattaLinkMagAutoFit? = null,
    val captureSamples: Int = 0,
    val progress: Int = 0,
    val requestId: UInt? = null,
    val recoveryAvailable: Boolean = false,
    val operationRunning: Boolean = false,
    val errorStage: String = "",
    val error: String = ""
) {
    val busy: Boolean get() = recoveryAvailable || phase in setOf(
        RegattaLinkMagPhase.READING, RegattaLinkMagPhase.CAPTURING,
        RegattaLinkMagPhase.FITTING_2D, RegattaLinkMagPhase.EXPORTING,
        RegattaLinkMagPhase.FITTING, RegattaLinkMagPhase.TRANSFERRING,
        RegattaLinkMagPhase.VALIDATING, RegattaLinkMagPhase.PERSISTING,
        RegattaLinkMagPhase.RESTORING, RegattaLinkMagPhase.UNKNOWN
    )
}
