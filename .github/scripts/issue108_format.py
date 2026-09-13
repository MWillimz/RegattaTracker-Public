from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one match, found {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


home = "app/src/main/java/de/williserv/regattaclient/HomeScreen.kt"
main = "app/src/main/java/de/williserv/regattaclient/MainActivity.kt"
worker = "app/src/main/java/de/williserv/regattaclient/TelemetryUploadWorker.kt"

replace_once(
    home,
    """    val uploadColor = uploadStatusColor(
    pendingUploadCount = pendingUploadCount,
    noConnection = noConnection
)
""",
    """    val uploadColor = uploadStatusColor(
        pendingUploadCount = pendingUploadCount,
        noConnection = noConnection
    )
""",
)
replace_once(
    home,
    """            uploadStatusText = shortUploadStatus(
    pendingUploadCount = pendingUploadCount,
    inRace = inRace,
    noConnection = noConnection,
    pendingText = { pending -> resources.getString(R.string.pending_value, pending) },
    okText = stringResource(R.string.ok),
    noConnectionText = stringResource(R.string.status_no_connection)
),
""",
    """            uploadStatusText = shortUploadStatus(
                pendingUploadCount = pendingUploadCount,
                inRace = inRace,
                noConnection = noConnection,
                pendingText = { pending -> resources.getString(R.string.pending_value, pending) },
                okText = stringResource(R.string.ok),
                noConnectionText = stringResource(R.string.status_no_connection)
            ),
""",
)

replace_once(
    main,
    """            } catch (e: Exception) {
    if (!serverResponded) {
        ServerConnectionStateStore.markNoConnection(this, raceServer.value)
    }
    runOnUiThread {
        if (currentBoatSetupValues() != registrationBoatSetup) {
            return@runOnUiThread
        }
        updateConnectionUiState()
        registerRaceStatusText.value = if (serverResponded) {
            getString(R.string.registration_failed, e.message ?: "")
        } else {
            getString(R.string.status_no_connection)
        }
    }
}
""",
    """            } catch (e: Exception) {
                if (!serverResponded) {
                    ServerConnectionStateStore.markNoConnection(this, raceServer.value)
                }
                runOnUiThread {
                    if (currentBoatSetupValues() != registrationBoatSetup) {
                        return@runOnUiThread
                    }
                    updateConnectionUiState()
                    registerRaceStatusText.value = if (serverResponded) {
                        getString(R.string.registration_failed, e.message ?: "")
                    } else {
                        getString(R.string.status_no_connection)
                    }
                }
            }
""",
)
replace_once(
    main,
    """    private fun updateConnectionUiState() {
    val server = raceServer.value
    if (server.isBlank()) {
        serverNoConnection.value = false
        return
    }

    val connectionState = ServerConnectionStateStore.state(this, server)
    if (
        !ServerConnectionStateStore.hasActiveNetwork(this) &&
        connectionState != ServerConnectionState.REACHABLE
    ) {
        ServerConnectionStateStore.markNoConnection(this, server)
    }

    serverNoConnection.value =
        ServerConnectionStateStore.state(this, server) == ServerConnectionState.NO_CONNECTION
}
""",
    """    private fun updateConnectionUiState() {
        val server = raceServer.value
        if (server.isBlank()) {
            serverNoConnection.value = false
            return
        }

        val connectionState = ServerConnectionStateStore.state(this, server)
        if (
            !ServerConnectionStateStore.hasActiveNetwork(this) &&
            connectionState != ServerConnectionState.REACHABLE
        ) {
            ServerConnectionStateStore.markNoConnection(this, server)
        }

        serverNoConnection.value =
            ServerConnectionStateStore.state(this, server) == ServerConnectionState.NO_CONNECTION
    }
""",
)

replace_once(
    worker,
    """        } catch (e: Exception) {
    if (!serverResponded) {
        ServerConnectionStateStore.markNoConnection(applicationContext, accessContext.serverUrl)
    }
    publishDebugError(applicationContext.getString(R.string.upload_exception, e.message ?: ""))
    TelemetryUploadAttemptResult.TEMPORARY_FAILURE
}
""",
    """        } catch (e: Exception) {
            if (!serverResponded) {
                ServerConnectionStateStore.markNoConnection(applicationContext, accessContext.serverUrl)
            }
            publishDebugError(applicationContext.getString(R.string.upload_exception, e.message ?: ""))
            TelemetryUploadAttemptResult.TEMPORARY_FAILURE
        }
""",
)

print("#108 touched-block formatting normalized")
