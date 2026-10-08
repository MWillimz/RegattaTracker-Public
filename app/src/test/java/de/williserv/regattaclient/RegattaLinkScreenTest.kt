package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegattaLinkScreenTest {

    @Test
    fun bluetoothDevicesOpenRequestsStatusOnlyOnceWhenControlIsReady() {
        assertTrue(
            shouldRefreshCalypsoStatusOnOpen(
                bluetoothDevicesOpen = true,
                connected = true,
                deviceControlSupported = true,
                deviceControlEnabled = true,
                alreadyRequested = false
            )
        )
        assertFalse(
            shouldRefreshCalypsoStatusOnOpen(
                bluetoothDevicesOpen = true,
                connected = true,
                deviceControlSupported = true,
                deviceControlEnabled = true,
                alreadyRequested = true
            )
        )
        assertFalse(
            shouldRefreshCalypsoStatusOnOpen(
                bluetoothDevicesOpen = false,
                connected = true,
                deviceControlSupported = true,
                deviceControlEnabled = true,
                alreadyRequested = false
            )
        )
    }
}
