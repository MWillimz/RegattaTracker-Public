package de.williserv.regattaclient

import android.content.Context

internal data class RaceEventDisplaySnapshotSelection(
    val snapshot: RaceEventSnapshot,
    val useIncomingStartFlags: Boolean
)

internal fun resolveRaceEventDisplaySnapshot(
    context: Context,
    access: EventAccessKey,
    incomingSnapshot: RaceEventSnapshot,
    incomingPersisted: Boolean
): RaceEventDisplaySnapshotSelection? {
    if (incomingPersisted) {
        return RaceEventDisplaySnapshotSelection(
            snapshot = incomingSnapshot,
            useIncomingStartFlags = true
        )
    }

    val persistedWinner = RaceEventSnapshotStore.loadMatching(
        context = context,
        server = access.server,
        event = access.event,
        secret = access.secret
    ) ?: return null

    return RaceEventDisplaySnapshotSelection(
        snapshot = persistedWinner,
        useIncomingStartFlags = false
    )
}
