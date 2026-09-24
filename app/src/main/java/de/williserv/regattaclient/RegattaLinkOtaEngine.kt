package de.williserv.regattaclient

import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min

internal enum class RegattaLinkOtaDataTransport {
    WRITE_WITHOUT_RESPONSE,
    WRITE_WITH_RESPONSE
}

internal enum class RegattaLinkOtaSubmitResult {
    ACCEPTED,
    LOCAL_QUEUE_BUSY,
    REJECTED
}

internal class RegattaLinkOtaTransportException(
    message: String,
    val ambiguous: Boolean = true,
    cause: Throwable? = null
) : Exception(message, cause)

internal interface RegattaLinkOtaTransport {
    val mtu: Int
    fun isConnected(): Boolean
    fun tuneConnection(info: RegattaLinkDeviceInfo)
    fun enableStatusNotifications()
    fun snapshot(): RegattaLinkOtaStatus
    fun writeControl(value: ByteArray)
    fun canWriteDataWithoutResponse(valueSize: Int): Boolean
    fun canWriteDataWithResponse(valueSize: Int): Boolean
    fun submitDataWithoutResponse(value: ByteArray): RegattaLinkOtaSubmitResult
    fun writeDataWithResponse(value: ByteArray)
    fun pollProgress(timeoutMs: Long): RegattaLinkOtaProgress?
    fun consumeDataTransportError(): String?
    fun closeCurrentConnection()
    fun reconnectCandidate(expectedStableId: String, timeoutMs: Long): RegattaLinkDeviceInfo?
}

internal class RegattaLinkOtaCancelledException : Exception()

internal class RegattaLinkOtaEngine(
    private val artifact: RegattaLinkFirmwareArtifact,
    private val initialDeviceInfo: RegattaLinkDeviceInfo,
    private val transport: RegattaLinkOtaTransport,
    private val cancelled: () -> Boolean,
    private val emit: (RegattaLinkOtaUiState) -> Unit,
    private val onTerminalDisconnect: () -> Unit = {}
) {
    companion object {
        private const val OPERATION_TIMEOUT_MS = 65_000L
        private const val AMBIGUOUS_STATUS_TIMEOUT_MS = 5_000L
        private const val DATA_NOTIFICATION_TIMEOUT_MS = 1_000L
        private const val PRE_TRANSFER_RECONNECT_ATTEMPTS = 3
        private const val PRE_TRANSFER_RECONNECT_SLICE_MS = 10_000L
        private const val RECONNECT_TIMEOUT_MS = 60_000L
        private const val POST_BOOT_CANDIDATE_TIMEOUT_MS = 10_000L
        private const val POST_BOOT_VALIDATION_SLICE_MS = 5_000L
    }

    private var requestId: UInt = 0u
    private var session: UInt = 0u
    private var canAbort = true
    private var lastUiState = RegattaLinkOtaUiState()

    fun run() {
        try {
            validateRegattaLinkOtaDevice(initialDeviceInfo, artifact)
            emitState(
                phase = RegattaLinkOtaPhase.PREPARING,
                detail = "Refreshing BLE connection for firmware transfer"
            )
            checkCancelled()

            val transferDeviceInfo = prepareFreshTransferConnection()
            checkCancelled()
            emitState(
                phase = RegattaLinkOtaPhase.PREPARING,
                detail = "Preparing secured BLE OTA"
            )
            transport.tuneConnection(transferDeviceInfo)
            transport.enableStatusNotifications()
            var status = transport.snapshot()
            if (status.state !in setOf(
                    RegattaLinkOtaDeviceState.IDLE,
                    RegattaLinkOtaDeviceState.ERROR
                )
            ) {
                throw IllegalStateException(
                    "RegattaLink is not idle before START: " + status.state
                )
            }

            val requiredDataValueSize = status.maxDataPayload + 8
            if (status.maxDataPayload <= 0) {
                throw IllegalStateException("RegattaLink reported invalid DATA payload capacity")
            }

            var dataTransport = when {
                transferDeviceInfo.otaDataWriteWithoutResponse &&
                    transport.canWriteDataWithoutResponse(requiredDataValueSize) ->
                    RegattaLinkOtaDataTransport.WRITE_WITHOUT_RESPONSE

                transferDeviceInfo.otaDataWriteWithResponse &&
                    transport.canWriteDataWithResponse(requiredDataValueSize) ->
                    RegattaLinkOtaDataTransport.WRITE_WITH_RESPONSE

                else -> throw IllegalStateException(
                    "No usable RegattaLink OTA DATA transport for " +
                        requiredDataValueSize + "-byte GATT values"
                )
            }

            requestId = newRegattaLinkOtaRequestId()
            emitState(
                phase = RegattaLinkOtaPhase.STARTING,
                detail = "Starting OTA transaction",
                transport = dataTransport.label()
            )
            status = startTransaction(status)

            emitState(
                phase = RegattaLinkOtaPhase.TRANSFERRING,
                committedBytes = status.acceptedOffset.toSafeInt(),
                totalBytes = artifact.image.size,
                detail = "Transferring firmware",
                transport = dataTransport.label()
            )
            val transferResult = transfer(
                status,
                dataTransport,
                transferDeviceInfo
            )
            status = transferResult.status
            dataTransport = transferResult.transport

            checkCancelled()
            canAbort = false
            emitState(
                phase = RegattaLinkOtaPhase.VERIFYING,
                committedBytes = artifact.image.size,
                totalBytes = artifact.image.size,
                detail = "Verifying firmware on RegattaLink",
                transport = dataTransport.label()
            )
            finish(status)

            emitState(
                phase = RegattaLinkOtaPhase.REBOOTING,
                committedBytes = artifact.image.size,
                totalBytes = artifact.image.size,
                detail = "Firmware selected; waiting for reboot",
                transport = dataTransport.label()
            )

            transport.closeCurrentConnection()
            reconcilePostBoot()

            emitState(
                phase = RegattaLinkOtaPhase.SUCCESS,
                committedBytes = artifact.image.size,
                totalBytes = artifact.image.size,
                detail = "Firmware installed and validated",
                transport = dataTransport.label()
            )
        } catch (_: RegattaLinkOtaCancelledException) {
            bestEffortAbort()
            transport.closeCurrentConnection()
            onTerminalDisconnect()
            emitState(
                phase = RegattaLinkOtaPhase.CANCELLED,
                detail = "Firmware update cancelled"
            )
        } catch (error: Exception) {
            bestEffortAbort()
            transport.closeCurrentConnection()
            onTerminalDisconnect()
            emitState(
                phase = RegattaLinkOtaPhase.ERROR,
                error = error.message ?: "RegattaLink OTA failed",
                detail = "Firmware update failed"
            )
        }
    }

    private fun startTransaction(initialStatus: RegattaLinkOtaStatus): RegattaLinkOtaStatus {
        checkCancelled()
        val metadata = encodeRegattaLinkOtaMetadata(artifact)
        writeControlReconciled(
            encodeRegattaLinkOtaStartBegin(requestId),
            "START_BEGIN"
        ) { status ->
            status.requestId == requestId &&
                status.assembling &&
                status.assembledBytes == 0
        }

        var assembled = 0
        for (fragment in encodeRegattaLinkOtaStartFragments(requestId, metadata, transport.mtu)) {
            checkCancelled()
            assembled += fragment.size - 7
            val expectedAssembled = assembled
            writeControlReconciled(
                fragment,
                "START_FRAGMENT"
            ) { status ->
                status.requestId == requestId &&
                    status.assembling &&
                    status.assembledBytes == expectedAssembled
            }
        }

        val recovered = writeControlReconciled(
            encodeRegattaLinkOtaStartCommit(requestId),
            "START_COMMIT"
        ) { status ->
            status.requestId == requestId &&
                status.session != 0u &&
                status.state in setOf(
                    RegattaLinkOtaDeviceState.PREPARING,
                    RegattaLinkOtaDeviceState.RECEIVING
                )
        }

        val status = if (recovered?.state == RegattaLinkOtaDeviceState.RECEIVING) {
            recovered
        } else {
            waitForStatus("RECEIVING") {
                it.requestId == requestId &&
                    it.session != 0u &&
                    it.state == RegattaLinkOtaDeviceState.RECEIVING
            }
        }

        require(status.requestId == requestId) {
            "RegattaLink request " + status.requestId + " does not match OTA attempt " + requestId
        }
        require(status.totalSize.toSafeInt() == artifact.image.size) {
            "RegattaLink accepted unexpected firmware size " + status.totalSize
        }
        require(status.maxDataPayload > 0) {
            "RegattaLink reported invalid DATA payload capacity"
        }
        session = status.session
        return status
    }

    private fun prepareFreshTransferConnection(): RegattaLinkDeviceInfo {
        /*
         * reconnectCandidate() deliberately closes the current GATT connection
         * before scanning/reconnecting. OTA must not inherit a long-lived
         * persistent connection whose Android link parameters may have fallen
         * back to a low-power state.
         */
        repeat(PRE_TRANSFER_RECONNECT_ATTEMPTS) {
            checkCancelled()
            val freshInfo = transport.reconnectCandidate(
                expectedStableId = initialDeviceInfo.stableId,
                timeoutMs = PRE_TRANSFER_RECONNECT_SLICE_MS
            )
            checkCancelled()

            if (freshInfo != null) {
                require(freshInfo.stableId == initialDeviceInfo.stableId) {
                    "Fresh OTA connection returned a different RegattaLink identity"
                }
                require(freshInfo.runningBuild == initialDeviceInfo.runningBuild) {
                    "RegattaLink build changed before OTA start"
                }
                validateRegattaLinkOtaDevice(freshInfo, artifact)
                return freshInfo
            }
        }

        throw IllegalStateException(
            "Could not establish a fresh BLE connection for OTA"
        )
    }

    private data class TransferResult(
        val status: RegattaLinkOtaStatus,
        val transport: RegattaLinkOtaDataTransport
    )

    private enum class Adaptation {
        REDUCE_WINDOW,
        SWITCH_TO_RESPONSE
    }

    private fun transfer(
        initialStatus: RegattaLinkOtaStatus,
        initialTransport: RegattaLinkOtaDataTransport,
        transferDeviceInfo: RegattaLinkDeviceInfo
    ): TransferResult {
        val totalSize = artifact.image.size
        val maxWindow = transferDeviceInfo.maxInflightBlocks
        var activeTransport = initialTransport
        var status = initialStatus
        var nextOffset = status.acceptedOffset.toSafeInt()
        var window = if (activeTransport == RegattaLinkOtaDataTransport.WRITE_WITH_RESPONSE) {
            maxWindow
        } else {
            min(REGATTALINK_OTA_INITIAL_WINDOW, maxWindow)
        }
        var growthEnabled =
            activeTransport == RegattaLinkOtaDataTransport.WRITE_WITHOUT_RESPONSE &&
                window < maxWindow
        var rampComplete = !growthEnabled
        var pendingAdaptation: Adaptation? = null
        val inflight = ArrayDeque<Int>()

        val transferStartedMs = nowMs()
        var sampleStartedMs = transferStartedMs
        var sampleOffset = status.acceptedOffset.toSafeInt()
        var lastProgressMs = transferStartedMs

        fun resetSample() {
            sampleStartedMs = nowMs()
            sampleOffset = status.acceptedOffset.toSafeInt()
        }

        fun emitProgress(detail: String = "Transferring firmware") {
            val elapsedMs = max(1L, nowMs() - transferStartedMs)
            val committed = status.acceptedOffset.toSafeInt()
            val rate = committed.toDouble() / elapsedMs.toDouble() * 1000.0 / 1024.0
            emitState(
                phase = RegattaLinkOtaPhase.TRANSFERRING,
                committedBytes = committed,
                totalBytes = totalSize,
                throughputKibPerSec = rate,
                detail = detail,
                transport = activeTransport.label()
            )
        }

        fun noteCommittedProgress(previousOffset: Int) {
            val committed = status.acceptedOffset.toSafeInt()
            if (committed <= previousOffset) return
            lastProgressMs = nowMs()
            if (growthEnabled) {
                window = min(maxWindow, window + 1)
                if (window >= maxWindow) {
                    growthEnabled = false
                    rampComplete = true
                    resetSample()
                }
            }
            emitProgress()
        }

        fun maybeScheduleThroughputAdaptation() {
            if (pendingAdaptation != null || !rampComplete) return
            val elapsed = nowMs() - sampleStartedMs
            val committed = status.acceptedOffset.toSafeInt() - sampleOffset
            if (
                elapsed < REGATTALINK_OTA_THROUGHPUT_SAMPLE_MS ||
                committed < REGATTALINK_OTA_THROUGHPUT_SAMPLE_BYTES
            ) {
                return
            }

            val rateKib = committed.toDouble() / elapsed.toDouble() * 1000.0 / 1024.0
            if (rateKib >= REGATTALINK_OTA_SLOW_LINK_THROUGHPUT_KIB_S) {
                resetSample()
                return
            }

            if (rateKib >= REGATTALINK_OTA_MIN_THROUGHPUT_KIB_S) {
                emitProgress(
                    "BLE link is slower than the preferred rate; continuing at maximum stable speed"
                )
                resetSample()
                return
            }

            if (
                activeTransport == RegattaLinkOtaDataTransport.WRITE_WITHOUT_RESPONSE &&
                transferDeviceInfo.otaDataWriteWithResponse &&
                transport.canWriteDataWithResponse(status.maxDataPayload + 8)
            ) {
                pendingAdaptation = Adaptation.SWITCH_TO_RESPONSE
                return
            }

            if (rateKib < REGATTALINK_OTA_MIN_THROUGHPUT_KIB_S) {
                throw IllegalStateException(
                    "TRANSPORT_TOO_SLOW: committed DATA throughput " +
                        String.format("%.1f", rateKib) +
                        " KiB/s is below the " +
                        String.format("%.1f", REGATTALINK_OTA_MIN_THROUGHPUT_KIB_S) +
                        " KiB/s hard floor after all transport adaptations"
                )
            }

            resetSample()
        }

        fun applyAdaptation() {
            when (pendingAdaptation) {
                Adaptation.REDUCE_WINDOW -> {
                    window = max(2, min(window, maxWindow) / 2)
                    growthEnabled =
                        activeTransport == RegattaLinkOtaDataTransport.WRITE_WITHOUT_RESPONSE &&
                            window < maxWindow
                    rampComplete = !growthEnabled
                    emitProgress(
                        "Local BLE queue pressure; reducing sender window to " +
                            window + " and ramping up again"
                    )
                }

                Adaptation.SWITCH_TO_RESPONSE -> {
                    activeTransport = RegattaLinkOtaDataTransport.WRITE_WITH_RESPONSE
                    window = maxWindow
                    growthEnabled = false
                    rampComplete = true
                    emitProgress("Switching to write-with-response fallback")
                }

                null -> return
            }
            pendingAdaptation = null
            resetSample()
        }

        while (status.acceptedOffset.toSafeInt() < totalSize) {
            checkCancelled()

            transport.consumeDataTransportError()?.let { transportError ->
                status = reconcileAmbiguousPipeline(
                    current = status,
                    nextOffset = nextOffset,
                    inflight = inflight,
                    detail = transportError
                )
                while (inflight.isNotEmpty() && inflight.first() <= status.acceptedOffset.toSafeInt()) {
                    inflight.removeFirst()
                }
                if (status.acceptedOffset.toSafeInt() != nextOffset) {
                    throw IllegalStateException(
                        "Ambiguous DATA transport failure; committed=" +
                            status.acceptedOffset + ", admitted=" + nextOffset
                    )
                }
            }

            if (pendingAdaptation != null && inflight.isEmpty()) {
                applyAdaptation()
            }

            while (
                pendingAdaptation == null &&
                nextOffset < totalSize &&
                inflight.size < window
            ) {
                checkCancelled()
                val offset = nextOffset
                val count = min(status.maxDataPayload, totalSize - offset)
                val payload = artifact.image.copyOfRange(offset, offset + count)
                val value = encodeRegattaLinkOtaData(session, offset, payload)
                val expectedOffset = offset + count

                when (activeTransport) {
                    RegattaLinkOtaDataTransport.WRITE_WITHOUT_RESPONSE -> {
                        when (transport.submitDataWithoutResponse(value)) {
                            RegattaLinkOtaSubmitResult.ACCEPTED -> {
                                inflight.addLast(expectedOffset)
                                nextOffset = expectedOffset
                            }

                            RegattaLinkOtaSubmitResult.LOCAL_QUEUE_BUSY -> {
                                pendingAdaptation =
                                    if (window > 2) {
                                        Adaptation.REDUCE_WINDOW
                                    } else if (
                                        transferDeviceInfo.otaDataWriteWithResponse &&
                                        transport.canWriteDataWithResponse(value.size)
                                    ) {
                                        Adaptation.SWITCH_TO_RESPONSE
                                    } else {
                                        throw IllegalStateException(
                                            "Local GATT queue rejected OTA DATA and no fallback is available"
                                        )
                                    }
                                break
                            }

                            RegattaLinkOtaSubmitResult.REJECTED -> {
                                throw IllegalStateException(
                                    "Android rejected OTA DATA before transmission"
                                )
                            }
                        }
                    }

                    RegattaLinkOtaDataTransport.WRITE_WITH_RESPONSE -> {
                        try {
                            transport.writeDataWithResponse(value)
                            inflight.addLast(expectedOffset)
                            nextOffset = expectedOffset
                        } catch (error: RegattaLinkOtaTransportException) {
                            if (!error.ambiguous) throw error
                            val boundaries = inflight.toMutableList().apply {
                                add(expectedOffset)
                            }
                            status = recoverAmbiguousDataWrite(
                                status,
                                expectedOffset,
                                boundaries,
                                error
                            )
                            nextOffset = expectedOffset
                            inflight.addLast(expectedOffset)
                            while (
                                inflight.isNotEmpty() &&
                                inflight.first() <= status.acceptedOffset.toSafeInt()
                            ) {
                                inflight.removeFirst()
                            }
                        }
                    }
                }

                var notification = transport.pollProgress(0)
                while (notification != null) {
                    val previous = status.acceptedOffset.toSafeInt()
                    status = applyProgress(
                        status,
                        notification,
                        inflight,
                        nextOffset,
                        totalSize
                    )
                    while (
                        inflight.isNotEmpty() &&
                        inflight.first() <= status.acceptedOffset.toSafeInt()
                    ) {
                        inflight.removeFirst()
                    }
                    noteCommittedProgress(previous)
                    maybeScheduleThroughputAdaptation()
                    notification = transport.pollProgress(0)
                }
            }

            if (status.acceptedOffset.toSafeInt() >= totalSize) break

            if (inflight.isEmpty()) {
                if (pendingAdaptation != null) continue
                if (nextOffset == status.acceptedOffset.toSafeInt()) continue
                throw IllegalStateException(
                    "OTA has admitted bytes without an in-flight boundary"
                )
            }

            val previous = status.acceptedOffset.toSafeInt()
            val progress = transport.pollProgress(DATA_NOTIFICATION_TIMEOUT_MS)
            status = if (progress != null) {
                applyProgress(status, progress, inflight, nextOffset, totalSize)
            } else {
                validateSnapshotDuringTransfer(
                    transport.snapshot(),
                    status,
                    inflight,
                    nextOffset,
                    totalSize
                )
            }

            while (
                inflight.isNotEmpty() &&
                inflight.first() <= status.acceptedOffset.toSafeInt()
            ) {
                inflight.removeFirst()
            }
            noteCommittedProgress(previous)
            maybeScheduleThroughputAdaptation()

            if (nowMs() - lastProgressMs > OPERATION_TIMEOUT_MS) {
                throw IllegalStateException("Timed out waiting for committed OTA DATA progress")
            }
        }

        require(nextOffset == totalSize) {
            "OTA admission ended at $nextOffset instead of $totalSize"
        }
        require(status.acceptedOffset.toSafeInt() == totalSize) {
            "OTA committed " + status.acceptedOffset + " of " + totalSize + " bytes"
        }
        require(inflight.isEmpty()) {
            "OTA finished with " + inflight.size + " uncommitted blocks"
        }

        emitProgress("Firmware transfer complete")
        return TransferResult(status, activeTransport)
    }

    private fun applyProgress(
        current: RegattaLinkOtaStatus,
        progress: RegattaLinkOtaProgress,
        inflight: ArrayDeque<Int>,
        nextOffset: Int,
        totalSize: Int
    ): RegattaLinkOtaStatus {
        if (progress.session != session) return current
        if (!isRegattaLinkOtaRevisionNewer(progress.revision, current.revision)) {
            return current
        }
        if (progress.totalSize.toSafeInt() != totalSize) {
            throw IllegalStateException(
                "Unexpected OTA total size " + progress.totalSize
            )
        }
        if (progress.state == RegattaLinkOtaDeviceState.ERROR || progress.error != 0) {
            throw IllegalStateException(
                "RegattaLink OTA error: " + regattaLinkOtaErrorName(progress.error)
            )
        }
        if (
            progress.state != RegattaLinkOtaDeviceState.RECEIVING &&
            progress.acceptedOffset.toSafeInt() < totalSize
        ) {
            throw IllegalStateException(
                "Unexpected OTA state during transfer: " + progress.state
            )
        }

        val previous = current.acceptedOffset.toSafeInt()
        val candidate = progress.acceptedOffset.toSafeInt()
        validateRegattaLinkCommittedOffset(previous, candidate, nextOffset, inflight)

        return current.copy(
            revision = progress.revision,
            acceptedOffset = progress.acceptedOffset,
            totalSize = progress.totalSize,
            state = progress.state,
            error = progress.error,
            maxDataPayload = progress.maxDataPayload
        )
    }

    private fun validateSnapshotDuringTransfer(
        snapshot: RegattaLinkOtaStatus,
        current: RegattaLinkOtaStatus,
        inflight: ArrayDeque<Int>,
        nextOffset: Int,
        totalSize: Int
    ): RegattaLinkOtaStatus {
        if (snapshot.session != session) {
            throw IllegalStateException(
                "Unexpected OTA session " + snapshot.session + " while transferring"
            )
        }
        if (snapshot.state == RegattaLinkOtaDeviceState.ERROR || snapshot.error != 0) {
            throw IllegalStateException(
                "RegattaLink OTA error: " + regattaLinkOtaErrorName(snapshot.error)
            )
        }
        if (
            snapshot.state != RegattaLinkOtaDeviceState.RECEIVING &&
            snapshot.acceptedOffset.toSafeInt() < totalSize
        ) {
            throw IllegalStateException(
                "Unexpected OTA state during transfer: " + snapshot.state
            )
        }
        if (snapshot.totalSize.toSafeInt() != totalSize) {
            throw IllegalStateException(
                "Unexpected OTA total size " + snapshot.totalSize
            )
        }

        validateRegattaLinkCommittedOffset(
            current.acceptedOffset.toSafeInt(),
            snapshot.acceptedOffset.toSafeInt(),
            nextOffset,
            inflight
        )
        return snapshot
    }

    private fun recoverAmbiguousDataWrite(
        current: RegattaLinkOtaStatus,
        expectedOffset: Int,
        admittedBoundaries: Collection<Int>,
        cause: Exception
    ): RegattaLinkOtaStatus {
        val deadline = nowMs() + AMBIGUOUS_STATUS_TIMEOUT_MS
        var last = current
        while (nowMs() < deadline) {
            if (!transport.isConnected()) {
                throw IllegalStateException(
                    "Ambiguous OTA DATA write disconnected before reconciliation",
                    cause
                )
            }
            val snapshot = transport.snapshot()
            if (snapshot.session != session) {
                throw IllegalStateException("OTA session changed during DATA reconciliation")
            }
            if (snapshot.state == RegattaLinkOtaDeviceState.ERROR || snapshot.error != 0) {
                throw IllegalStateException(
                    "RegattaLink OTA error after ambiguous DATA write: " +
                        regattaLinkOtaErrorName(snapshot.error),
                    cause
                )
            }
            validateRegattaLinkCommittedOffset(
                last.acceptedOffset.toSafeInt(),
                snapshot.acceptedOffset.toSafeInt(),
                expectedOffset,
                admittedBoundaries
            )
            last = snapshot
            if (
                snapshot.state == RegattaLinkOtaDeviceState.RECEIVING &&
                snapshot.acceptedOffset.toSafeInt() == expectedOffset
            ) {
                return snapshot
            }
            Thread.sleep(100)
        }
        throw IllegalStateException(
            "Ambiguous OTA DATA write could not be proven committed; abort/restart required",
            cause
        )
    }

    private fun reconcileAmbiguousPipeline(
        current: RegattaLinkOtaStatus,
        nextOffset: Int,
        inflight: ArrayDeque<Int>,
        detail: String
    ): RegattaLinkOtaStatus {
        val deadline = nowMs() + AMBIGUOUS_STATUS_TIMEOUT_MS
        var last = current
        while (nowMs() < deadline) {
            if (!transport.isConnected()) {
                throw IllegalStateException(
                    "Ambiguous OTA DATA transport failure disconnected: $detail"
                )
            }
            val snapshot = validateSnapshotDuringTransfer(
                transport.snapshot(),
                last,
                inflight,
                nextOffset,
                artifact.image.size
            )
            last = snapshot
            if (snapshot.acceptedOffset.toSafeInt() == nextOffset) {
                return snapshot
            }
            Thread.sleep(100)
        }
        throw IllegalStateException(
            "Ambiguous OTA DATA transport failure; abort/restart required: $detail"
        )
    }

    private fun finish(status: RegattaLinkOtaStatus) {
        require(status.acceptedOffset.toSafeInt() == artifact.image.size)
        val recovered = writeControlReconciled(
            encodeRegattaLinkOtaFinish(session),
            "FINISH",
            allowDisconnect = true
        ) { snapshot ->
            snapshot.session == session &&
                snapshot.state in setOf(
                    RegattaLinkOtaDeviceState.VERIFYING,
                    RegattaLinkOtaDeviceState.READY_TO_REBOOT
                )
        }

        if (recovered?.state == RegattaLinkOtaDeviceState.READY_TO_REBOOT) return

        val deadline = nowMs() + OPERATION_TIMEOUT_MS
        while (nowMs() < deadline) {
            if (!transport.isConnected()) return
            val snapshot = try {
                transport.snapshot()
            } catch (error: Exception) {
                if (!transport.isConnected()) return
                throw error
            }

            if (snapshot.state == RegattaLinkOtaDeviceState.ERROR || snapshot.error != 0) {
                throw IllegalStateException(
                    "RegattaLink verification failed: " +
                        regattaLinkOtaErrorName(snapshot.error)
                )
            }
            if (
                snapshot.session == session &&
                snapshot.state == RegattaLinkOtaDeviceState.READY_TO_REBOOT
            ) {
                return
            }
            if (
                snapshot.session != session ||
                snapshot.state !in setOf(
                    RegattaLinkOtaDeviceState.VERIFYING,
                    RegattaLinkOtaDeviceState.READY_TO_REBOOT
                )
            ) {
                throw IllegalStateException(
                    "Unexpected OTA state while finishing: " + snapshot.state
                )
            }
            Thread.sleep(100)
        }
        throw IllegalStateException("Timed out waiting for READY_TO_REBOOT")
    }

    private fun reconcilePostBoot() {
        val targetBuild = artifact.manifest.buildNumber
        val expectedStableId = initialDeviceInfo.stableId
        val deadline = nowMs() + RECONNECT_TIMEOUT_MS
        var lastObservation = "updated RegattaLink not yet rediscovered"

        while (nowMs() < deadline) {
            emitState(
                phase = RegattaLinkOtaPhase.RECONNECTING,
                committedBytes = artifact.image.size,
                totalBytes = artifact.image.size,
                detail = "Reconnecting to updated RegattaLink"
            )

            val remaining = deadline - nowMs()
            val info = transport.reconnectCandidate(
                expectedStableId,
                min(POST_BOOT_CANDIDATE_TIMEOUT_MS, remaining)
            )
            if (info == null) {
                lastObservation = "no matching RegattaLink completed reconnect"
                continue
            }

            if (info.stableId != expectedStableId) {
                lastObservation =
                    "unexpected stable ID ${info.stableId}"
                transport.closeCurrentConnection()
                continue
            }
            if (info.runningBuild != targetBuild) {
                lastObservation =
                    "Device Info still reports build ${info.runningBuild}"
                transport.closeCurrentConnection()
                Thread.sleep(100)
                continue
            }

            emitState(
                phase = RegattaLinkOtaPhase.VALIDATING,
                committedBytes = artifact.image.size,
                totalBytes = artifact.image.size,
                detail = "Confirming first-boot validation"
            )

            try {
                transport.tuneConnection(info)
                transport.enableStatusNotifications()
                val validationDeadline =
                    min(deadline, nowMs() + POST_BOOT_VALIDATION_SLICE_MS)

                while (nowMs() < validationDeadline && transport.isConnected()) {
                    val snapshot = transport.snapshot()
                    lastObservation =
                        "status build=${snapshot.runningBuild}, boot=${snapshot.bootResult}"
                    if (snapshot.bootResult == RegattaLinkOtaBootResult.ROLLBACK) {
                        throw IllegalStateException(
                            "RegattaLink rolled back the firmware update"
                        )
                    }
                    if (isRegattaLinkPostBootValidated(snapshot, targetBuild)) {
                        return
                    }
                    Thread.sleep(250)
                }
            } catch (error: IllegalStateException) {
                if (error.message?.contains("rolled back", ignoreCase = true) == true) {
                    throw error
                }
                lastObservation =
                    error.message ?: "post-boot validation failed"
            } catch (error: Exception) {
                lastObservation =
                    error.message ?: error.javaClass.simpleName
            }

            transport.closeCurrentConnection()
            Thread.sleep(100)
        }

        throw IllegalStateException(
            "RegattaLink did not return with a VALIDATED target build; last observation: " +
                lastObservation
        )
    }

    private fun waitForStatus(
        description: String,
        predicate: (RegattaLinkOtaStatus) -> Boolean
    ): RegattaLinkOtaStatus {
        val deadline = nowMs() + OPERATION_TIMEOUT_MS
        while (nowMs() < deadline) {
            checkCancelled()
            val status = transport.snapshot()
            if (status.state == RegattaLinkOtaDeviceState.ERROR) {
                throw IllegalStateException(
                    "RegattaLink OTA error: " + regattaLinkOtaErrorName(status.error)
                )
            }
            if (predicate(status)) return status
            transport.pollProgress(500)
        }
        throw IllegalStateException("Timed out waiting for $description")
    }

    private fun writeControlReconciled(
        value: ByteArray,
        description: String,
        allowDisconnect: Boolean = false,
        predicate: (RegattaLinkOtaStatus) -> Boolean
    ): RegattaLinkOtaStatus? {
        try {
            transport.writeControl(value)
            return null
        } catch (error: Exception) {
            val deadline = nowMs() + AMBIGUOUS_STATUS_TIMEOUT_MS
            var lastStatus: RegattaLinkOtaStatus? = null
            while (nowMs() < deadline) {
                if (!transport.isConnected()) {
                    if (allowDisconnect) return null
                    throw IllegalStateException(
                        "$description result is ambiguous because BLE disconnected",
                        error
                    )
                }
                try {
                    val status = transport.snapshot()
                    lastStatus = status
                    if (predicate(status)) return status
                    if (status.state == RegattaLinkOtaDeviceState.ERROR) {
                        throw IllegalStateException(
                            "RegattaLink OTA error after $description: " +
                                regattaLinkOtaErrorName(status.error),
                            error
                        )
                    }
                } catch (snapshotError: Exception) {
                    if (!transport.isConnected() && allowDisconnect) return null
                    if (!transport.isConnected()) throw snapshotError
                }
                Thread.sleep(100)
            }
            throw IllegalStateException(
                "$description result remained ambiguous; last status=" +
                    (lastStatus?.state ?: "unavailable"),
                error
            )
        }
    }

    private fun bestEffortAbort() {
        if (!canAbort || requestId == 0u || !transport.isConnected()) return
        runCatching {
            transport.writeControl(encodeRegattaLinkOtaAbort(requestId, session))
        }
    }

    private fun checkCancelled() {
        if (cancelled()) throw RegattaLinkOtaCancelledException()
    }

    private fun emitState(
        phase: RegattaLinkOtaPhase,
        committedBytes: Int = lastUiState.committedBytes,
        totalBytes: Int = if (lastUiState.totalBytes > 0) {
            lastUiState.totalBytes
        } else {
            artifact.image.size
        },
        throughputKibPerSec: Double? = lastUiState.throughputKibPerSec,
        detail: String = lastUiState.detail,
        transport: String = lastUiState.transport,
        error: String = ""
    ) {
        lastUiState = RegattaLinkOtaUiState(
            phase = phase,
            installedBuild = initialDeviceInfo.runningBuild.toString(),
            targetBuild = artifact.manifest.buildNumber.toString(),
            committedBytes = committedBytes,
            totalBytes = totalBytes,
            throughputKibPerSec = throughputKibPerSec,
            transport = transport,
            detail = detail,
            error = error
        )
        emit(lastUiState)
    }

    private fun UInt.toSafeInt(): Int {
        require(toLong() <= Int.MAX_VALUE.toLong()) {
            "OTA offset exceeds Android client range: $this"
        }
        return toInt()
    }

    private fun RegattaLinkOtaDataTransport.label(): String = when (this) {
        RegattaLinkOtaDataTransport.WRITE_WITHOUT_RESPONSE -> "write without response"
        RegattaLinkOtaDataTransport.WRITE_WITH_RESPONSE -> "write with response"
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L
}
