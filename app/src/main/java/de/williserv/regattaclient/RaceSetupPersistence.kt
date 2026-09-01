package de.williserv.regattaclient

internal const val RACE_RAW_STATE_VERSION = 1

internal fun legacyDisplayPayload(value: String): String {
    val separatorIndex = value.indexOf(':')
    return if (separatorIndex >= 0) {
        value.substring(separatorIndex + 1).trim()
    } else {
        value.trim()
    }
}
