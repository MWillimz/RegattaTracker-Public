package de.williserv.regattaclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RegattaLinkLoadMeasurementsTest {
    @Test
    fun stableCatalogAndX10SampleProduceStableMeasurement() {
        val assembler = RegattaLinkLoadPacketAssembler { key ->
            "Vorstag".takeIf {
                key == "stable:nmea:0807060504030201.3"
            }
        }

        assertNull(
            assembler.accept(
                catalog(
                    epoch = 7,
                    slot = 2,
                    stable = true,
                    kind = 2,
                    id = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 3)
                )
            )
        )
        val sensors = requireNotNull(
            assembler.accept(
                sample(
                    epoch = 7,
                    sequence = 4,
                    x10 = true,
                    entries = listOf(2 to 705)
                )
            )
        )

        val sensor = sensors.single()
        assertEquals("nmea.load.0807060504030201.3", sensor.measurementKey)
        assertEquals("Vorstag", sensor.label)
        assertEquals(70.5, sensor.loadKg, 0.0001)
        assertTrue(sensor.stableIdentity)
    }

    @Test
    fun x1SampleUsesOneKilogramCounts() {
        val assembler = RegattaLinkLoadPacketAssembler()
        assembler.accept(
            catalog(
                epoch = 1,
                slot = 0,
                stable = true,
                kind = 2,
                id = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0)
            )
        )

        val sensor = requireNotNull(
            assembler.accept(
                sample(
                    epoch = 1,
                    sequence = 1,
                    x10 = false,
                    entries = listOf(0 to 71)
                )
            )
        ).single()

        assertEquals(71.0, sensor.loadKg, 0.0001)
    }

    @Test
    fun multiFragmentSampleIsPublishedOnlyWhenComplete() {
        val assembler = RegattaLinkLoadPacketAssembler()
        repeat(3) { slot ->
            assembler.accept(
                catalog(
                    epoch = 9,
                    slot = slot,
                    stable = true,
                    kind = 2,
                    id = byteArrayOf(
                        (slot + 1).toByte(),
                        0, 0, 0, 0, 0, 0, 0,
                        slot.toByte()
                    )
                )
            )
        }

        assertNull(
            assembler.accept(
                sample(
                    epoch = 9,
                    sequence = 8,
                    x10 = true,
                    fragmentIndex = 0,
                    fragmentCount = 2,
                    entries = listOf(0 to 100, 1 to 200)
                )
            )
        )
        val sensors = requireNotNull(
            assembler.accept(
                sample(
                    epoch = 9,
                    sequence = 8,
                    x10 = true,
                    fragmentIndex = 1,
                    fragmentCount = 2,
                    entries = listOf(2 to 300)
                )
            )
        )

        assertEquals(listOf(10.0, 20.0, 30.0), sensors.map { it.loadKg })
    }

    @Test
    fun emptySampleClearsFreshSet() {
        val assembler = RegattaLinkLoadPacketAssembler()
        assembler.accept(
            catalog(
                epoch = 2,
                slot = 0,
                stable = true,
                kind = 2,
                id = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0)
            )
        )
        assertTrue(
            requireNotNull(
                assembler.accept(
                    sample(
                        epoch = 2,
                        sequence = 1,
                        x10 = false,
                        entries = listOf(0 to 50)
                    )
                )
            ).isNotEmpty()
        )

        assertTrue(
            requireNotNull(
                assembler.accept(
                    sample(
                        epoch = 2,
                        sequence = 2,
                        x10 = false,
                        entries = emptyList()
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun sourceAddressFallbackUsesAnonymousBootLocalChannel() {
        val assembler = RegattaLinkLoadPacketAssembler { "should-not-apply" }
        assembler.accept(
            catalog(
                epoch = 1,
                slot = 1,
                stable = false,
                kind = 1,
                id = byteArrayOf(0x22, 4)
            )
        )
        val sensor = requireNotNull(
            assembler.accept(
                sample(
                    epoch = 1,
                    sequence = 1,
                    x10 = true,
                    entries = listOf(1 to 123)
                )
            )
        ).single()

        assertEquals("temporary:load-slot:1", sensor.identityKey)
        assertEquals("regattalink.load.1", sensor.measurementKey)
        assertEquals("Load 1", sensor.label)
        assertFalse(sensor.stableIdentity)
        assertFalse(sensor.identityKey.contains("22"))
        assertFalse(sensor.measurementKey.contains("22"))
    }

    @Test
    fun multipleTemporarySensorsUseTheirRuntimeSlots() {
        val assembler = RegattaLinkLoadPacketAssembler()
        assembler.accept(
            catalog(
                epoch = 5,
                slot = 0,
                stable = false,
                kind = 1,
                id = byteArrayOf(0x22, 0)
            )
        )
        assembler.accept(
            catalog(
                epoch = 5,
                slot = 1,
                stable = false,
                kind = 1,
                id = byteArrayOf(0x33, 7)
            )
        )

        val sensors = requireNotNull(
            assembler.accept(
                sample(
                    epoch = 5,
                    sequence = 1,
                    x10 = false,
                    entries = listOf(0 to 61, 1 to 42)
                )
            )
        )

        assertEquals(
            listOf("regattalink.load.0", "regattalink.load.1"),
            sensors.map { it.measurementKey }
        )
        assertEquals(listOf("Load 0", "Load 1"), sensors.map { it.label })
    }

    @Test
    fun resetRequiresFreshCatalogBeforeReconnectSamplesAreAccepted() {
        val assembler = RegattaLinkLoadPacketAssembler()
        val catalogFrame = catalog(
            epoch = 3,
            slot = 0,
            stable = true,
            kind = 2,
            id = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0)
        )
        assembler.accept(catalogFrame)
        assertTrue(
            requireNotNull(
                assembler.accept(
                    sample(
                        epoch = 3,
                        sequence = 1,
                        x10 = false,
                        entries = listOf(0 to 50)
                    )
                )
            ).isNotEmpty()
        )

        assembler.reset()

        assertNull(
            assembler.accept(
                sample(
                    epoch = 3,
                    sequence = 2,
                    x10 = false,
                    entries = listOf(0 to 99)
                )
            )
        )
        assembler.accept(catalogFrame)
        assertEquals(
            99.0,
            requireNotNull(
                assembler.accept(
                    sample(
                        epoch = 3,
                        sequence = 3,
                        x10 = false,
                        entries = listOf(0 to 99)
                    )
                )
            ).single().loadKg,
            0.0001
        )
    }

    @Test
    fun aliasRenameDoesNotChangeMeasurementKey() {
        val original = RegattaLinkLoadSensor(
            identityKey = "stable:nmea:0123456789abcdef.0",
            measurementKey = "nmea.load.0123456789abcdef.0",
            defaultLabel = "Load 0",
            alias = "Vorstag",
            loadKg = 70.5,
            stableIdentity = true
        )
        val renamed = original.copy(alias = "Forestay")

        assertEquals(original.measurementKey, renamed.measurementKey)
        assertEquals("Forestay", renamed.label)
    }

    @Test
    fun snapshotStoreWritesExistingDynamicMeasurementContract() {
        RegattaLinkLoadSnapshotStore.update(
            sensors = listOf(
                RegattaLinkLoadSensor(
                    identityKey = "stable:nmea:0123456789abcdef.0",
                    measurementKey = "nmea.load.0123456789abcdef.0",
                    defaultLabel = "Load 0",
                    alias = "Vorstag",
                    loadKg = 70.5,
                    stableIdentity = true
                ),
                RegattaLinkLoadSensor(
                    identityKey = "stable:nmea:fedcba9876543210.1",
                    measurementKey = "nmea.load.fedcba9876543210.1",
                    defaultLabel = "Load 1",
                    loadKg = 42.0,
                    stableIdentity = true
                )
            ),
            receivedAtElapsedMs = 1_000L
        )

        val json = JSONObject(
            requireNotNull(
                RegattaLinkLoadSnapshotStore.measurementsJson(
                    nowElapsedMs = 2_000L
                )
            )
        )
        assertEquals(2, json.length())
        val first = json.getJSONObject("nmea.load.0123456789abcdef.0")
        assertEquals(70.5, first.getDouble("value"), 0.0001)
        assertEquals("kg", first.getString("unit"))
        assertEquals("load", first.getString("group"))
        assertEquals("Vorstag", first.getString("label"))

        assertNull(
            RegattaLinkLoadSnapshotStore.measurementsJson(
                nowElapsedMs =
                    1_001L + REGATTALINK_LOAD_TRANSPORT_STALE_MS
            )
        )

        RegattaLinkLoadSnapshotStore.clear()
        assertNull(
            RegattaLinkLoadSnapshotStore.measurementsJson(
                nowElapsedMs = 2_000L
            )
        )
    }

    @Test
    fun aliasMetadataChangeDoesNotRefreshTransportFreshness() {
        val sensor = RegattaLinkLoadSensor(
            identityKey = "stable:nmea:0123456789abcdef.0",
            measurementKey = "nmea.load.0123456789abcdef.0",
            defaultLabel = "Load 0",
            loadKg = 70.5,
            stableIdentity = true
        )
        RegattaLinkLoadSnapshotStore.update(
            sensors = listOf(sensor),
            receivedAtElapsedMs = 1_000L
        )

        RegattaLinkLoadSnapshotStore.updateAlias(
            identityKey = sensor.identityKey,
            alias = "Vorstag"
        )

        assertNull(
            RegattaLinkLoadSnapshotStore.measurementsJson(
                nowElapsedMs =
                    1_001L + REGATTALINK_LOAD_TRANSPORT_STALE_MS
            )
        )
        RegattaLinkLoadSnapshotStore.clear()
    }

    @Test
    fun transportTimeoutClearsVisibleLoadSensors() {
        val sensor = RegattaLinkLoadSensor(
            identityKey = "stable:nmea:0123456789abcdef.0",
            measurementKey = "nmea.load.0123456789abcdef.0",
            defaultLabel = "Load 0",
            loadKg = 70.5,
            stableIdentity = true
        )
        val state = RegattaLinkNmeaState(
            loadSupported = true,
            loadSubscribed = true,
            loadSensors = listOf(sensor),
            loadReceivedAtElapsedMs = 1_000L
        )

        val stillFresh = regattaLinkExpireLoadSensorsIfTransportStale(
            state = state,
            nowElapsedMs =
                1_000L + REGATTALINK_LOAD_TRANSPORT_STALE_MS - 1L
        )
        assertEquals(1, stillFresh.loadSensors.size)

        val stale = regattaLinkExpireLoadSensorsIfTransportStale(
            state = state,
            nowElapsedMs =
                1_000L + REGATTALINK_LOAD_TRANSPORT_STALE_MS
        )
        assertTrue(stale.loadSensors.isEmpty())
        assertNull(stale.loadReceivedAtElapsedMs)
        assertTrue(stale.loadSupported)
        assertTrue(stale.loadSubscribed)
    }

    @Test
    fun loadMeasurementsMergeWithoutOverwritingImuOrNmea() {
        val load =
            """{"nmea.load.0123456789abcdef.0":{"value":70.5,"unit":"kg","group":"load"}}"""
        val nmea =
            """{"nmea.stw_mps":{"value":3.2,"unit":"m/s","group":"nmea"}}"""
        val imu =
            """{"regattalink.motion.heel_deg":{"value":4.0,"unit":"deg","group":"regattalink"}}"""

        val merged = JSONObject(
            requireNotNull(mergeMeasurementsJson(imu, nmea, load))
        )
        assertEquals(3, merged.length())
        assertTrue(merged.has("nmea.load.0123456789abcdef.0"))
        assertTrue(merged.has("nmea.stw_mps"))
        assertTrue(merged.has("regattalink.motion.heel_deg"))
    }

    private fun catalog(
        epoch: Int,
        slot: Int,
        stable: Boolean,
        kind: Int,
        id: ByteArray
    ): ByteArray =
        byteArrayOf(
            ((1 shl 4) or (1 shl 1)).toByte(),
            epoch.toByte(),
            slot.toByte(),
            ((if (stable) 0x80 else 0) or kind).toByte(),
            id.size.toByte()
        ) + id

    private fun sample(
        epoch: Int,
        sequence: Int,
        x10: Boolean,
        fragmentIndex: Int = 0,
        fragmentCount: Int = 1,
        entries: List<Pair<Int, Int>>
    ): ByteArray {
        val bytes = mutableListOf<Byte>(
            ((1 shl 4) or (2 shl 1) or if (x10) 1 else 0).toByte(),
            epoch.toByte(),
            sequence.toByte(),
            (((fragmentCount - 1) shl 4) or fragmentIndex).toByte()
        )
        entries.forEach { (slot, value) ->
            bytes += slot.toByte()
            bytes += (value and 0xff).toByte()
            bytes += ((value ushr 8) and 0xff).toByte()
        }
        return bytes.toByteArray()
    }
}
