package de.williserv.regattaclient

import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RegattaLinkUiMessageTest {

    @Test
    fun everyTypedMessageMapsToAStringResource() {
        RegattaLinkUiMessage.entries.forEach { message ->
            assertNotEquals(
                "Missing string mapping for $message",
                0,
                regattaLinkUiMessageResource(message)
            )
        }
    }


}
