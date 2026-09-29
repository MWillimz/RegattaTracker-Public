package de.williserv.regattaclient

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
