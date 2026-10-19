package de.williserv.regattaclient

internal enum class RegattaLinkMagPhase {
    IDLE, READING, EXPORTING, ARCHIVE_READY, FITTING, FIT_READY,
    TRANSFERRING, VALIDATING, PERSISTING, STORED, UNKNOWN, ERROR
}

internal data class RegattaLinkMagState(
    val supported: Boolean = false,
    val phase: RegattaLinkMagPhase = RegattaLinkMagPhase.IDLE,
    val status: RegattaLinkMagStatus? = null,
    val archive: RegattaLinkMagArchive? = null,
    val fit: RegattaLinkMagFit? = null,
    val progress: Int = 0,
    val requestId: UInt? = null,
    val errorStage: String = "",
    val error: String = ""
) {
    val busy: Boolean get() = phase in setOf(
        RegattaLinkMagPhase.READING, RegattaLinkMagPhase.EXPORTING,
        RegattaLinkMagPhase.FITTING, RegattaLinkMagPhase.TRANSFERRING,
        RegattaLinkMagPhase.VALIDATING, RegattaLinkMagPhase.PERSISTING
    )
}
