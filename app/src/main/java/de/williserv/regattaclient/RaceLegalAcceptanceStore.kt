package de.williserv.regattaclient

import android.content.Context

internal data class RaceLegalAcceptanceCacheEntry(
    val resolvedEventName: String,
    val legalTextHash: String
)

internal class RaceLegalAcceptanceStore(context: Context) {
    companion object {
        const val PREFS_NAME = "race_legal_acceptance"
        private const val KEY_RESOLVED_EVENT_NAME = "resolved_event_name"
        private const val KEY_LEGAL_TEXT_HASH = "legal_text_hash"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): RaceLegalAcceptanceCacheEntry? {
        val resolvedEventName = prefs
            .getString(KEY_RESOLVED_EVENT_NAME, "")
            .orEmpty()
            .trim()
        val legalTextHash = prefs
            .getString(KEY_LEGAL_TEXT_HASH, "")
            .orEmpty()
            .trim()

        if (resolvedEventName.isBlank() || legalTextHash.isBlank()) {
            return null
        }

        return RaceLegalAcceptanceCacheEntry(
            resolvedEventName = resolvedEventName,
            legalTextHash = legalTextHash
        )
    }

    fun matches(
        resolvedEventName: String,
        legalTextHash: String
    ): Boolean {
        val expectedEvent = resolvedEventName.trim()
        val expectedHash = legalTextHash.trim()
        if (expectedEvent.isBlank() || expectedHash.isBlank()) return false

        val cached = load() ?: return false
        return cached.resolvedEventName == expectedEvent &&
            cached.legalTextHash == expectedHash
    }

    fun save(
        resolvedEventName: String,
        legalTextHash: String
    ) {
        val event = resolvedEventName.trim()
        val hash = legalTextHash.trim()
        if (event.isBlank() || hash.isBlank()) return

        prefs.edit()
            .putString(KEY_RESOLVED_EVENT_NAME, event)
            .putString(KEY_LEGAL_TEXT_HASH, hash)
            .commit()
    }
}
