package de.williserv.regattaclient

import android.content.Context
import org.json.JSONObject

internal data class ParticipantRetirementIdentity(
    val sailNumber: String,
    val boatName: String,
    val captainName: String
) {
    fun normalized() = ParticipantRetirementIdentity(
        sailNumber.trim(),
        boatName.trim(),
        captainName.trim()
    )

    fun isComplete(): Boolean {
        val value = normalized()
        return value.sailNumber.isNotBlank() &&
            value.boatName.isNotBlank() &&
            value.captainName.isNotBlank()
    }
}

internal data class ParticipantRetirementReceipt(
    val resolvedEventName: String,
    val identity: ParticipantRetirementIdentity,
    val reportedAt: String
)

internal fun buildParticipantRetirementPayload(
    eventName: String,
    identity: ParticipantRetirementIdentity
): JSONObject {
    val value = identity.normalized()
    return JSONObject().apply {
        put("event_name", eventName.trim())
        put("sail_number", value.sailNumber)
        put("boat_name", value.boatName)
        put("captain_name", value.captainName)
    }
}

internal fun parseParticipantRetirementReceipt(
    body: String,
    expectedIdentity: ParticipantRetirementIdentity
): ParticipantRetirementReceipt? = runCatching {
    val json = JSONObject(body)
    if (!json.optString("status").equals("ret", ignoreCase = true)) return@runCatching null

    val eventName = json.optString("event_name").trim()
    val reportedAt = json.optString("reported_at").trim()
    val identity = ParticipantRetirementIdentity(
        json.optString("sail_number"),
        json.optString("boat_name"),
        json.optString("captain_name")
    ).normalized()

    if (eventName.isBlank() || reportedAt.isBlank() || identity != expectedIdentity.normalized()) {
        return@runCatching null
    }

    ParticipantRetirementReceipt(eventName, identity, reportedAt)
}.getOrNull()

internal object ParticipantRetirementStore {
    private const val PREFS = "participant_retirement_self_report"

    fun save(context: Context, receipt: ParticipantRetirementReceipt) {
        val identity = receipt.identity.normalized()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("resolved_event_name", receipt.resolvedEventName.trim())
            .putString("sail_number", identity.sailNumber)
            .putString("boat_name", identity.boatName)
            .putString("captain_name", identity.captainName)
            .putString("reported_at", receipt.reportedAt.trim())
            .commit()
    }

    fun matches(
        context: Context,
        resolvedEventName: String,
        identity: ParticipantRetirementIdentity
    ): Boolean {
        val value = identity.normalized()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("resolved_event_name", "").orEmpty() == resolvedEventName.trim() &&
            prefs.getString("sail_number", "").orEmpty() == value.sailNumber &&
            prefs.getString("boat_name", "").orEmpty() == value.boatName &&
            prefs.getString("captain_name", "").orEmpty() == value.captainName
    }

    internal fun clearForTests(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
}
