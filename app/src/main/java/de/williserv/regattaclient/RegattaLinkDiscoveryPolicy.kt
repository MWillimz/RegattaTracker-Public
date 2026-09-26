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


internal const val REGATTALINK_STALE_ANDROID_BOND_ERROR =
    "Android still reports this RegattaLink as paired, but the secured connection failed. " +
        "Remove RegattaLink in Android Bluetooth settings, then tap Search again."

internal fun regattaLinkManualDiscoveryExhaustedMessage(
    staleBondFailureObserved: Boolean
): String =
    if (staleBondFailureObserved) {
        REGATTALINK_STALE_ANDROID_BOND_ERROR
    } else {
        "No available RegattaLink found"
    }
