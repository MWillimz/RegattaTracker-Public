package de.williserv.regattaclient

/**
 * A valid event access context is sufficient to open the existing Event screen;
 * fetching race data or registering is not a prerequisite.
 */
internal fun homeEventDestination(
    boatSetupConfirmed: Boolean,
    eventAccessLoaded: Boolean
): Screen = when {
    !boatSetupConfirmed -> Screen.HOME
    eventAccessLoaded -> Screen.RACE
    else -> Screen.QR_SCANNER
}

internal fun qrScannerBackDestination(eventAccessLoaded: Boolean): Screen =
    if (eventAccessLoaded) Screen.RACE else Screen.HOME
