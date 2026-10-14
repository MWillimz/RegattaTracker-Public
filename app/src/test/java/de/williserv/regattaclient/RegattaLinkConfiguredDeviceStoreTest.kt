package de.williserv.regattaclient

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RegattaLinkConfiguredDeviceStoreTest {
    private lateinit var context: Context
    private lateinit var store: RegattaLinkConfiguredDeviceStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(
            RegattaLinkConfiguredDeviceStore.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        store = RegattaLinkConfiguredDeviceStore(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(
            RegattaLinkConfiguredDeviceStore.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }

    @Test
    fun emptyStoreHasNoConfiguredDevice() {
        assertNull(store.load())
    }

    @Test
    fun configuredDeviceRoundTripsStableIdentityAndBondAddress() {
        val configured = RegattaLinkConfiguredDevice(
            stableId = "0011223344556677",
            deviceAddress = "44:B1:76:48:31:B2",
            deviceName = "RegattaLink-31B2"
        )

        store.save(configured)

        assertEquals(configured, store.load())
    }

    @Test
    fun multipleKnownDevicesPersistWithSingleSelection() {
        val first = RegattaLinkConfiguredDevice(
            stableId = "first",
            deviceAddress = "44:B1:76:48:31:B2",
            deviceName = "Cockpit"
        )
        val second = RegattaLinkConfiguredDevice(
            stableId = "second",
            deviceAddress = "44:B1:76:48:31:CE",
            deviceName = "Dinghy"
        )

        store.upsert(first)
        store.upsert(second)
        assertNull(store.selected())
        assertEquals(setOf(first, second), store.all().toSet())

        assertEquals(true, store.select(second.stableId))
        assertEquals(second, store.selected())
        assertEquals(setOf(first, second), store.all().toSet())
    }

    @Test
    fun upsertingSameStableIdRefreshesAddressWithoutDuplicate() {
        store.upsert(
            RegattaLinkConfiguredDevice(
                stableId = "same",
                deviceAddress = "44:B1:76:48:31:B2",
                deviceName = "Old"
            )
        )
        val refreshed = RegattaLinkConfiguredDevice(
            stableId = "same",
            deviceAddress = "44:B1:76:48:31:CE",
            deviceName = "New"
        )

        store.upsertAndSelect(refreshed)

        assertEquals(listOf(refreshed), store.all())
        assertEquals(refreshed, store.selected())
    }

    @Test
    fun legacySingleDeviceMigratesIntoKnownRegistryAndSelection() {
        context.getSharedPreferences(
            RegattaLinkConfiguredDeviceStore.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .clear()
            .putString("stable_id", "legacy")
            .putString("device_address", "44:B1:76:48:31:B2")
            .putString("device_name", "Legacy RLink")
            .commit()

        store = RegattaLinkConfiguredDeviceStore(context)

        val migrated = RegattaLinkConfiguredDevice(
            stableId = "legacy",
            deviceAddress = "44:B1:76:48:31:B2",
            deviceName = "Legacy RLink"
        )
        assertEquals(migrated, store.selected())
        assertEquals(listOf(migrated), store.all())
    }

    @Test
    fun resetPairingRequirementSurvivesRemovalOfResetDevice() {
        val resetDevice = RegattaLinkConfiguredDevice(
            stableId = "reset",
            deviceAddress = "44:B1:76:48:31:B2",
            deviceName = "Reset me"
        )
        store.save(resetDevice)
        store.markResetRecoveryPending(resetDevice.stableId)

        store.remove(resetDevice.stableId)

        assertNull(store.selected())
        assertTrue(store.requiresNewPairing())
    }

    @Test
    fun resetPairingRequirementForADoesNotBlockSelectedB() {
        val first = RegattaLinkConfiguredDevice(
            stableId = "first",
            deviceAddress = "44:B1:76:48:31:B2",
            deviceName = "First"
        )
        val second = RegattaLinkConfiguredDevice(
            stableId = "second",
            deviceAddress = "44:B1:76:48:31:CE",
            deviceName = "Second"
        )
        store.save(first)
        store.upsert(second)
        store.markResetRecoveryPending(first.stableId)
        store.remove(first.stableId)

        assertTrue(store.select(second.stableId))
        assertFalse(store.requiresNewPairing())
        assertEquals(second, store.selected())
    }

    @Test
    fun replacingConfiguredDeviceReplacesTheCompleteIdentity() {
        store.save(
            RegattaLinkConfiguredDevice(
                stableId = "old",
                deviceAddress = "44:B1:76:48:31:B2",
                deviceName = "old-link"
            )
        )

        val replacement = RegattaLinkConfiguredDevice(
            stableId = "new",
            deviceAddress = "44:B1:76:48:31:CE",
            deviceName = "new-link"
        )
        store.save(replacement)

        assertEquals(replacement, store.load())
    }
    @Test
    fun forgettingOneOfTwoKnownDevicesDoesNotTouchSelectedOne() {
        val selected = RegattaLinkConfiguredDevice("selected", "44:B1:76:48:31:B2", "Same")
        val forgotten = RegattaLinkConfiguredDevice("forgotten", "44:B1:76:48:31:CE", "Same")
        store.save(selected)
        store.upsert(forgotten)

        assertTrue(store.forget(forgotten.stableId))
        assertEquals(listOf(selected), store.all())
        assertEquals(selected, store.selected())
        assertEquals(listOf(selected), RegattaLinkConfiguredDeviceStore(context).all())
        assertFalse(store.forget(forgotten.stableId))
    }

    @Test
    fun forgettingSelectedDeviceClearsSelectionButKeepsOthers() {
        val selected = RegattaLinkConfiguredDevice("selected", "44:B1:76:48:31:B2", "One")
        val other = RegattaLinkConfiguredDevice("other", "44:B1:76:48:31:CE", "Two")
        store.save(selected)
        store.upsert(other)

        assertTrue(store.forget(selected.stableId))

        val reloaded = RegattaLinkConfiguredDeviceStore(context)
        assertNull(reloaded.selected())
        assertEquals(listOf(other), reloaded.all())
        assertFalse(reloaded.allowsLegacyBondBootstrap())
    }

    @Test
    fun forgettingLastKnownDeviceBlocksLegacyBondBootstrapAcrossRestart() {
        val only = RegattaLinkConfiguredDevice("only", "44:B1:76:48:31:B2", "Last")
        store.save(only)
        assertTrue(store.allowsLegacyBondBootstrap())

        assertTrue(store.forget(only.stableId))

        val reloaded = RegattaLinkConfiguredDeviceStore(context)
        assertEquals(emptyList<RegattaLinkConfiguredDevice>(), reloaded.all())
        assertNull(reloaded.selected())
        assertFalse(reloaded.allowsLegacyBondBootstrap())

        // Explicitly pairing the device again still works.
        reloaded.upsertAndSelect(only)
        assertEquals(only, reloaded.selected())
    }

    @Test
    fun failedForgetCommitLeavesKnownDeviceAndSelectionUntouched() {
        val selected = RegattaLinkConfiguredDevice("selected", "44:B1:76:48:31:B2", "One")
        store.save(selected)
        val rejectingStore = RegattaLinkConfiguredDeviceStore(context) { editor ->
            // Simulate a commit reporting failure after SharedPreferences memory changed.
            editor.commit()
            false
        }

        assertFalse(rejectingStore.forget(selected.stableId))
        assertEquals(selected, rejectingStore.selected())
        assertEquals(listOf(selected), rejectingStore.all())
        assertTrue(rejectingStore.allowsLegacyBondBootstrap())
        assertEquals(selected, RegattaLinkConfiguredDeviceStore(context).selected())
    }

}
