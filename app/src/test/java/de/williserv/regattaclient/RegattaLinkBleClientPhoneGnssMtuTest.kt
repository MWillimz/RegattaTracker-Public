package de.williserv.regattaclient

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Looper
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RegattaLinkBleClientPhoneGnssMtuTest {

    private lateinit var context: Context
    private lateinit var client: RegattaLinkBleClient
    private val states = mutableListOf<RegattaLinkClientState>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.BLUETOOTH_CONNECT
        )
        states.clear()
        client = RegattaLinkBleClient(
            context = context,
            onStateChanged = { states += it }
        )
    }

    @After
    fun tearDown() {
        client.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun mtuCallbackPublishesPhoneGnssReadinessOnlyAtRequiredMtu() {
        val (gatt, callback) = installConnectedGatt()

        callback.onMtuChanged(
            gatt,
            REGATTALINK_PHONE_GNSS_REQUIRED_MTU - 1,
            BluetoothGatt.GATT_SUCCESS
        )

        assertEquals(REGATTALINK_PHONE_GNSS_REQUIRED_MTU - 1, client.mtu)
        assertFalse(
            getField<RegattaLinkClientState>(client, "lastState")
                .phoneGnssTransportReady
        )

        callback.onMtuChanged(
            gatt,
            REGATTALINK_PHONE_GNSS_REQUIRED_MTU,
            BluetoothGatt.GATT_SUCCESS
        )

        assertEquals(REGATTALINK_PHONE_GNSS_REQUIRED_MTU, client.mtu)
        assertTrue(
            getField<RegattaLinkClientState>(client, "lastState")
                .phoneGnssTransportReady
        )

        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(states.last().phoneGnssTransportReady)
    }

    @Test
    fun failedMtuCallbackKeepsDefaultMtuAndDoesNotPublishReadiness() {
        val (gatt, callback) = installConnectedGatt()

        callback.onMtuChanged(
            gatt,
            247,
            BluetoothGatt.GATT_FAILURE
        )

        assertEquals(23, client.mtu)
        assertFalse(
            getField<RegattaLinkClientState>(client, "lastState")
                .phoneGnssTransportReady
        )
    }

    @Test
    fun staleMtuCallbackFromPreviousGattCannotReadyCurrentConnection() {
        val (oldGatt, callback) = installConnectedGatt()
        val currentGatt = newGatt()

        setField(client, "gatt", currentGatt)
        setField(client, "connected", true)
        setField(client, "establishedConnection", true)
        setField(
            client,
            "lastState",
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED,
                deviceAddress = currentGatt.device.address
            )
        )

        callback.onMtuChanged(
            oldGatt,
            247,
            BluetoothGatt.GATT_SUCCESS
        )

        assertEquals(23, client.mtu)
        assertFalse(
            getField<RegattaLinkClientState>(client, "lastState")
                .phoneGnssTransportReady
        )
    }

    @Test
    fun disconnectResetsMtuRequestPendingSampleAndReadiness() {
        val (gatt, callback) = installConnectedGatt()

        callback.onMtuChanged(
            gatt,
            83,
            BluetoothGatt.GATT_SUCCESS
        )
        assertTrue(
            getField<RegattaLinkClientState>(client, "lastState")
                .phoneGnssTransportReady
        )

        val requestAttempted =
            getField<AtomicBoolean>(client, "mtuRequestAttempted")
        requestAttempted.set(true)

        val pending =
            getField<AtomicReference<RegattaLinkPhoneGnssSample?>>(
                client,
                "phoneGnssPending"
            )
        pending.set(
            RegattaLinkPhoneGnssSample(
                observationElapsedRealtimeNanos = 1_000_000_000L,
                utcTimeMs = 1_700_000_000_000L,
                latitudeDeg = 53.0,
                longitudeDeg = 10.0
            )
        )

        callback.onConnectionStateChange(
            gatt,
            BluetoothGatt.GATT_SUCCESS,
            BluetoothProfile.STATE_DISCONNECTED
        )

        assertEquals(23, client.mtu)
        assertFalse(requestAttempted.get())
        assertNull(pending.get())
        assertFalse(
            getField<RegattaLinkClientState>(client, "lastState")
                .phoneGnssTransportReady
        )
    }

    private fun installConnectedGatt(): Pair<BluetoothGatt, BluetoothGattCallback> {
        val gatt = newGatt()
        setField(client, "gatt", gatt)
        setField(client, "connected", true)
        setField(client, "establishedConnection", true)
        setField(
            client,
            "lastState",
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED,
                deviceAddress = gatt.device.address
            )
        )
        return gatt to getField(client, "gattCallback")
    }

    private fun newGatt(): BluetoothGatt {
        val adapter = requireNotNull(BluetoothAdapter.getDefaultAdapter())
        val device = adapter.getRemoteDevice(TEST_ADDRESS)
        return requireNotNull(
            device.connectGatt(
                context,
                false,
                object : BluetoothGattCallback() {}
            )
        )
    }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).let { field ->
            field.isAccessible = true
            field.get(target) as T
        }

    private companion object {
        const val TEST_ADDRESS = "12:34:56:78:9A:BC"
    }
}
