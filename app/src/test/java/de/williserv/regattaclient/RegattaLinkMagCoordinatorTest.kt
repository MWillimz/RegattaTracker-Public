package de.williserv.regattaclient

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RegattaLinkMagCoordinatorTest {
    private class Queue: Executor {
        val jobs = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { jobs.add(command) }
        fun drain() { while (jobs.isNotEmpty()) jobs.removeFirst().run() }
    }
    private class Store: MagJournalStore {
        val records = mutableMapOf<String,MagJournal>()
        var fail: (MagJournal) -> Boolean = { false }
        override fun load(stableId: String) = records[stableId] ?: MagJournal()
        override fun save(stableId: String,journal: MagJournal) {
            check(!fail(journal)) { "Local disk failure" }; records[stableId] = journal
        }
    }
    private class Io(override val stableId: String = "boat"): RegattaLinkMagIo {
        var m = MagTestData.metadata()
        var page = 0
        var frozen = MagTestData.bytes(m)
        val writes = mutableListOf<Pair<MagChannel,ByteArray>>()
        val subscriptions = mutableListOf<Boolean>()
        var onSubscribe: (() -> Unit)? = null
        var onCommit: (() -> Unit)? = null
        var onReset: (() -> Unit)? = null
        var failCommitBefore = false
        var failCommitAfter = false
        var failTrim = false
        var resetReceipt = true
        var reject = false
        var resets = 0
        var trim = -17
        var resetRequest = 0u
        var importRequest = 0u
        var objectBytes = ByteArray(32)
        override fun read(channel: MagChannel): ByteArray = when(channel) {
            MagChannel.AUTO -> {
                val data = frozen.copyOfRange(page*16,minOf(page*16+16,88))
                ByteBuffer.allocate(4+data.size).order(ByteOrder.LITTLE_ENDIAN).put(1).put(page.toByte()).putShort(10).put(data).array()
            }
            MagChannel.IMPORT_STATUS -> ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                .put(1).put(0).put(0).put(0x81.toByte()).putInt(0).putInt(m.factoryCrc.toInt()).putInt(5).putInt(0).array()
            else -> error("Unexpected read $channel")
        }
        override fun write(channel: MagChannel,bytes: ByteArray) {
            writes += channel to bytes.copyOf()
            if (channel != MagChannel.AUTO) return
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            when (b.get().toInt()) {
                5 -> { b.get(); page = b.get().toInt() and 255; if(page == 255) frozen = MagTestData.bytes(m) }
                1 -> { b.get(); importRequest = b.int.toUInt(); assertEquals(32,b.short.toInt()); objectBytes = ByteArray(32) }
                2 -> {
                    b.get(); assertEquals(importRequest,b.int.toUInt()); val offset = b.short.toInt() and 65535
                    val part = ByteArray(b.remaining()); b.get(part); part.copyInto(objectBytes,offset)
                }
                3 -> {
                    if (failCommitBefore) error("Lost COMMIT ACK before persistence")
                    if (reject) m = m.copy(phase=5,errorCode=4,requestId=importRequest,objectCrc=magCrc32(objectBytes))
                    else {
                        val offset = ByteBuffer.wrap(objectBytes).order(ByteOrder.LITTLE_ENDIAN).let { raw -> DoubleArray(3) { raw.getFloat(16+it*4).toDouble() } }
                        m = m.copy(phase=4,requestId=importRequest,objectCrc=magCrc32(objectBytes),offset=offset,
                            storedRequestId=importRequest,storedObjectCrc=magCrc32(objectBytes),flags=m.flags or 4)
                    }
                    onCommit?.invoke()
                    if(failCommitAfter) error("Lost COMMIT ACK after persistence")
                }
            }
        }
        override fun subscribe(enabled: Boolean) {
            subscriptions += enabled; m = m.copy(streamContext=m.streamContext+1u)
            if(enabled) onSubscribe?.invoke()
        }
        override fun reset(requestId: UInt) {
            resets++; resetRequest = requestId
            m = m.copy(flags=(m.flags and (4 or 16 or 32).inv()) or 8,offset=doubleArrayOf(0.0,0.0,0.0),activeKind=0)
            trim = 0; onReset?.invoke()
        }
        override fun resetConfirmed(requestId: UInt) = resetReceipt && requestId == resetRequest
        override fun readTrim() = trim
        override fun writeTrim(value: Int) { if(failTrim) error("Trim write failed"); trim = value }
    }
    private val wire = Queue()
    private val math = Queue()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val store = Store()
    private var clock = 0L
    private var state = RegattaLinkMagState()
    private fun coordinator() = RegattaLinkMagCoordinator(wire,math,timer,store,{ state = it },{ clock },{ clock += it })
    @After fun close() { timer.shutdownNow() }
    private fun ready(c: RegattaLinkMagCoordinator,io: Io) { c.bind(io,true); wire.drain() }
    private fun proposal(c: RegattaLinkMagCoordinator) {
        assertTrue(c.startCapture()); wire.drain()
        repeat(120) { i ->
            clock += 100
            c.live(MagTestData.live(i,i.toUInt()*100u,MagTestData.circle()[i]))
        }
        assertTrue(c.stopCapture()); wire.drain()
        assertEquals(RegattaLinkMagPhase.CAPTURE_READY,state.phase)
        assertTrue(c.calculateAutoFit()); math.drain()
        assertEquals(RegattaLinkMagPhase.PROPOSAL_2D,state.phase)
    }
    @Test fun cccdAckThenMetadataNoReadsPerSampleAndUnsubscribeDoesNotInvalidateProposal() {
        val c = coordinator(); val io = Io(); ready(c,io)
        io.onSubscribe = { c.live(ByteArray(2)) } // Ignored until initial metadata is frozen.
        proposal(c)
        assertEquals(listOf(true,false),io.subscriptions)
        val metadataWrites = io.writes.count { it.second[0].toInt() == 5 }
        assertTrue(c.installAuto()); wire.drain()
        assertEquals(RegattaLinkMagPhase.STORED,state.phase)
        assertTrue(io.writes.count { it.second[0].toInt() == 5 } > metadataWrites)
        val parts = io.writes.filter { it.second[0].toInt() == 2 }.map { it.second.size }
        assertEquals(listOf(20,20,16),parts)
        assertFalse(c.busy())
    }
    @Test fun duplicateClicksAndMalformedLiveCleanUpTheOwnerAndCccd() {
        val c = coordinator(); val io = Io(); ready(c,io)
        assertTrue(c.startCapture()); assertFalse(c.startCapture()); wire.drain()
        assertFalse(c.exportArchive()); assertFalse(c.refresh())
        c.live(ByteArray(19)); wire.drain()
        assertEquals(RegattaLinkMagPhase.ERROR,state.phase)
        assertEquals(listOf(true,false),io.subscriptions)
        assertFalse(c.busy())
    }
    @Test fun screenClosingBeforeQueuedCaptureStartDoesNotLeaveAHiddenSubscription() {
        val c = coordinator(); val io = Io(); ready(c,io)
        assertTrue(c.startCapture())
        assertTrue(c.stopCapture()) // Dismissal/background before the GATT worker has started.
        wire.drain()
        assertTrue(io.subscriptions.isEmpty())
        assertEquals(RegattaLinkMagPhase.IDLE,state.phase)
        assertFalse(c.busy())
    }
    @Test fun closingDuringCccdSetupStillDisablesNotifications() {
        val c = coordinator(); val io = Io(); ready(c,io)
        io.onSubscribe = { assertTrue(c.stopCapture()) }
        assertTrue(c.startCapture()); wire.drain()
        assertEquals(listOf(true,false),io.subscriptions)
        assertEquals(RegattaLinkMagPhase.IDLE,state.phase)
        assertFalse(c.busy())
    }
    @Test fun localSaveFailureNeverSubmitsCommitAndAbortsOnlyUncommittedStaging() {
        val c = coordinator(); val io = Io(); ready(c,io); proposal(c)
        store.fail = { it.pending != null }
        assertTrue(c.installAuto()); wire.drain()
        assertEquals(RegattaLinkMagPhase.ERROR,state.phase)
        assertFalse(io.writes.any { it.second[0].toInt() == 3 })
        assertTrue(io.writes.any { it.second[0].toInt() == 4 })
    }
    @Test fun lostAckAfterCommitUsesDurableReceiptWithoutRetryOrAbort() {
        val c = coordinator(); val io = Io(); ready(c,io); proposal(c)
        io.failCommitAfter = true
        assertTrue(c.installAuto()); wire.drain()
        assertEquals(RegattaLinkMagPhase.STORED,state.phase)
        assertEquals(1,io.writes.count { it.second[0].toInt() == 3 })
        assertEquals(0,io.writes.count { it.second[0].toInt() == 4 })
        assertNull(store.load("boat").pending)
    }
    @Test fun lostAckBeforePersistenceStaysUnknownAcrossProcessRestartAndBlocksMutations() {
        val c = coordinator(); val io = Io(); ready(c,io); proposal(c)
        io.failCommitBefore = true
        assertTrue(c.installAuto()); wire.drain()
        assertEquals(RegattaLinkMagPhase.UNKNOWN,state.phase)
        assertNotNull(store.load("boat").pending)
        val restarted = coordinator(); ready(restarted,io)
        assertEquals(RegattaLinkMagPhase.UNKNOWN,state.phase)
        assertFalse(restarted.startCapture()); assertFalse(restarted.installAuto()); assertFalse(restarted.resetAndRestore())
        assertTrue(restarted.resumeRecovery()); wire.drain()
        assertEquals(1,io.writes.count { it.second[0].toInt() == 3 })
        assertEquals(0,io.writes.count { it.second[0].toInt() == 4 })
    }
    @Test fun disconnectAndMcuRebootAfterCommitCanBeProvedOnFreshConnection() {
        val c = coordinator(); val io = Io(); ready(c,io); proposal(c)
        io.onCommit = { c.disconnect(); throw IllegalStateException("BLE disconnected") }
        assertTrue(c.installAuto()); wire.drain()
        assertNotNull(store.load("boat").pending)
        io.onCommit = null; io.m = io.m.copy(bootId=42u,streamContext=90u,phase=0)
        val restarted = coordinator(); ready(restarted,io)
        assertEquals(RegattaLinkMagPhase.STORED,state.phase)
        assertNull(store.load("boat").pending)
    }
    @Test fun asynchronousPersistErrorIsNotSuccessAndDoesNotAbortOrRetry() {
        val c = coordinator(); val io = Io(); ready(c,io); proposal(c); io.reject = true
        assertTrue(c.installAuto()); wire.drain()
        assertEquals(RegattaLinkMagPhase.ERROR,state.phase)
        assertNull(store.load("boat").pending)
        assertEquals(0,io.writes.count { it.second[0].toInt() == 4 })
    }
    @Test fun resetBackupPrecedesDestructiveWriteAndRestoreVerifiesTrimLast() {
        val c = coordinator(); val io = Io(); ready(c,io)
        io.onReset = {
            assertEquals(MagRestoreStage.RESET_MAY_HAVE_BEEN_SENT,store.load("boat").backup?.stage)
            assertTrue(c.busy()); assertFalse(c.startCapture()); assertFalse(c.installAuto())
        }
        assertTrue(c.resetAndRestore()); wire.drain()
        assertEquals(1,io.resets); assertEquals(-17,io.trim)
        assertEquals(RegattaLinkMagPhase.STORED,state.phase)
        assertFalse(state.recoveryAvailable)
        assertNull(store.load("boat").backup)
        assertArrayEquals(doubleArrayOf(6.0,-4.0,0.0),io.m.offset,0.0)
    }
    @Test fun backupSaveFailureNeverResetsDevice() {
        val c = coordinator(); val io = Io(); ready(c,io); store.fail = { true }
        assertTrue(c.resetAndRestore()); wire.drain()
        assertEquals(0,io.resets); assertEquals(-17,io.trim)
    }
    @Test fun unknownResetNeverRepeatsResetEvenAfterProcessRestart() {
        val c = coordinator(); val io = Io(); ready(c,io); io.resetReceipt = false
        assertTrue(c.resetAndRestore()); wire.drain()
        assertEquals(RegattaLinkMagPhase.UNKNOWN,state.phase)
        assertNotNull(store.load("boat").backup)
        val restarted = coordinator(); ready(restarted,io)
        assertTrue(restarted.resumeRecovery()); wire.drain()
        assertEquals(1,io.resets)
        assertNotNull(store.load("boat").backup)
        io.resetReceipt = true
        assertTrue(restarted.resumeRecovery()); wire.drain()
        assertEquals(1,io.resets); assertEquals(-17,io.trim)
        assertNull(store.load("boat").backup)
    }
    @Test fun interruptedTrimRestoresFromBackupAfterRebootWithoutAnotherResetOrImport() {
        val c = coordinator(); val io = Io(); ready(c,io); io.failTrim = true
        assertTrue(c.resetAndRestore()); wire.drain()
        assertEquals(MagRestoreStage.RESTORE_TRIM,store.load("boat").backup?.stage)
        assertEquals(0,io.trim)
        val imports = io.writes.count { it.second[0].toInt() == 3 }
        io.failTrim = false; io.m = io.m.copy(bootId=99u,streamContext=888u)
        val restarted = coordinator(); ready(restarted,io)
        assertTrue(restarted.resumeRecovery()); wire.drain()
        assertEquals(1,io.resets); assertEquals(imports,io.writes.count { it.second[0].toInt() == 3 })
        assertEquals(-17,io.trim); assertNull(store.load("boat").backup)
    }
    @Test fun referenceChangePreservesBackupAndBlocksWrongRestore() {
        val c = coordinator(); val io = Io(); ready(c,io); io.failTrim = true
        assertTrue(c.resetAndRestore()); wire.drain()
        io.m = io.m.copy(uprightRef=1234u)
        assertTrue(c.resumeRecovery()); wire.drain()
        assertEquals(RegattaLinkMagPhase.UNKNOWN,state.phase)
        assertNotNull(store.load("boat").backup); assertEquals(0,io.trim)
    }
    @Test fun lateFitErrorAndFinallyCannotOverwriteOrReleaseNewDeviceCapture() {
        val c = coordinator(); val first = Io(); ready(c,first)
        assertTrue(c.startCapture()); wire.drain()
        repeat(30) { c.live(MagTestData.live(it,it.toUInt()*100u,doubleArrayOf(1.0,1.0,1.0))) }
        assertTrue(c.stopCapture()); wire.drain()
        assertTrue(c.calculateAutoFit()) // Deferred failing math worker for first device.
        c.disconnect(); val next = Io("other"); ready(c,next)
        assertTrue(c.startCapture()); wire.drain()
        math.drain()
        assertEquals(RegattaLinkMagPhase.CAPTURING,state.phase)
        assertTrue(c.busy()); assertFalse(c.exportArchive())
    }
}
