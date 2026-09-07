package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Test

class ClientVersionNoticeTest {

    @Test
    fun current_hasNoNotice() {
        assertEquals(
            ClientVersionNotice.NONE,
            clientVersionNotice(
                persistedHardBlock = false,
                status = status(ClientVersionPolicyState.CURRENT)
            )
        )
    }

    @Test
    fun updateRecommended_showsSoftNotice() {
        assertEquals(
            ClientVersionNotice.UPDATE_RECOMMENDED,
            clientVersionNotice(
                persistedHardBlock = false,
                status = status(ClientVersionPolicyState.UPDATE_RECOMMENDED)
            )
        )
    }

    @Test
    fun updateRequired_showsHardNotice() {
        assertEquals(
            ClientVersionNotice.UPDATE_REQUIRED,
            clientVersionNotice(
                persistedHardBlock = false,
                status = status(ClientVersionPolicyState.UPDATE_REQUIRED)
            )
        )
    }

    @Test
    fun devDebug_hasNoSoftNotice() {
        assertEquals(
            ClientVersionNotice.NONE,
            clientVersionNotice(
                persistedHardBlock = false,
                status = status(ClientVersionPolicyState.DEV_DEBUG)
            )
        )
    }

    @Test
    fun persistedHardBlock_hasPriorityOverSoftRecommendation() {
        assertEquals(
            ClientVersionNotice.UPDATE_REQUIRED,
            clientVersionNotice(
                persistedHardBlock = true,
                status = status(ClientVersionPolicyState.UPDATE_RECOMMENDED)
            )
        )
    }

    private fun status(policyState: ClientVersionPolicyState) = ClientVersionStatus(
        policyState = policyState,
        installedVersionCode = 100,
        recommendedVersionCode = null,
        minimumVersionCode = null,
        productionVersionCode = null,
        directDownloadVersionCode = null,
        newerProductionAvailable = false,
        newerDirectDownloadAvailable = false
    )
}
