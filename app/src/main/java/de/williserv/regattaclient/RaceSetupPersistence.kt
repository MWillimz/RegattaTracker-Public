package de.williserv.regattaclient

internal const val RACE_RAW_STATE_VERSION = 1

internal data class LegacyCourseMarkState(
    val order: Int?,
    val label: String,
    val skipped: Boolean
)

internal data class LegacyRaceDisplayState(
    val raceStatus: String,
    val raceStart: String,
    val raceStop: String,
    val raceInfo: String,
    val courseShortened: Boolean,
    val courseMarks: List<LegacyCourseMarkState>
)

internal fun legacyDisplayPayload(value: String): String {
    val separatorIndex = value.indexOf(':')
    return if (separatorIndex >= 0) {
        value.substring(separatorIndex + 1).trim()
    } else {
        value.trim()
    }
}

internal fun migrateLegacyRaceDisplayState(
    raceStatusText: String,
    raceStartText: String,
    raceStopText: String,
    raceInfoText: String,
    raceShortenedText: String,
    raceMarksText: String
): LegacyRaceDisplayState {
    return LegacyRaceDisplayState(
        raceStatus = legacyDisplayPayload(raceStatusText),
        raceStart = legacyDisplayPayload(raceStartText),
        raceStop = legacyDisplayPayload(raceStopText),
        raceInfo = legacyDisplayPayload(raceInfoText),
        courseShortened = legacyCourseShortened(raceShortenedText),
        courseMarks = legacyCourseMarkStates(raceMarksText)
    )
}

internal fun legacyCourseShortened(value: String): Boolean {
    return when (legacyDisplayPayload(value).lowercase()) {
        "yes", "ja", "oui", "si", "sì", "true", "1" -> true
        else -> false
    }
}

internal fun legacyCourseMarkStates(value: String): List<LegacyCourseMarkState> {
    val payload = legacyDisplayPayload(value)
    if (payload.isBlank() || payload == "--") {
        return emptyList()
    }

    return payload
        .split(',')
        .mapNotNull { raw ->
            var label = raw.trim()
            if (label.isBlank() || label == "--") {
                return@mapNotNull null
            }

            val skippedMarker = LEGACY_SKIPPED_MARKERS.firstOrNull { marker ->
                label.contains(marker, ignoreCase = true)
            }
            val skipped = skippedMarker != null
            if (skippedMarker != null) {
                label = label.replace(skippedMarker, "", ignoreCase = true).trim()
            }

            if (label.isBlank()) {
                null
            } else {
                LegacyCourseMarkState(
                    order = label.substringBefore(' ').toIntOrNull(),
                    label = label,
                    skipped = skipped
                )
            }
        }
}

private val LEGACY_SKIPPED_MARKERS = listOf(
    "[skipped]",
    "[übersprungen]",
    "[omise]",
    "[omessa]",
    "[omitida]"
)
