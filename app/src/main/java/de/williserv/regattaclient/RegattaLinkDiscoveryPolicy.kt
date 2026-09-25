package de.williserv.regattaclient

internal const val REGATTALINK_MANUAL_DISCOVERY_TIMEOUT_MS = 120_000L

internal fun regattaLinkDiscoveryRemainingMs(
    deadlineElapsedMs: Long,
    nowElapsedMs: Long
): Long = (deadlineElapsedMs - nowElapsedMs).coerceAtLeast(0L)

internal fun regattaLinkDiscoveryStageTimeoutMs(
    stageTimeoutMs: Long,
    deadlineElapsedMs: Long,
    nowElapsedMs: Long
): Long = minOf(
    stageTimeoutMs.coerceAtLeast(0L),
    regattaLinkDiscoveryRemainingMs(deadlineElapsedMs, nowElapsedMs)
)

internal fun shouldSkipRejectedRegattaLinkDiscoveryCandidate(
    bondingObserved: Boolean,
    currentlyUnbonded: Boolean
): Boolean = bondingObserved && currentlyUnbonded
