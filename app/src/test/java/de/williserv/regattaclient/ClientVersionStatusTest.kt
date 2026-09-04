package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientVersionStatusTest {

    @Test
    fun currentClientBuildIdentity_usesAndroidBuildConfig() {
        val identity = currentClientBuildIdentity()

        assertEquals(BuildConfig.VERSION_CODE, identity.versionCode)
        assertEquals(BuildConfig.VERSION_NAME, identity.buildId)
    }

    @Test
    fun devDebug_isNeverComparedAgainstReleaseVersions() {
        val status = evaluate(
            installed = 42,
            recommended = 2450,
            minimum = 2400,
            production = 2450,
            direct = 2535
        )

        assertEquals(ClientVersionPolicyState.DEV_DEBUG, status.policyState)
        assertFalse(status.newerProductionAvailable)
        assertFalse(status.newerDirectDownloadAvailable)
    }

    @Test
    fun currentRelease_staysCurrentAtOrAboveRecommended() {
        val status = evaluate(
            installed = 2535,
            recommended = 2450,
            minimum = null,
            production = 2450,
            direct = 2535
        )

        assertEquals(ClientVersionPolicyState.CURRENT, status.policyState)
        assertFalse(status.newerProductionAvailable)
        assertFalse(status.newerDirectDownloadAvailable)
    }

    @Test
    fun installedBelowRecommended_requestsSoftUpdate() {
        val status = evaluate(
            installed = 2322,
            recommended = 2450,
            minimum = null,
            production = 2450,
            direct = 2535
        )

        assertEquals(ClientVersionPolicyState.UPDATE_RECOMMENDED, status.policyState)
        assertTrue(status.newerProductionAvailable)
        assertTrue(status.newerDirectDownloadAvailable)
    }

    @Test
    fun hardMinimum_hasPriorityOverRecommended() {
        val status = evaluate(
            installed = 2322,
            recommended = 2450,
            minimum = 2400,
            production = 2450,
            direct = 2535
        )

        assertEquals(ClientVersionPolicyState.UPDATE_REQUIRED, status.policyState)
    }

    @Test
    fun equalRecommendedAndAboveMinimum_isCurrentWithNewerDirectRelease() {
        val status = evaluate(
            installed = 2450,
            recommended = 2450,
            minimum = 2400,
            production = 2450,
            direct = 2535
        )

        assertEquals(ClientVersionPolicyState.CURRENT, status.policyState)
        assertFalse(status.newerProductionAvailable)
        assertTrue(status.newerDirectDownloadAvailable)
    }

    @Test
    fun directReleaseCanBeNewerWithoutChangingPolicy() {
        val status = evaluate(
            installed = 2500,
            recommended = null,
            minimum = null,
            production = 2450,
            direct = 2535
        )

        assertEquals(ClientVersionPolicyState.CURRENT, status.policyState)
        assertFalse(status.newerProductionAvailable)
        assertTrue(status.newerDirectDownloadAvailable)
    }

    @Test
    fun availableReleasesDoNotCreatePolicyWithoutServerThreshold() {
        val status = evaluate(
            installed = 2322,
            recommended = null,
            minimum = null,
            production = 2450,
            direct = 2535
        )

        assertEquals(ClientVersionPolicyState.CURRENT, status.policyState)
        assertTrue(status.newerProductionAvailable)
        assertTrue(status.newerDirectDownloadAvailable)
    }

    @Test
    fun installedExactlyAtMinimum_isNotHardBlocked() {
        val status = evaluate(
            installed = 2400,
            recommended = null,
            minimum = 2400,
            production = null,
            direct = null
        )

        assertEquals(ClientVersionPolicyState.CURRENT, status.policyState)
    }

    @Test
    fun installedExactlyAtRecommended_isNotRecommended() {
        val status = evaluate(
            installed = 2450,
            recommended = 2450,
            minimum = null,
            production = null,
            direct = null
        )

        assertEquals(ClientVersionPolicyState.CURRENT, status.policyState)
    }

    @Test
    fun missingReleaseMetadata_producesNoAvailabilityFlags() {
        val status = evaluate(
            installed = 2450,
            recommended = null,
            minimum = null,
            production = null,
            direct = null
        )

        assertEquals(ClientVersionPolicyState.CURRENT, status.policyState)
        assertFalse(status.newerProductionAvailable)
        assertFalse(status.newerDirectDownloadAvailable)
    }

    private fun evaluate(
        installed: Int,
        recommended: Int?,
        minimum: Int?,
        production: Int?,
        direct: Int?
    ): ClientVersionStatus = evaluateClientVersionStatus(
        client = ClientBuildIdentity(
            versionCode = installed,
            buildId = "test-build-$installed"
        ),
        serverMetadata = serverMetadata(
            recommended = recommended,
            minimum = minimum,
            production = production,
            direct = direct
        )
    )

    private fun serverMetadata(
        recommended: Int?,
        minimum: Int?,
        production: Int?,
        direct: Int?
    ): ServerMetadata = ServerMetadata(
        operator = null,
        publicUrl = null,
        contactEmail = null,
        serverBuildId = "server-build",
        serverBuildNumber = 1000,
        serverBuildType = "release",
        recommendedClientVersionCode = recommended,
        minClientVersionCode = minimum,
        productionRelease = production?.let {
            ProductionReleaseMetadata(
                versionCode = it,
                versionName = "production-$it",
                sourceSha = "a".repeat(40),
                recordedAt = null
            )
        },
        directDownloadRelease = direct?.let {
            DirectDownloadReleaseMetadata(
                versionCode = it,
                versionName = "staging-$it",
                sourceSha = "b".repeat(40),
                uploadedAt = null,
                downloadUrl = "/static/downloads/regatta-app.apk"
            )
        }
    )
}
