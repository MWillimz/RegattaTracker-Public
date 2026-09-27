package de.williserv.regattaclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegattaLinkGattSchemaTest {

    @Test
    fun persistedGenerationStillRequiresOneRealGattProofPerProcess() {
        assertTrue(
            shouldValidateRegattaLinkGattLayout(
                schemaAlreadyVerifiedThisProcess = false,
                acceptReportedVersion = false,
                forcedRediscoveryPendingValidation = false
            )
        )
    }

    @Test
    fun provenGenerationSkipsRepeatedProbeWithinSameProcess() {
        assertFalse(
            shouldValidateRegattaLinkGattLayout(
                schemaAlreadyVerifiedThisProcess = true,
                acceptReportedVersion = false,
                forcedRediscoveryPendingValidation = false
            )
        )
    }

    @Test
    fun localCacheRefreshAlwaysRequiresFreshGattProof() {
        assertTrue(
            shouldValidateRegattaLinkGattLayout(
                schemaAlreadyVerifiedThisProcess = true,
                acceptReportedVersion = false,
                forcedRediscoveryPendingValidation = true
            )
        )
    }

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
    fun schemaNineToTenRequiresGenericReconciliation() {
        val decision = regattaLinkGattSchemaDecision(
            reportedVersion = 10,
            acceptedVersion = 9,
            connectionStartedBonded = true,
            serviceChangedObserved = false,
            serviceChangedRediscoveryCompleted = false
        )

        assertTrue(decision.waitForRediscovery)
        assertTrue(decision.requestServiceChanged)
        assertFalse(decision.acceptReportedVersion)
    }

    @Test
    fun schemaTenToElevenRequiresGenericReconciliation() {
        val decision = regattaLinkGattSchemaDecision(
            reportedVersion = 11,
            acceptedVersion = 10,
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
    fun legacyFirmwareWithoutProcessLocalAcceptanceMustReconcileAgain() {
        val decision = regattaLinkGattSchemaDecision(
            reportedVersion = 0,
            acceptedVersion = null,
            connectionStartedBonded = true,
            serviceChangedObserved = false,
            serviceChangedRediscoveryCompleted = false
        )

        assertTrue(decision.waitForRediscovery)
        assertFalse(decision.requestServiceChanged)
        assertFalse(decision.acceptReportedVersion)
    }

    @Test
    fun reconciledLegacyFirmwareDoesNotBlockFreshOtaReconnect() {
        val decision = regattaLinkGattSchemaDecision(
            reportedVersion = 0,
            acceptedVersion = 0,
            connectionStartedBonded = true,
            serviceChangedObserved = false,
            serviceChangedRediscoveryCompleted = false
        )

        assertFalse(decision.waitForRediscovery)
        assertFalse(decision.requestServiceChanged)
        assertFalse(decision.acceptReportedVersion)
    }

    @Test
    fun legacyRediscoveryIsAcceptedForLaterReconnects() {
        val decision = regattaLinkGattSchemaDecision(
            reportedVersion = 0,
            acceptedVersion = null,
            connectionStartedBonded = true,
            serviceChangedObserved = true,
            serviceChangedRediscoveryCompleted = true
        )

        assertFalse(decision.waitForRediscovery)
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
