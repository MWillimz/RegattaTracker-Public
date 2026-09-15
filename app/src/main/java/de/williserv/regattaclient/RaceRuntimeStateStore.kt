package de.williserv.regattaclient

internal data class RaceRuntimeState(
    val accessKey: String,
    val resolvedEventName: String,
    val status: String,
    val startEpochMillis: Long?,
    val stopEpochMillis: Long?,
    val updatedAtMillis: Long
)

internal object RaceRuntimeStateStore {
    private val lock = Any()
    private var currentState: RaceRuntimeState? = null
    private val listeners = LinkedHashSet<(RaceRuntimeState?) -> Unit>()

    fun publish(
        server: String,
        event: String,
        secret: String,
        resolvedEventName: String,
        status: String,
        startEpochMillis: Long?,
        stopEpochMillis: Long?,
        updatedAtMillis: Long = System.currentTimeMillis()
    ) {
        val accessKey = accessKey(server, event, secret) ?: return
        val nextState = RaceRuntimeState(
            accessKey = accessKey,
            resolvedEventName = resolvedEventName.trim(),
            status = status.trim(),
            startEpochMillis = startEpochMillis,
            stopEpochMillis = stopEpochMillis,
            updatedAtMillis = updatedAtMillis
        )

        val listenersSnapshot = synchronized(lock) {
            currentState = nextState
            listeners.toList()
        }

        listenersSnapshot.forEach { listener ->
            listener(nextState)
        }
    }

    fun snapshotFor(
        server: String,
        event: String,
        secret: String
    ): RaceRuntimeState? = snapshotForAccessKey(accessKey(server, event, secret))

    fun snapshotForAccessKey(accessKey: String?): RaceRuntimeState? {
        if (accessKey == null) return null
        return synchronized(lock) {
            currentState?.takeIf { it.accessKey == accessKey }
        }
    }

    fun clearFor(
        server: String,
        event: String,
        secret: String
    ) {
        val accessKey = accessKey(server, event, secret) ?: return
        val listenersSnapshot = synchronized(lock) {
            if (currentState?.accessKey != accessKey) return
            currentState = null
            listeners.toList()
        }

        listenersSnapshot.forEach { listener ->
            listener(null)
        }
    }

    fun addListener(listener: (RaceRuntimeState?) -> Unit) {
        val snapshot = synchronized(lock) {
            listeners.add(listener)
            currentState
        }
        listener(snapshot)
    }

    fun removeListener(listener: (RaceRuntimeState?) -> Unit) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    internal fun accessKey(
        server: String,
        event: String,
        secret: String
    ): String? {
        val normalizedServer = server.trim().trimEnd('/')
        val normalizedEvent = event.trim()
        val normalizedSecret = secret.trim()

        if (
            normalizedServer.isBlank() ||
            normalizedEvent.isBlank() ||
            normalizedSecret.isBlank()
        ) {
            return null
        }

        return listOf(
            normalizedServer,
            normalizedEvent,
            normalizedSecret
        ).joinToString("\n")
    }

    internal fun resetForTests() {
        synchronized(lock) {
            currentState = null
            listeners.clear()
        }
    }
}
