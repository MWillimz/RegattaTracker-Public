package de.williserv.regattaclient

import org.json.JSONArray
import org.json.JSONObject

internal enum class TelemetryBatchSampleStatus {
    ACCEPTED,
    TEMPORARY_REJECTION,
    CLIENT_UPDATE_REQUIRED,
    OTHER_REJECTION
}

internal data class TelemetryBatchSampleResult(
    val localId: Long,
    val status: TelemetryBatchSampleStatus,
    val code: String? = null
)

internal data class ParsedTelemetryBatchResponse(
    val results: List<TelemetryBatchSampleResult>
) {
    val acceptedLocalIds: List<Long>
        get() = results
            .asSequence()
            .filter { it.status == TelemetryBatchSampleStatus.ACCEPTED }
            .map { it.localId }
            .toList()

    val hasTemporaryRejection: Boolean
        get() = results.any { it.status == TelemetryBatchSampleStatus.TEMPORARY_REJECTION }

    val clientUpdateRequired: Boolean
        get() = results.any { it.status == TelemetryBatchSampleStatus.CLIENT_UPDATE_REQUIRED }

    val firstOtherRejectionCode: String?
        get() = results.firstOrNull {
            it.status == TelemetryBatchSampleStatus.OTHER_REJECTION
        }?.code
}

internal fun buildTelemetryBatchUploadPayload(
    samples: List<PendingTrackingSample>,
    client: ClientBuildIdentity
): JSONObject {
    val payloads = JSONArray()
    samples.forEach { sample ->
        payloads.put(buildTelemetryUploadPayload(sample, client))
    }

    return JSONObject().put("samples", payloads)
}

internal fun parseTelemetryBatchUploadResponse(
    body: String,
    samples: List<PendingTrackingSample>
): ParsedTelemetryBatchResponse? {
    if (samples.isEmpty()) return null

    val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val responseResults = json.optJSONArray("results") ?: return null

    if (responseResults.length() != samples.size) return null

    val seenIndexes = BooleanArray(samples.size)
    val parsed = ArrayList<TelemetryBatchSampleResult>(samples.size)

    for (resultIndex in 0 until responseResults.length()) {
        val item = responseResults.optJSONObject(resultIndex) ?: return null
        val index = strictJsonIndex(item, "index") ?: return null

        if (index !in samples.indices || seenIndexes[index]) return null
        seenIndexes[index] = true

        val status = item.optString("status", "")
        val sample = samples[index]

        when (status) {
            "accepted" -> {
                parsed += TelemetryBatchSampleResult(
                    localId = sample.localId,
                    status = TelemetryBatchSampleStatus.ACCEPTED
                )
            }

            "rejected" -> {
                val code = item.optString("code", "").trim()
                if (code.isBlank()) return null

                val classified = when (code) {
                    "server_busy" -> TelemetryBatchSampleStatus.TEMPORARY_REJECTION
                    "client_version_rejected" -> {
                        TelemetryBatchSampleStatus.CLIENT_UPDATE_REQUIRED
                    }
                    else -> TelemetryBatchSampleStatus.OTHER_REJECTION
                }

                parsed += TelemetryBatchSampleResult(
                    localId = sample.localId,
                    status = classified,
                    code = code
                )
            }

            else -> return null
        }
    }

    if (seenIndexes.any { !it }) return null

    return ParsedTelemetryBatchResponse(
        results = parsed.sortedBy { result ->
            samples.indexOfFirst { it.localId == result.localId }
        }
    )
}

private fun strictJsonIndex(
    json: JSONObject,
    key: String
): Int? {
    if (!json.has(key) || json.isNull(key)) return null

    return when (val value = json.get(key)) {
        is Int -> value
        is Long -> value
            .takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
            ?.toInt()
        else -> null
    }
}
