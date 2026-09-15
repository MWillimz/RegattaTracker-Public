package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NormalApiGetTest {

    @Test
    fun buildNormalApiGetUrl_keepsEventInQueryAndNoSecret() {
        val url = buildNormalApiGetUrl(
            baseUrl = "https://raceoffice.example.org/",
            path = "/event",
            eventName = "Wednesday Race 1"
        )

        assertEquals(
            "https://raceoffice.example.org/event?event_name=Wednesday+Race+1",
            url
        )
        assertFalse(url.contains("shared_secret"))
        assertFalse(url.contains("secret"))
    }

    @Test
    fun buildNormalApiGetUrl_normalizesPathWithoutChangingEndpoint() {
        assertEquals(
            "https://raceoffice.example.org/event-results?event_name=Race%2FFinal",
            buildNormalApiGetUrl(
                baseUrl = "https://raceoffice.example.org",
                path = "event-results",
                eventName = "Race/Final"
            )
        )
    }
}
