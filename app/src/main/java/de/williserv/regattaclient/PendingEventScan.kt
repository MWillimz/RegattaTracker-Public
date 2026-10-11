package de.williserv.regattaclient

import org.json.JSONObject

/** Uncommitted scan: the currently loaded event and its state stay untouched. */
internal data class PendingEventScan(
    val token: Long,
    val access: EventAccessKey,
    val previousAccess: EventAccessKey?,
    val notice: PendingEventNotice? = null,
    val statusText: String = "",
    val accepting: Boolean = false
)

internal data class PendingEventNotice(
    val resolvedEventName: String,
    val legalText: String,
    val legalHash: String
)

/** Do not act on a scan reply after cancel, replacement or a change of active event. */
internal fun isCurrentPendingEventScan(
    pending: PendingEventScan?,
    requested: PendingEventScan,
    currentActiveAccess: EventAccessKey?
): Boolean = pending?.token == requested.token &&
    pending.access == requested.access &&
    pending.previousAccess == currentActiveAccess

/** Race Notice links always refer to the staged scan if one is awaiting consent. */
internal fun raceNoticeEventPageUrl(
    pending: PendingEventScan?,
    active: EventAccessKey?
): String? {
    val access = pending?.access ?: active ?: return null
    return buildEventAccessUrl(access.server, access.event, access.secret)
}

internal fun parsePendingEventNotice(body: String): PendingEventNotice? {
    val json = JSONObject(body)
    val resolvedEventName = json.optString("event_name", "").trim()
    val legalText = json.optString("invitation_legal_text", "").trim()
    val legalHash = json.optString("legal_text_hash", "").trim()
    if (resolvedEventName.isBlank() || legalText.isBlank() || legalHash.isBlank()) {
        return null
    }
    return PendingEventNotice(resolvedEventName, legalText, legalHash)
}
