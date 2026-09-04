package de.williserv.regattaclient

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal const val CLIENT_VERSION_TOO_OLD_REASON = "client_version_too_old"
internal const val CLIENT_VERSION_REQUIRED_REASON = "client_version_required"
internal val CLIENT_COMPATIBILITY_RECHECK_INTERVAL_MILLIS: Long = TimeUnit.HOURS.toMillis(1)

internal data class ClientCompatibilityError(
    val reason: String,
    val minimumVersionCode: Int,
    val clientVersionCode: Int?
)

internal fun parseClientCompatibilityError(body: String): ClientCompatibilityError? {
    return runCatching {
        val detail = JSONObject(body).optJSONObject("detail") ?: return null
        val reason = detail.optString("reason", "").trim()
        if (reason != CLIENT_VERSION_TOO_OLD_REASON && reason != CLIENT_VERSION_REQUIRED_REASON) {
            return null
        }

        val minimumVersionCode = strictOptionalInt(detail, "min_client_version_code")
            ?.takeIf { it > 0 }
            ?: return null
        val clientVersionCode = strictOptionalInt(detail, "client_version_code")

        when (reason) {
            CLIENT_VERSION_TOO_OLD_REASON -> if (clientVersionCode == null) return null
            CLIENT_VERSION_REQUIRED_REASON -> if (clientVersionCode != null) return null
        }

        ClientCompatibilityError(
            reason = reason,
            minimumVersionCode = minimumVersionCode,
            clientVersionCode = clientVersionCode
        )
    }.getOrNull()
}

private fun strictOptionalInt(json: JSONObject, key: String): Int? {
    if (!json.has(key) || json.isNull(key)) return null

    return when (val value = json.get(key)) {
        is Int -> value
        is Long -> {
            if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                throw IllegalArgumentException("$key is outside the supported integer range")
            }
            value.toInt()
        }

        else -> throw IllegalArgumentException("$key must be an integer or null")
    }
}

internal fun shouldTreatAsClientUpdateRequired(
    responseCode: Int,
    errorBody: String,
    client: ClientBuildIdentity
): Boolean {
    if (responseCode != 426 || client.isDevDebug) return false
    return parseClientCompatibilityError(errorBody) != null
}

internal object ClientCompatibilityBlockStore {
    private const val PREFS_NAME = "regatta_local_status"
    private const val BLOCKED_SERVER_VERSIONS_KEY = "client_update_required_server_versions"

    fun markBlocked(
        context: Context,
        serverUrl: String,
        versionCode: Int,
        blockedAtMillis: Long = System.currentTimeMillis()
    ) {
        if (versionCode == DEV_DEBUG_VERSION_CODE) return

        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val entries = prefs.getStringSet(BLOCKED_SERVER_VERSIONS_KEY, emptySet())
            .orEmpty()
            .toMutableSet()
        val prefix = blockPrefix(serverUrl, versionCode)
        entries.removeAll { it.startsWith(prefix) }
        entries += "$prefix$blockedAtMillis"
        prefs.edit().putStringSet(BLOCKED_SERVER_VERSIONS_KEY, entries).apply()
    }

    fun isBlocked(
        context: Context,
        serverUrl: String,
        versionCode: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (versionCode == DEV_DEBUG_VERSION_CODE) return false

        val prefix = blockPrefix(serverUrl, versionCode)
        val latestBlockedAt = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(BLOCKED_SERVER_VERSIONS_KEY, emptySet())
            .orEmpty()
            .asSequence()
            .filter { it.startsWith(prefix) }
            .mapNotNull { it.removePrefix(prefix).toLongOrNull() }
            .maxOrNull()
            ?: return false

        if (nowMillis <= latestBlockedAt) return true
        return nowMillis - latestBlockedAt < CLIENT_COMPATIBILITY_RECHECK_INTERVAL_MILLIS
    }

    fun clearBlocked(context: Context, serverUrl: String, versionCode: Int) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val entries = prefs.getStringSet(BLOCKED_SERVER_VERSIONS_KEY, emptySet())
            .orEmpty()
            .toMutableSet()
        val prefix = blockPrefix(serverUrl, versionCode)
        if (entries.removeAll { it.startsWith(prefix) }) {
            prefs.edit().putStringSet(BLOCKED_SERVER_VERSIONS_KEY, entries).apply()
        }
    }

    fun hasAnyBlockForVersion(context: Context, versionCode: Int): Boolean {
        if (versionCode == DEV_DEBUG_VERSION_CODE) return false

        val prefix = "$versionCode\n"
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(BLOCKED_SERVER_VERSIONS_KEY, emptySet())
            .orEmpty()
            .any { it.startsWith(prefix) }
    }

    private fun blockPrefix(serverUrl: String, versionCode: Int): String =
        "$versionCode\n${normalizeServerUrl(serverUrl)}\n"

    private fun normalizeServerUrl(serverUrl: String): String {
        val trimmed = serverUrl.trim().trimEnd('/')
        return if (trimmed.endsWith("/ingest")) trimmed.removeSuffix("/ingest") else trimmed
    }
}
