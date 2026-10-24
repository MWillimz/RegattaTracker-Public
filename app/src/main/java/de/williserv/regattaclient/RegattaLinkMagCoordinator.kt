package de.williserv.regattaclient

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal enum class MagChannel { EXPORT_CONTROL, EXPORT_PAGE, IMPORT_CONTROL, IMPORT_DATA, IMPORT_STATUS, AUTO, DEVICE_CONTROL, TRIM }
internal interface RegattaLinkMagIo {
    val stableId: String
    fun read(channel: MagChannel): ByteArray
    fun write(channel: MagChannel, bytes: ByteArray)
    fun subscribe(enabled: Boolean)
    fun reset(requestId: UInt)
    fun resetConfirmed(requestId: UInt): Boolean
    fun readTrim(): Int
    fun writeTrim(value: Int)
}

// One owner spans each operation, including every step of reset/restore. All wire work
// runs on the existing BLE executor; capture waiting and numerical fits never occupy it.
internal class RegattaLinkMagCoordinator(
    private val wire: Executor,
    private val math: Executor,
    private val timer: ScheduledExecutorService,
    private val store: MagJournalStore,
    private val emit: (RegattaLinkMagState) -> Unit,
    private val now: () -> Long = { System.nanoTime()/1_000_000 },
    private val pause: (Long) -> Unit = { Thread.sleep(it) }
) {
    private data class Session(val generation: Long, val io: RegattaLinkMagIo)
    private data class Lease(val session: Session, val operation: Long)
    private var generation = 0L
    private var operation = 0L
    private var session: Session? = null
    private var owner: Lease? = null
    private var state = RegattaLinkMagState()
    private var capture: RegattaLinkMagCapture? = null
    private var captureLease: Lease? = null
    private var captureStarted = 0L
    private var capturePublished = 0L
    private var capturePoints: List<DoubleArray>? = null
    private var captureMetadata: RegattaLinkMagAutoMetadata? = null
    private var stoppingCapture = false
    private var captureError: String? = null
    private var captureTimeout: ScheduledFuture<*>? = null

    @Synchronized fun busy() = owner != null || state.busy
    @Synchronized private fun current(t: Lease) = session === t.session && owner === t
    @Synchronized private fun publish(t: Lease, change: (RegattaLinkMagState) -> RegattaLinkMagState) {
        if (current(t)) { state = change(state); emit(state) }
    }
    @Synchronized private fun acquire(recovery: Boolean = false): Lease? {
        val s = session ?: return null
        if (owner != null || (!recovery && (state.recoveryAvailable || state.phase == RegattaLinkMagPhase.UNKNOWN))) return null
        return Lease(s,++operation).also { owner = it; state = state.copy(operationRunning = true); emit(state) }
    }
    @Synchronized private fun release(t: Lease) {
        if (owner === t) { owner = null; state = state.copy(operationRunning = false); emit(state) }
    }
    private fun checkCurrent(t: Lease) { check(current(t)) { "MAG connection changed" } }
    private fun read(t: Lease, c: MagChannel): ByteArray { checkCurrent(t); return t.session.io.read(c).also { checkCurrent(t) } }
    private fun write(t: Lease, c: MagChannel, bytes: ByteArray) { checkCurrent(t); t.session.io.write(c,bytes); checkCurrent(t) }
    private fun load(t: Lease) = store.load(t.session.io.stableId)
    private fun save(t: Lease, journal: MagJournal) { checkCurrent(t); store.save(t.session.io.stableId,journal) }
    private fun request(): UInt = SecureRandom().nextInt().toUInt().let { if (it == 0u) 1u else it }

    @Synchronized fun bind(io: RegattaLinkMagIo?, supported: Boolean) {
        captureTimeout?.cancel(false); captureTimeout = null
        generation++
        session = if (io != null && supported) Session(generation,io) else null
        owner = null; capture = null; captureLease = null; capturePoints = null; captureMetadata = null
        stoppingCapture = false; captureError = null
        state = RegattaLinkMagState(supported = supported)
        emit(state)
        if (session != null) refresh()
    }
    @Synchronized fun disconnect() {
        captureTimeout?.cancel(false); captureTimeout = null
        generation++; session = null; owner = null; capture = null; captureLease = null
        capturePoints = null; captureMetadata = null; stoppingCapture = false
        state = RegattaLinkMagState(); emit(state)
        // Pending commits and reset backups stay on disk. Late worker results cannot update another session.
    }

    private fun failed(t: Lease, stage: String, e: Exception, preserveResult: Boolean = false) {
        val recovery = runCatching { load(t).let { it.pending != null || it.backup != null } }.getOrDefault(true)
        publish(t) { previous ->
            val phase = when {
                recovery -> RegattaLinkMagPhase.UNKNOWN
                preserveResult && previous.phase in setOf(
                    RegattaLinkMagPhase.CAPTURE_READY, RegattaLinkMagPhase.PROPOSAL_2D,
                    RegattaLinkMagPhase.ARCHIVE_READY, RegattaLinkMagPhase.FIT_READY
                ) -> previous.phase
                else -> RegattaLinkMagPhase.ERROR
            }
            previous.copy(phase = phase, recoveryAvailable = recovery,
                errorStage = stage, error = e.message ?: "MAG operation failed")
        }
    }
    private fun launch(t: Lease, phase: RegattaLinkMagPhase, stage: String, executor: Executor = wire, block: () -> Unit) {
        publish(t) { it.copy(phase = phase, error = "", errorStage = "") }
        executor.execute {
            try { checkCurrent(t); block() }
            catch (e: Exception) { failed(t,stage,e) }
            finally { release(t) }
        }
    }
    private fun metadata(t: Lease): RegattaLinkMagAutoMetadata = readRegattaLinkMagAutoMetadata(
        { write(t,MagChannel.AUTO,it) }, { read(t,MagChannel.AUTO) })
    private fun status(t: Lease) = parseRegattaLinkMagStatus(read(t,MagChannel.IMPORT_STATUS))
    private fun requireBinding(t: Lease, expected: RegattaLinkMagBinding, m: RegattaLinkMagAutoMetadata) {
        m.requireReady()
        require(m.binding(t.session.io.stableId) == expected) { "MAG factory or upright reference changed" }
    }
    private fun showMetadata(t: Lease, m: RegattaLinkMagAutoMetadata, s: RegattaLinkMagStatus) {
        val binding = m.binding(t.session.io.stableId)
        publish(t) { previous -> previous.copy(metadata = m, status = s,
            autoFit = previous.autoFit?.takeIf { it.binding == binding },
            archive = previous.archive?.takeIf { it.manifest.factoryCrc == m.factoryCrc && it.uprightRef == m.uprightRef },
            fit = previous.fit?.takeIf { previous.archive?.manifest?.factoryCrc == m.factoryCrc && previous.archive?.uprightRef == m.uprightRef }) }
    }
    private fun reconcile(t: Lease, m: RegattaLinkMagAutoMetadata, s: RegattaLinkMagStatus): MagJournal {
        var journal = load(t)
        journal.pending?.let { pending ->
            when (magReceipt(pending,m,s,t.session.io.stableId)) {
                MagReceipt.STORED -> {
                    journal = journal.copy(pending = null, backup = journal.backup?.copy(stage = MagRestoreStage.RESTORE_2D_CONFIRMED))
                    save(t,journal)
                    publish(t) { it.copy(phase = if (journal.backup == null) RegattaLinkMagPhase.STORED else RegattaLinkMagPhase.RESTORING,
                        progress = 100, recoveryAvailable = journal.backup != null, error = "", errorStage = "") }
                }
                MagReceipt.REJECTED -> {
                    journal = journal.copy(pending = null, backup = journal.backup?.copy(stage = MagRestoreStage.RESET_CONFIRMED))
                    save(t,journal)
                    publish(t) { it.copy(phase = RegattaLinkMagPhase.ERROR, recoveryAvailable = journal.backup != null,
                        errorStage = "COMMIT", error = "Device rejected MAG object (${if(pending.kind == MagObjectKind.AUTO_2D) m.errorCode else s.errorCode})") }
                }
                MagReceipt.UNKNOWN -> publish(t) { it.copy(phase = RegattaLinkMagPhase.UNKNOWN, recoveryAvailable = true,
                    errorStage = "COMMIT", error = "Import result is unverified; reconnect and check recovery") }
            }
        }
        if (journal.backup != null && journal.pending == null) publish(t) {
            it.copy(recoveryAvailable = true, phase = RegattaLinkMagPhase.RESTORING)
        }
        return journal
    }
    fun refresh(): Boolean {
        val t = acquire(recovery = true) ?: return false
        wire.execute {
            try {
                val m = metadata(t); val s = status(t)
                showMetadata(t,m,s)
                reconcile(t,m,s)
                publish(t) { if (it.errorStage == "STATUS") it.copy(error = "", errorStage = "") else it }
            } catch (e: Exception) { failed(t,"STATUS",e,preserveResult = true) }
            finally { release(t) }
        }
        return true
    }

    fun startCapture(): Boolean {
        synchronized(this) { if (state.metadata?.let { it.factoryValid && it.referenceValid } != true) return false }
        val t = acquire() ?: return false
        synchronized(this) {
            captureLease = t; capture = null; stoppingCapture = false; captureError = null
        }
        publish(t) { it.copy(phase = RegattaLinkMagPhase.CAPTURING, autoFit = null, fit = null,
            captureSamples = 0, progress = 0, error = "", errorStage = "") }
        wire.execute {
            var subscribed = false
            var recording = false
            try {
                checkCurrent(t)
                if (synchronized(this) { stoppingCapture }) return@execute
                subscribed = true // Even a lost CCCD ACK needs a best-effort disable.
                t.session.io.subscribe(true)
                val m = metadata(t); m.requireReady()
                synchronized(this) {
                    checkCurrent(t)
                    if (stoppingCapture) return@execute
                    capture = RegattaLinkMagCapture(m); captureMetadata = m
                    capturePoints = null; captureStarted = now(); capturePublished = 0
                    recording = true
                    captureTimeout = timer.schedule({ synchronized(this) {
                        if (captureLease === t) stopCapture()
                    } },RegattaLinkMagCapture.MAX_MS,TimeUnit.MILLISECONDS)
                }
                publish(t) { it.copy(metadata = m) }
            } catch (e: Exception) { failed(t,"CAPTURE",e) }
            finally {
                if (!recording) {
                    runCatching { if (subscribed && current(t)) t.session.io.subscribe(false) }
                        .onFailure { if (it is Exception) failed(t,"CCCD",it) }
                    publish(t) { if (it.phase == RegattaLinkMagPhase.CAPTURING) it.copy(phase = RegattaLinkMagPhase.IDLE) else it }
                    synchronized(this) { if (captureLease === t) { captureLease = null; stoppingCapture = false } }
                    release(t)
                }
            }
        }
        return true
    }
    fun live(bytes: ByteArray) {
        val t: Lease
        var stop = false
        synchronized(this) {
            t = captureLease ?: return
            if (!current(t) || stoppingCapture) return
            val c = capture ?: return
            try {
                if (!c.accept(bytes)) return
                if (now()-capturePublished >= 250) {
                    capturePublished = now()
                    publish(t) { it.copy(captureSamples = c.size(), progress = ((now()-captureStarted)*100/RegattaLinkMagCapture.MAX_MS).toInt().coerceIn(0,100)) }
                }
                stop = c.size() >= RegattaLinkMagCapture.MAX_SAMPLES || now()-captureStarted >= RegattaLinkMagCapture.MAX_MS
            } catch (e: Exception) { captureError = e.message; stop = true }
        }
        if (stop) stopCapture()
    }
    fun stopCapture(): Boolean {
        val t: Lease
        val c: RegattaLinkMagCapture
        synchronized(this) {
            t = captureLease ?: return false
            if (!current(t) || stoppingCapture) return false
            stoppingCapture = true
            captureTimeout?.cancel(false); captureTimeout = null
            c = capture ?: return true // A queued start observes cancellation before publishing or recording.
        }
        wire.execute {
            try {
                checkCurrent(t)
                captureError?.let { error(it) }
                val final = metadata(t)
                val points = c.finish(final)
                synchronized(this) { checkCurrent(t); capturePoints = points }
                publish(t) { it.copy(phase = RegattaLinkMagPhase.CAPTURE_READY, captureSamples = points.size, metadata = final) }
            } catch (e: Exception) { failed(t,"CAPTURE",e) }
            finally {
                try { if (current(t)) t.session.io.subscribe(false) }
                catch (e: Exception) { failed(t,"CCCD",e) }
                synchronized(this) { if (captureLease === t) { captureLease = null; capture = null; stoppingCapture = false } }
                release(t)
            }
        }
        return true
    }
    fun calculateAutoFit(): Boolean {
        val points: List<DoubleArray>; val m: RegattaLinkMagAutoMetadata
        synchronized(this) {
            if (state.phase != RegattaLinkMagPhase.CAPTURE_READY) return false
            points = capturePoints ?: return false; m = captureMetadata ?: return false
        }
        val t = acquire() ?: return false
        launch(t,RegattaLinkMagPhase.FITTING_2D,"FIT_2D",math) {
            val fit = RegattaLinkMagAutoFitter.fit(points,m.binding(t.session.io.stableId),m.basis)
            publish(t) { it.copy(phase = RegattaLinkMagPhase.PROPOSAL_2D, autoFit = fit) }
        }
        return true
    }
    fun exportArchive(): Boolean {
        val t = acquire() ?: return false
        publish(t) { it.copy(archive = null, fit = null, progress = 0) }
        launch(t,RegattaLinkMagPhase.EXPORTING,"EXPORT") {
            var opened = false
            try {
                val m = metadata(t); m.requireReady()
                write(t,MagChannel.EXPORT_CONTROL,MAG_EXPORT_OPEN); opened = true
                val manifest = parseRegattaLinkMagManifest(read(t,MagChannel.EXPORT_CONTROL))
                require(manifest.factoryCrc == m.factoryCrc) { "Archive factory changed" }
                val output = ByteArrayOutputStream(manifest.byteCount)
                repeat(manifest.pageCount) { i ->
                    write(t,MagChannel.EXPORT_CONTROL,magExportSelect(i))
                    output.write(parseRegattaLinkMagPage(read(t,MagChannel.EXPORT_PAGE),i,manifest))
                    publish(t) { it.copy(progress = (i+1)*100/manifest.pageCount) }
                }
                val archive = parseRegattaLinkMagArchive(manifest,output.toByteArray())
                val final = metadata(t)
                requireBinding(t,m.binding(t.session.io.stableId),final)
                require(archive.uprightRef == m.uprightRef && archive.basis?.same(m.basis) == true) { "Archive reference changed" }
                publish(t) { it.copy(phase = RegattaLinkMagPhase.ARCHIVE_READY, archive = archive, fit = null, metadata = final, progress = 100) }
            } finally {
                if (opened && current(t)) runCatching { write(t,MagChannel.EXPORT_CONTROL,MAG_EXPORT_CLOSE) }
            }
        }
        return true
    }
    fun calculateFit(): Boolean {
        val archive: RegattaLinkMagArchive
        synchronized(this) {
            if (state.phase != RegattaLinkMagPhase.ARCHIVE_READY || state.metadata?.autoStored != true) return false
            archive = state.archive ?: return false
        }
        val t = acquire() ?: return false
        launch(t,RegattaLinkMagPhase.FITTING,"FIT_3D",math) {
            val fit = RegattaLinkMagFitter.fit(archive)
            publish(t) { it.copy(phase = RegattaLinkMagPhase.FIT_READY, fit = fit) }
        }
        return true
    }
    fun installAuto(): Boolean {
        val fit = synchronized(this) { state.autoFit?.takeIf { state.phase == RegattaLinkMagPhase.PROPOSAL_2D } } ?: return false
        val t = acquire() ?: return false
        launch(t,RegattaLinkMagPhase.TRANSFERRING,"IMPORT_2D") {
            val m = metadata(t); requireBinding(t,fit.binding,m)
            install(t,MagPending(MagObjectKind.AUTO_2D,fit.binding,request(),
                encodeRegattaLinkMagAutoModel(fit.binding,m.basis,fit.offset),m.bootId))
        }
        return true
    }
    fun installManual(): Boolean {
        val archive: RegattaLinkMagArchive; val fit: RegattaLinkMagFit
        synchronized(this) {
            if (state.phase != RegattaLinkMagPhase.FIT_READY || state.metadata?.autoStored != true) return false
            archive = state.archive ?: return false; fit = state.fit ?: return false
        }
        val t = acquire() ?: return false
        launch(t,RegattaLinkMagPhase.TRANSFERRING,"IMPORT_3D") {
            val m = metadata(t)
            requireBinding(t,RegattaLinkMagBinding(t.session.io.stableId,archive.manifest.factoryCrc,archive.uprightRef),m)
            require(m.autoStored) { "Install a 2D calibration first" }
            install(t,MagPending(MagObjectKind.MANUAL_3D,m.binding(t.session.io.stableId),request(),
                encodeRegattaLinkMagModel(archive.manifest,fit),m.bootId))
        }
        return true
    }
    private fun install(t: Lease, pending: MagPending) {
        val auto = pending.kind == MagObjectKind.AUTO_2D
        val control = if (auto) MagChannel.AUTO else MagChannel.IMPORT_CONTROL
        var mayCommit = false
        var journal = load(t)
        require(journal.pending == null) { "Resolve pending MAG import first" }
        require(auto || journal.backup == null) { "A reset backup may only restore MAG2" }
        try {
            write(t,control,if (auto) magAutoCommand(1,pending.requestId,pending.crc) else magUploadCommand(1,pending.requestId,pending.crc))
            val size = if (auto) 12 else 18
            for (offset in pending.objectBytes.indices step size) {
                val part = pending.objectBytes.copyOfRange(offset,minOf(offset+size,pending.objectBytes.size))
                write(t,if(auto) control else MagChannel.IMPORT_DATA,
                    if(auto) magAutoFragment(pending.requestId,offset,part) else magUploadFragment(offset,part))
                publish(t) { it.copy(progress = (offset+part.size)*100/pending.objectBytes.size) }
            }
            if (!auto) {
                publish(t) { it.copy(phase = RegattaLinkMagPhase.VALIDATING) }
                write(t,control,magUploadCommand(2,pending.requestId))
            }
            // Persist exact object + correlation BEFORE the first possible COMMIT submission.
            journal = journal.copy(pending = pending, backup = journal.backup?.copy(stage = MagRestoreStage.RESTORE_2D_MAY_HAVE_BEEN_SENT))
            save(t,journal)
            mayCommit = true
            publish(t) { it.copy(phase = RegattaLinkMagPhase.PERSISTING, requestId = pending.requestId, recoveryAvailable = true) }
            try { write(t,control,if(auto) magAutoCommand(3,pending.requestId) else magUploadCommand(3,pending.requestId)) }
            catch (e: Exception) { checkCurrent(t) } // Lost ATT ACK may already have persisted. Read proof; never retry/abort.
            val deadline = now()+30_000
            do {
                val m = metadata(t); val s = status(t)
                showMetadata(t,m,s)
                when (magReceipt(pending,m,s,t.session.io.stableId)) {
                    MagReceipt.STORED -> { reconcile(t,m,s); return }
                    MagReceipt.REJECTED -> { reconcile(t,m,s); error("Device rejected MAG object") }
                    MagReceipt.UNKNOWN -> Unit
                }
                pause(200)
            } while(now() < deadline)
            error("MAG persistence unverified; reconnect and check recovery")
        } finally {
            if (!mayCommit && current(t)) runCatching {
                write(t,control,if(auto) magAutoCommand(4,pending.requestId) else magUploadCommand(4,pending.requestId))
            }
        }
    }

    fun resetAndRestore(): Boolean {
        synchronized(this) { if (state.metadata?.autoStored != true) return false }
        val t = acquire() ?: return false
        launch(t,RegattaLinkMagPhase.RESTORING,"RESET_RESTORE") {
            val existing = load(t)
            require(existing.pending == null && existing.backup == null) { "Resolve existing MAG recovery first" }
            val m = metadata(t); m.requireReady()
            require(m.autoStored) { "No stored 2D calibration to back up" }
            val binding = m.binding(t.session.io.stableId)
            val backup = MagBackup(binding,encodeRegattaLinkMagAutoModel(binding,m.basis,m.offset),
                t.session.io.readTrim(),request(),MagRestoreStage.BACKUP_SAVED)
            save(t,MagJournal(backup = backup))
            publish(t) { it.copy(recoveryAvailable = true) }
            restore(t)
        }
        return true
    }
    private fun resetSucceeded(t: Lease, id: UInt): Boolean {
        checkCurrent(t)
        return t.session.io.resetConfirmed(id).also { checkCurrent(t) }
    }
    private fun restore(t: Lease) {
        var journal = load(t)
        val original = journal.backup ?: return
        var backup = original
        var m = metadata(t); requireBinding(t,backup.binding,m)
        if (journal.pending != null) {
            journal = reconcile(t,m,status(t))
            require(journal.pending == null) { "Restore import remains unverified" }
            backup = journal.backup ?: return
        }
        if (backup.stage == MagRestoreStage.BACKUP_SAVED) {
            backup = backup.copy(stage = MagRestoreStage.RESET_MAY_HAVE_BEEN_SENT)
            save(t,journal.copy(backup = backup))
            // Composite owner invokes the wire command directly; public Device Control remains blocked.
            try { t.session.io.reset(backup.resetRequest) }
            catch (e: Exception) { checkCurrent(t) }
            val deadline = now()+12_000
            while (!resetSucceeded(t,backup.resetRequest) && now() < deadline) pause(100)
        }
        if (backup.stage == MagRestoreStage.RESET_MAY_HAVE_BEEN_SENT) {
            // A lost reset receipt (e.g. MCU reboot) is deliberately UNKNOWN. Do not repeat reset.
            require(resetSucceeded(t,backup.resetRequest)) { "Reset result unverified; saved backup retained" }
            backup = backup.copy(stage = MagRestoreStage.RESET_CONFIRMED)
            journal = MagJournal(backup = backup); save(t,journal)
        }
        m = metadata(t); requireBinding(t,backup.binding,m)
        if (backup.stage == MagRestoreStage.RESET_CONFIRMED) {
            install(t,MagPending(MagObjectKind.AUTO_2D,backup.binding,request(),backup.objectBytes,m.bootId))
            journal = load(t); backup = journal.backup ?: return
        }
        require(backup.stage in setOf(MagRestoreStage.RESTORE_2D_CONFIRMED,MagRestoreStage.RESTORE_TRIM)) {
            "Restore outcome requires recovery"
        }
        // Re-verify stored bytes before restoring trim, even after a process/MCU restart.
        m = metadata(t); requireBinding(t,backup.binding,m)
        val actual = encodeRegattaLinkMagAutoModel(backup.binding,m.basis,m.offset)
        require(m.autoSelected && !m.offSelected) { "Running MAG mode changed during restore" }
        require(m.autoStored && actual.contentEquals(backup.objectBytes)) { "Stored 2D offset changed before trim restore" }
        save(t,MagJournal(backup = backup.copy(stage = MagRestoreStage.RESTORE_TRIM)))
        t.session.io.writeTrim(backup.trimDeg)
        require(t.session.io.readTrim() == backup.trimDeg) { "Restored heading trim readback differs" }
        save(t,MagJournal())
        publish(t) { it.copy(phase = RegattaLinkMagPhase.STORED, recoveryAvailable = false, metadata = m,
            archive = null, fit = null, autoFit = null, progress = 100, error = "", errorStage = "") }
    }
    fun resumeRecovery(): Boolean {
        val t = acquire(recovery = true) ?: return false
        launch(t,RegattaLinkMagPhase.RESTORING,"RECOVERY") {
            val m = metadata(t); val s = status(t); showMetadata(t,m,s)
            val journal = reconcile(t,m,s)
            if (journal.pending != null) return@launch
            if (journal.backup != null) restore(t)
        }
        return true
    }
    fun discardRecovery(): Boolean {
        val t = acquire(recovery = true) ?: return false
        launch(t,RegattaLinkMagPhase.READING,"DISCARD_RECOVERY") {
            save(t,MagJournal()) // Explicit user confirmation must explain loss of backup and uncertain outcome.
            publish(t) { it.copy(phase = RegattaLinkMagPhase.IDLE,recoveryAvailable = false,error = "",errorStage = "") }
        }
        return true
    }
}
