package de.williserv.regattaclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceRegistrationRequestGateTest {
    @Test
    fun sameEventQrReloadInvalidatesInFlightRegistration() {
        val gate = RaceRegistrationRequestGate()
        val requestForFirstImport = gate.begin()
        assertTrue(gate.isCurrent(requestForFirstImport))

        // The same QR credentials can be imported again. Credentials and
        // boat setup are identical, but the original callback is obsolete.
        gate.invalidate()
        assertFalse(gate.isCurrent(requestForFirstImport))

        val requestForSecondImport = gate.begin()
        assertFalse(gate.isCurrent(requestForFirstImport))
        assertTrue(gate.isCurrent(requestForSecondImport))
    }

    @Test
    fun secondRegistrationAttemptSupersedesEarlierSuccessOrError() {
        val gate = RaceRegistrationRequestGate()
        val first = gate.begin()
        val second = gate.begin()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
        gate.invalidate()
        assertFalse(gate.isCurrent(second))
    }
}
