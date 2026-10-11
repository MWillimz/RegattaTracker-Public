package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Test

class EventHomeNavigationTest {
    @Test
    fun confirmedBoatSetupWithoutEventOpensScannerImmediately() {
        assertEquals(
            Screen.QR_SCANNER,
            homeEventDestination(boatSetupConfirmed = true, eventAccessLoaded = false)
        )
    }

    @Test
    fun alreadyLoadedEventAlwaysOpensEventScreenEvenBeforeDataFetch() {
        assertEquals(
            Screen.RACE,
            homeEventDestination(boatSetupConfirmed = true, eventAccessLoaded = true)
        )
    }

    @Test
    fun unconfirmedBoatSetupCannotEnterEventOrScannerFromHome() {
        assertEquals(
            Screen.HOME,
            homeEventDestination(boatSetupConfirmed = false, eventAccessLoaded = false)
        )
        assertEquals(
            Screen.HOME,
            homeEventDestination(boatSetupConfirmed = false, eventAccessLoaded = true)
        )
    }

    @Test
    fun scannerBackReturnsHomeIfNoEventWasImported() {
        assertEquals(Screen.HOME, qrScannerBackDestination(eventAccessLoaded = false))
    }

    @Test
    fun scannerBackReturnsEventWhenAnEventIsLoaded() {
        assertEquals(Screen.RACE, qrScannerBackDestination(eventAccessLoaded = true))
    }
}
