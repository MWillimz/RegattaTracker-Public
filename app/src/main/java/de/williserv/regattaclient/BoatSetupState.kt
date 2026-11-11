package de.williserv.regattaclient

internal const val MIN_YARDSTICK = 40
internal const val MAX_YARDSTICK = 160
internal val BOAT_SETUP_LINE_BREAKS = Regex("""[\r\n\u000B\u000C\u0085\u2028\u2029]+""")

data class BoatSetupValues(
    val boatName: String,
    val skipperName: String,
    val hullColor: String,
    val sailNumber: String,
    val yardstick: String,
    val boatType: String
)

internal enum class BoatSetupFieldError {
    REQUIRED,
    SINGLE_LINE_LIMIT,
    INVALID_YARDSTICK
}

internal enum class BoatSetupField(val maxLength: Int, private val required: Boolean = true) {
    BOAT_NAME(80),
    SKIPPER(80),
    HULL_COLOR(40, required = false),
    SAIL_NUMBER(40),
    YARDSTICK(16),
    BOAT_TYPE(80);

    fun acceptInput(previous: String, proposed: String): String {
        val singleLine = proposed.replace(BOAT_SETUP_LINE_BREAKS, " ").replace('\t', ' ')
        val normalized = if (this == YARDSTICK) singleLine.replace(',', '.') else singleLine

        // Allow an overlong saved value to be shortened in several edits.
        return if (normalized.length <= maxLength || normalized.length < previous.length) {
            normalized
        } else {
            previous
        }
    }

    fun validationError(value: String): BoatSetupFieldError? = when {
        value.length > maxLength || BOAT_SETUP_LINE_BREAKS.containsMatchIn(value) ->
            BoatSetupFieldError.SINGLE_LINE_LIMIT
        this == YARDSTICK && !isYardstickValid(value) -> BoatSetupFieldError.INVALID_YARDSTICK
        required && value.isBlank() -> BoatSetupFieldError.REQUIRED
        else -> null
    }
}

private fun isYardstickValid(value: String): Boolean {
    val yardstick = value.toDoubleOrNull() ?: return false
    return yardstick.isFinite() && yardstick >= MIN_YARDSTICK && yardstick <= MAX_YARDSTICK
}

internal fun isBoatSetupValid(values: BoatSetupValues): Boolean =
    BoatSetupField.BOAT_NAME.validationError(values.boatName) == null &&
        BoatSetupField.SKIPPER.validationError(values.skipperName) == null &&
        BoatSetupField.HULL_COLOR.validationError(values.hullColor) == null &&
        BoatSetupField.SAIL_NUMBER.validationError(values.sailNumber) == null &&
        BoatSetupField.YARDSTICK.validationError(values.yardstick) == null &&
        BoatSetupField.BOAT_TYPE.validationError(values.boatType) == null

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
