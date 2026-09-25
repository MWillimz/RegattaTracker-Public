package de.williserv.regattaclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegattaLinkPgnRefreshPolicyTest {

    @Test
    fun emptyInventoryAutoRefreshRunsOnlyOncePerExpandedView() {
        assertTrue(
            shouldAutoRefreshPgnInventory(
                detailsExpanded = true,
                inventorySupported = true,
                inventoryEmpty = true,
                inventoryLoading = false,
                otaActive = false,
                alreadyRequested = false
            )
        )

        assertFalse(
            shouldAutoRefreshPgnInventory(
                detailsExpanded = true,
                inventorySupported = true,
                inventoryEmpty = true,
                inventoryLoading = false,
                otaActive = false,
                alreadyRequested = true
            )
        )
    }

    @Test
    fun loadingTransitionCannotRestartCompletedAutoRefresh() {
        assertFalse(
            shouldAutoRefreshPgnInventory(
                detailsExpanded = true,
                inventorySupported = true,
                inventoryEmpty = true,
                inventoryLoading = true,
                otaActive = false,
                alreadyRequested = true
            )
        )

        assertFalse(
            shouldAutoRefreshPgnInventory(
                detailsExpanded = true,
                inventorySupported = true,
                inventoryEmpty = true,
                inventoryLoading = false,
                otaActive = false,
                alreadyRequested = true
            )
        )
    }

    @Test
    fun autoRefreshIsBlockedWhenCollapsedUnsupportedPopulatedOrInOta() {
        val base = arrayOf(
            false to shouldAutoRefreshPgnInventory(
                detailsExpanded = false,
                inventorySupported = true,
                inventoryEmpty = true,
                inventoryLoading = false,
                otaActive = false,
                alreadyRequested = false
            ),
            false to shouldAutoRefreshPgnInventory(
                detailsExpanded = true,
                inventorySupported = false,
                inventoryEmpty = true,
                inventoryLoading = false,
                otaActive = false,
                alreadyRequested = false
            ),
            false to shouldAutoRefreshPgnInventory(
                detailsExpanded = true,
                inventorySupported = true,
                inventoryEmpty = false,
                inventoryLoading = false,
                otaActive = false,
                alreadyRequested = false
            ),
            false to shouldAutoRefreshPgnInventory(
                detailsExpanded = true,
                inventorySupported = true,
                inventoryEmpty = true,
                inventoryLoading = false,
                otaActive = true,
                alreadyRequested = false
            )
        )

        base.forEach { (_, result) -> assertFalse(result) }
    }
}
