package de.williserv.regattaclient

internal data class RaceEntrySample(
    val sequenceId: Long,
    val timestamp: String,
    val boatName: String,
    val captainName: String,
    val hullColor: String,
    val sailNumber: String,
    val yardstick: Double,
    val boatType: String,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val accuracy: Float = 9999f,
    val cog: Float = 0f,
    val sog: Float = 0f,
    val accelX: Float = 0f,
    val accelY: Float = 0f,
    val accelZ: Float = 0f,
    val gyroX: Float = 0f,
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f
)

internal fun buildRaceEntrySample(
    rawRaceStart: String,
    boatSetup: BoatSetupValues,
    sequenceId: Long = System.currentTimeMillis()
): RaceEntrySample? {
    val timestamp = RaceRegistrationPolicy.registrationTimestamp(rawRaceStart) ?: return null

    return RaceEntrySample(
        sequenceId = sequenceId,
        timestamp = timestamp,
        boatName = boatSetup.boatName,
        captainName = boatSetup.skipperName,
        hullColor = boatSetup.hullColor,
        sailNumber = boatSetup.sailNumber,
        yardstick = boatSetup.yardstick.toDoubleOrNull() ?: 0.0,
        boatType = boatSetup.boatType
    )
}
