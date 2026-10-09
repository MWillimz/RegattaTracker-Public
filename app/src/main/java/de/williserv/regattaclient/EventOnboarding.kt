package de.williserv.regattaclient

import java.time.Instant
import java.time.ZoneId

internal enum class OnboardingStep {
    BOAT_SETUP, REGISTER, ENTER_RACE, UPLOAD_CHECK
}

internal enum class OnboardingStatus {
    DONE, CURRENT, URGENT, FUTURE
}

internal data class EventOnboardingState(
    val statuses: List<OnboardingStatus>
) {
    val completedCount: Int get() = statuses.count { it == OnboardingStatus.DONE }
    val complete: Boolean get() = completedCount == statuses.size
    val completedMask: Int get() = statuses.indices
        .filter { statuses[it] == OnboardingStatus.DONE }
        .fold(0) { mask, index -> mask or (1 shl index) }
}

internal fun eventOnboardingState(
    boatSetupConfirmed: Boolean,
    eventKnown: Boolean,
    registered: Boolean,
    enteredRace: Boolean,
    trackingUploadConfirmed: Boolean,
    raceStartEpochMillis: Long?,
    canEnterRace: Boolean,
    nowEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): EventOnboardingState {
    val completed = listOf(
        boatSetupConfirmed,
        eventKnown && registered,
        eventKnown && enteredRace,
        eventKnown && trackingUploadConfirmed
    )
    val firstOpen = completed.indexOfFirst { !it }
    val statuses = OnboardingStep.entries.mapIndexed { index, step ->
        when {
            completed[index] -> OnboardingStatus.DONE
            index != firstOpen -> OnboardingStatus.FUTURE
            isEventOnboardingUrgent(
                step = step,
                eventKnown = eventKnown,
                raceStartEpochMillis = raceStartEpochMillis,
                canEnterRace = canEnterRace,
                nowEpochMillis = nowEpochMillis,
                zoneId = zoneId
            ) -> OnboardingStatus.URGENT
            step == OnboardingStep.UPLOAD_CHECK &&
                (raceStartEpochMillis == null ||
                    nowEpochMillis < raceStartEpochMillis - 10 * 60_000L) ->
                OnboardingStatus.FUTURE
            else -> OnboardingStatus.CURRENT
        }
    }
    return EventOnboardingState(statuses)
}

private fun isEventOnboardingUrgent(
    step: OnboardingStep,
    eventKnown: Boolean,
    raceStartEpochMillis: Long?,
    canEnterRace: Boolean,
    nowEpochMillis: Long,
    zoneId: ZoneId
): Boolean {
    if (!eventKnown || raceStartEpochMillis == null) return false
    return when (step) {
        OnboardingStep.BOAT_SETUP -> {
            val eventDay = Instant.ofEpochMilli(raceStartEpochMillis)
                .atZone(zoneId).toLocalDate()
            val today = Instant.ofEpochMilli(nowEpochMillis)
                .atZone(zoneId).toLocalDate()
            !today.isBefore(eventDay)
        }
        OnboardingStep.REGISTER ->
            nowEpochMillis >= raceStartEpochMillis - 72L * 60 * 60_000L
        OnboardingStep.ENTER_RACE ->
            canEnterRace && nowEpochMillis >= raceStartEpochMillis - 60 * 60_000L
        OnboardingStep.UPLOAD_CHECK -> nowEpochMillis >= raceStartEpochMillis
    }
}

internal fun eventOnboardingNewlyCompleted(previousMask: Int, currentMask: Int): Int =
    currentMask and previousMask.inv()

/**
 * A stable, opaque event identity: changing the event, credentials or resolved
 * series occurrence invalidates event-specific onboarding progress.
 */
internal fun eventOnboardingKey(
    serverUrl: String,
    eventName: String,
    secret: String,
    resolvedEventName: String
): String? {
    val key = normalizeAccessContextKey(serverUrl, eventName, secret) ?: return null
    val canonical = listOf(
        key.serverUrl, key.accessIdentifier, key.accessSecret,
        resolvedEventName.trim()
    ).joinToString("\u0000")
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
