package de.williserv.regattaclient

data class BoatSetupValues(
    val boatName: String,
    val skipperName: String,
    val hullColor: String,
    val sailNumber: String,
    val yardstick: String,
    val boatType: String
)

internal fun isBoatSetupValid(values: BoatSetupValues): Boolean {
    val yardstick = values.yardstick.toDoubleOrNull() ?: return false

    return values.boatName.isNotBlank() &&
            values.skipperName.isNotBlank() &&
            values.sailNumber.isNotBlank() &&
            values.boatType.isNotBlank() &&
            yardstick.isFinite()
}

internal fun shouldInvalidateRaceRegistration(
    previous: BoatSetupValues,
    next: BoatSetupValues,
    hadConfirmedSetup: Boolean
): Boolean = hadConfirmedSetup && previous != next

internal fun shouldInvalidateRaceLegal(
    previous: BoatSetupValues,
    next: BoatSetupValues,
    hadConfirmedSetup: Boolean
): Boolean = hadConfirmedSetup && !hasSameLegalBoatIdentity(previous, next)

internal fun hasSameLegalBoatIdentity(
    first: BoatSetupValues,
    second: BoatSetupValues
): Boolean {
    return first.sailNumber == second.sailNumber &&
            first.boatName == second.boatName &&
            first.skipperName == second.skipperName
}
