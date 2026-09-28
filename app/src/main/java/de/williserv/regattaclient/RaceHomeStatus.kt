package de.williserv.regattaclient

internal const val RACE_OPERATIONAL_PRESTART_MILLIS = 30L * 60L * 1000L

internal enum class RaceHomeStatus {
    CHECKING,
    NOT_ENTERED,
    REGISTERED_NOT_ENTERED,
    READY,
    RACING,
    SERVER_STATUS
}

internal fun resolveRaceHomeStatus(
    raceStatusCode: String,
    raceDataReady: Boolean,
    raceConfigured: Boolean,
    inRace: Boolean,
    raceRegistered: Boolean,
    millisToStart: Long?
): RaceHomeStatus {
    if (!raceDataReady) {
        return if (raceConfigured && raceStatusCode.isBlank()) {
            RaceHomeStatus.CHECKING
        } else {
            RaceHomeStatus.SERVER_STATUS
        }
    }

    val status = raceStatusCode.trim().lowercase()
    if (status in setOf("finished", "postponed", "cancelled")) {
        return RaceHomeStatus.SERVER_STATUS
    }

    val raceRunning = status == "racing" || status == "started"
    val inOperationalPrestart =
        millisToStart != null &&
            millisToStart <= RACE_OPERATIONAL_PRESTART_MILLIS

    if (!inRace && (raceRunning || inOperationalPrestart)) {
        return RaceHomeStatus.NOT_ENTERED
    }

    if (inRace && raceRunning) {
        return RaceHomeStatus.RACING
    }

    if (inRace && inOperationalPrestart) {
        return RaceHomeStatus.READY
    }

    if (!inRace && raceRegistered) {
        return RaceHomeStatus.REGISTERED_NOT_ENTERED
    }

    return RaceHomeStatus.SERVER_STATUS
}
