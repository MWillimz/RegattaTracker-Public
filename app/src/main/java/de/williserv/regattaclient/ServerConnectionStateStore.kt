package de.williserv.regattaclient

import android.content.Context
import android.net.ConnectivityManager

internal enum class ServerConnectionState {
    UNKNOWN,
    REACHABLE,
    NO_CONNECTION
}

internal object ServerConnectionStateStore {
    private const val PREFS_NAME = "regatta_connection_state"
    private const val KEY_SERVER = "server"
    private const val KEY_STATE = "state"

    fun markReachable(context: Context, server: String) {
        write(context, server, ServerConnectionState.REACHABLE)
    }

    fun markNoConnection(context: Context, server: String) {
        write(context, server, ServerConnectionState.NO_CONNECTION)
    }

    fun state(context: Context, server: String): ServerConnectionState {
        val normalized = normalizeServer(server)
        if (normalized.isBlank()) return ServerConnectionState.UNKNOWN

        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_SERVER, "").orEmpty() != normalized) {
            return ServerConnectionState.UNKNOWN
        }

        return runCatching {
            ServerConnectionState.valueOf(prefs.getString(KEY_STATE, "").orEmpty())
        }.getOrDefault(ServerConnectionState.UNKNOWN)
    }

    fun hasActiveNetwork(context: Context): Boolean {
        val manager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network) != null
    }

    private fun write(
        context: Context,
        server: String,
        state: ServerConnectionState
    ) {
        val normalized = normalizeServer(server)
        if (normalized.isBlank()) return

        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER, normalized)
            .putString(KEY_STATE, state.name)
            .apply()
    }

    internal fun normalizeServer(server: String): String {
        val trimmed = server.trim().trimEnd('/')
        return if (trimmed.endsWith("/ingest")) trimmed.removeSuffix("/ingest") else trimmed
    }
}
