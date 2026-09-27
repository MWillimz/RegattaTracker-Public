package de.williserv.regattaclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegattaLinkGattSchemaTest {

    @Test
    fun acceptedSchemaDoesNotInvalidateEveryReconnect() {
        val decision = regattaLinkGattSchemaDecision(
            reportedVersion = 9,
            acceptedVersion = 9,
            connectionStartedBonded = true,
            serviceChangedObserved = false,
            serviceChangedRediscoveryCompleted = false
        )

        assertFalse(decision.waitForRediscovery)
        assertFalse(decision.requestServiceChanged)
        assertFalse(decision.acceptReportedVersion)
    }

    @Test
    fun changedSchemaRequestsServiceChangedBeforeMovableHandles() {
        val decision = regattaLinkGattSchemaDecision(
            reportedVersion = 9,
            acceptedVersion = 8,
            connectionStartedBonded = true,
            serviceChangedObserved = false,
            serviceChangedRediscoveryCompleted = false
        )

        assertTrue(decision.waitForRediscovery)
        assertTrue(decision.requestServiceChanged)
        assertFalse(decision.acceptReportedVersion)
    }

    @Test
    fun callbackAloneStillWaitsForRediscovery() {
        val decision = regattaLinkGattSchemaDecision(
            reportedVersion = 9,
            acceptedVersion = 8,
            connectionStartedBonded = true,
            serviceChangedObserved = true,
            serviceChangedRediscoveryCompleted = false
        )

        assertTrue(decision.waitForRediscovery)
        assertFalse(decision.requestServiceChanged)
    }

    @Test
    fun completedRediscoveryAcceptsChangedSchema() {
        val decision = regattaLinkGattSchemaDecision(
            reportedVersion = 9,
            acceptedVersion = 8,
            connectionStartedBonded = true,
            serviceChangedObserved = true,
            serviceChangedRediscoveryCompleted = true
        )

        assertFalse(decision.waitForRediscovery)
        assertTrue(decision.acceptReportedVersion)
    }

    @Test
    fun freshPairingAcceptsCurrentSchemaWithoutInvalidation() {
        val decision = regattaLinkGattSchemaDecision(
            reportedVersion = 9,
            acceptedVersion = null,
            connectionStartedBonded = false,
            serviceChangedObserved = false,
            serviceChangedRediscoveryCompleted = false
        )

        assertFalse(decision.waitForRediscovery)
        assertFalse(decision.requestServiceChanged)
        assertTrue(decision.acceptReportedVersion)
    }

    @Test
    fun legacyBondedFirmwareWaitsForItsMigrationServiceChanged() {
        val decision = regattaLinkGattSchemaDecision(
            reportedVersion = 0,
            acceptedVersion = null,
            connectionStartedBonded = true,
            serviceChangedObserved = false,
            serviceChangedRediscoveryCompleted = false
        )

        assertTrue(decision.waitForRediscovery)
        assertFalse(decision.requestServiceChanged)
    }
}
