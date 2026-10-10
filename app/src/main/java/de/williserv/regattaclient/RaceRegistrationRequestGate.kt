package de.williserv.regattaclient

/**
 * Keeps obsolete HTTP registration callbacks from updating a newer QR import
 * or a newer registration attempt, even when their event credentials match.
 * All calls run on the Activity's main thread.
 */
internal class RaceRegistrationRequestGate {
    private var generation = 0L

    fun begin(): Long {
        generation++
        return generation
    }

    fun invalidate() {
        generation++
    }

    fun isCurrent(requestGeneration: Long): Boolean =
        requestGeneration == generation
}
