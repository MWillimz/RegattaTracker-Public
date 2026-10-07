package de.williserv.regattaclient

internal fun shouldStartRegattaLinkOutageReconnect(
    connectionWasReady: Boolean,
    otaOwnsConnection: Boolean,
    knownReconnectAlreadyActive: Boolean
): Boolean =
    connectionWasReady &&
        !otaOwnsConnection &&
        !knownReconnectAlreadyActive

internal fun regattaLinkReconnectRemainingMs(
    deadlineElapsedMs: Long,
    nowElapsedMs: Long
): Long = (deadlineElapsedMs - nowElapsedMs).coerceAtLeast(0L)

internal enum class RegattaLinkHomeStatus {
    WAITING,
    ERROR,
    CONNECTED
}

internal fun regattaLinkHomeStatus(
    state: RegattaLinkClientState,
    pairingRequired: Boolean = false
): RegattaLinkHomeStatus = when {
    state.status == RegattaLinkConnectionStatus.CONNECTED ->
        RegattaLinkHomeStatus.CONNECTED
    state.status == RegattaLinkConnectionStatus.ERROR ||
        state.status == RegattaLinkConnectionStatus.BLUETOOTH_OFF ||
        pairingRequired ->
        RegattaLinkHomeStatus.ERROR
    else ->
        RegattaLinkHomeStatus.WAITING
}

internal fun shouldDeferRegattaLinkTerminalOtaState(
    otaOwnsConnection: Boolean,
    phase: RegattaLinkOtaPhase
): Boolean =
    otaOwnsConnection &&
        phase in setOf(
            RegattaLinkOtaPhase.SUCCESS,
            RegattaLinkOtaPhase.CANCELLED,
            RegattaLinkOtaPhase.ERROR
        )
