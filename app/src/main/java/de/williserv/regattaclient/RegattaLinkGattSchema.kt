package de.williserv.regattaclient

import android.content.Context

internal const val REGATTALINK_GATT_SCHEMA_RECONCILE_TIMEOUT_MS = 10_000L

internal data class RegattaLinkGattSchemaDecision(
    val waitForRediscovery: Boolean,
    val requestServiceChanged: Boolean,
    val acceptReportedVersion: Boolean
)

internal fun regattaLinkGattSchemaDecision(
    reportedVersion: Int,
    acceptedVersion: Int?,
    connectionStartedBonded: Boolean,
    serviceChangedObserved: Boolean,
    serviceChangedRediscoveryCompleted: Boolean
): RegattaLinkGattSchemaDecision {
    require(reportedVersion in 0..255)

    if (!connectionStartedBonded) {
        return RegattaLinkGattSchemaDecision(
            waitForRediscovery = false,
            requestServiceChanged = false,
            acceptReportedVersion = true
        )
    }

    /*
     * Schema-aware firmware advertises a monotonic generation in Device Info.
     * Once this client has accepted that generation there is no reason to
     * invalidate Android's ATT cache on every reconnect.
     */
    if (acceptedVersion == reportedVersion) {
        return RegattaLinkGattSchemaDecision(
            waitForRediscovery = false,
            requestServiceChanged = false,
            acceptReportedVersion = false
        )
    }

    /*
     * A Service Changed callback alone is not sufficient. The connection may
     * use movable handles only after the rediscovery caused by that callback
     * has completed and Device Info has been read again from the fresh table.
     */
    if (serviceChangedRediscoveryCompleted) {
        return RegattaLinkGattSchemaDecision(
            waitForRediscovery = false,
            requestServiceChanged = false,
            acceptReportedVersion = true
        )
    }

    return RegattaLinkGattSchemaDecision(
        waitForRediscovery = true,
        requestServiceChanged =
            reportedVersion > 0 && !serviceChangedObserved,
        acceptReportedVersion = false
    )
}

internal fun shouldValidateRegattaLinkGattLayout(
    schemaAlreadyVerifiedThisProcess: Boolean,
    acceptReportedVersion: Boolean,
    forcedRediscoveryPendingValidation: Boolean
): Boolean =
    acceptReportedVersion ||
        forcedRediscoveryPendingValidation ||
        !schemaAlreadyVerifiedThisProcess

internal class RegattaLinkGattSchemaStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "regattalink_gatt_schema"
        private const val KEY_PREFIX = "accepted_"
    }

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun acceptedVersion(stableId: String): Int? {
        if (stableId.isBlank()) return null
        val key = KEY_PREFIX + stableId.lowercase()
        if (!prefs.contains(key)) return null
        val value = prefs.getInt(key, 0)
        if (value !in 1..255) {
            /*
             * Schema 0 means legacy/unspecified firmware. Persisting it was a
             * migration bug: it made later app processes trust Android's ATT
             * cache without observing that boot's Service Changed. Drop any
             * value written by the affected client build.
             */
            prefs.edit().remove(key).apply()
            return null
        }
        return value
    }

    fun accept(stableId: String, version: Int) {
        require(stableId.isNotBlank())
        require(version in 1..255)
        prefs.edit()
            .putInt(KEY_PREFIX + stableId.lowercase(), version)
            .apply()
    }

    fun clear(stableId: String) {
        if (stableId.isBlank()) return
        prefs.edit()
            .remove(KEY_PREFIX + stableId.lowercase())
            .apply()
    }
}
