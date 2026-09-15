package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Test

class ClientVersionStatusTest {

    @Test
    fun currentClientBuildIdentity_usesAndroidBuildConfig() {
        val identity = currentClientBuildIdentity()

        assertEquals(BuildConfig.VERSION_CODE, identity.versionCode)
        assertEquals(BuildConfig.VERSION_NAME, identity.buildId)
    }

    @Test
    fun devDebug_isNeverComparedAgainstReleaseThresholds() {
        val status = evaluate(
            installed = 42,
            recommended = 2450,
            minimum = 2400
        )

        assertEquals(ClientVersionPolicyState.DEV_DEBUG, status.policyState)
        assertEquals(42, status.installedVersionCode)
        assertEquals(2450, status.recommendedVersionCode)
        assertEquals(2400, status.minimumVersionCode)
    }

    @Test
    fun currentRelease_staysCurrentAtOrAboveRecommended() {
        val status = evaluate(
            installed = 2535,
            recommended = 2450,
            minimum = null
        )

        assertEquals(ClientVersionPolicyState.CURRENT, status.policyState)
    }

    @Test
    fun installedBelowRecommended_requestsSoftUpdate() {
        val status = evaluate(
            installed = 2322,
            recommended = 2450,
            minimum = null
        )

        assertEquals(ClientVersionPolicyState.UPDATE_RECOMMENDED, status.policyState)
    }

    @Test
    fun hardMinimum_hasPriorityOverRecommended() {
        val status = evaluate(
            installed = 2322,
            recommended = 2450,
            minimum = 2400
        )

        assertEquals(ClientVersionPolicyState.UPDATE_REQUIRED, status.policyState)
    }

    @Test
    fun installedExactlyAtMinimum_isNotHardBlocked() {
        val status = evaluate(
            installed = 2400,
            recommended = null,
            minimum = 2400
        )

        assertEquals(ClientVersionPolicyState.CURRENT, status.policyState)
    }

    @Test
    fun installedExactlyAtRecommended_isNotRecommended() {
        val status = evaluate(
            installed = 2450,
            recommended = 2450,
            minimum = null
        )

        assertEquals(ClientVersionPolicyState.CURRENT, status.policyState)
    }

    @Test
    fun missingPolicyMetadata_staysCurrent() {
        val status = evaluate(
            installed = 2450,
            recommended = null,
            minimum = null
        )

        assertEquals(ClientVersionPolicyState.CURRENT, status.policyState)
        assertEquals(2450, status.installedVersionCode)
        assertEquals(null, status.recommendedVersionCode)
        assertEquals(null, status.minimumVersionCode)
    }

    private fun evaluate(
        installed: Int,
        recommended: Int?,
        minimum: Int?
    ): ClientVersionStatus = evaluateClientVersionStatus(
        client = ClientBuildIdentity(
            versionCode = installed,
            buildId = "test-build-$installed"
        ),
        serverMetadata = ServerMetadata(
            operator = null,
            publicUrl = null,
            contactEmail = null,
            serverBuildId = "server-build",
            recommendedClientVersionCode = recommended,
            minClientVersionCode = minimum
        )
    )
}
