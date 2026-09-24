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
