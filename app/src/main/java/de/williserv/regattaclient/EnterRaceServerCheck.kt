package de.williserv.regattaclient

internal const val ENTER_RACE_SERVER_CHECK_TIMEOUT_MILLIS = 3_000L

internal class EnterRaceServerCheckState {
    private var nextGeneration = 0L
    private var activeGeneration: Long? = null

    fun begin(): Long {
        nextGeneration += 1L
        return nextGeneration.also { activeGeneration = it }
    }

    fun isActive(generation: Long?): Boolean =
        generation == null || activeGeneration == generation

    fun finish(generation: Long?) {
        if (generation != null && activeGeneration == generation) {
            activeGeneration = null
        }
    }

    fun cancel() {
        activeGeneration = null
    }
}
