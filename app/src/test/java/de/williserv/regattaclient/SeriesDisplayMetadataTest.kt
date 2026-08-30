package de.williserv.regattaclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SeriesDisplayMetadataTest {

    @Test
    fun missingSeriesMetadataKeepsStandaloneHeaderUnchanged() {
        val parsed = parseSeriesDisplayMetadata(JSONObject().put("event_name", "standalone-event"))

        assertEquals(SeriesDisplayMetadata(), parsed)
        assertEquals(
            listOf("Standalone Event"),
            buildEventHeaderLines(
                raceEvent = "Standalone Event",
                raceDataReady = true,
                seriesDisplayMetadata = parsed
            )
        )
        assertEquals("", buildEventSummarySeriesLine(parsed))
    }

    @Test
    fun seriesMetadataUsesServerRunNameAndNeutralOrder() {
        val series = JSONObject()
            .put("name", "Elbe Cup")
            .put("run_name", "Langstrecke")
            .put("occurrence_no", 2)
            .put("planned_race_count", 5)
        val parsed = parseSeriesDisplayMetadata(JSONObject().put("series", series))

        assertEquals("Langstrecke", parsed.runName)
        assertEquals(2, parsed.occurrenceNo)
        assertEquals(5, parsed.plannedRaceCount)
        assertEquals("2 of 5", parsed.orderText())

        val header = buildEventHeaderLines(
            raceEvent = "Elbe Cup",
            raceDataReady = true,
            seriesDisplayMetadata = parsed
        )
        assertEquals(listOf("Elbe Cup"), header)
        assertEquals("Langstrecke · 2 of 5", buildEventSummarySeriesLine(parsed))
        assertFalse(buildEventSummarySeriesLine(parsed).contains("Lauf", ignoreCase = true))
    }

    @Test
    fun serverRunNameIsNotRewrittenByClient() {
        val serverRunName = "  Langstrecke  "
        val parsed = parseSeriesDisplayMetadata(
            JSONObject().put("series", JSONObject().put("run_name", serverRunName))
        )

        assertEquals(serverRunName, parsed.runName)
        assertEquals(serverRunName, buildEventSummarySeriesLine(parsed))
    }

    @Test
    fun missingRunNameDoesNotInventOne() {
        val series = JSONObject()
            .put("run_name", JSONObject.NULL)
            .put("occurrence_no", 2)
            .put("planned_race_count", 5)
        val parsed = parseSeriesDisplayMetadata(JSONObject().put("series", series))

        assertEquals("", parsed.runName)
        assertEquals(
            listOf("Elbe Cup"),
            buildEventHeaderLines(
                raceEvent = "Elbe Cup",
                raceDataReady = true,
                seriesDisplayMetadata = parsed
            )
        )
        assertEquals("2 of 5", buildEventSummarySeriesLine(parsed))
    }

    @Test
    fun incompleteOrderIsNotDisplayed() {
        val parsed = SeriesDisplayMetadata(
            runName = "Langstrecke",
            occurrenceNo = 2,
            plannedRaceCount = null
        )

        assertEquals("", parsed.orderText())
        assertEquals(
            listOf("Elbe Cup"),
            buildEventHeaderLines(
                raceEvent = "Elbe Cup",
                raceDataReady = true,
                seriesDisplayMetadata = parsed
            )
        )
        assertEquals("Langstrecke", buildEventSummarySeriesLine(parsed))
    }

    @Test
    fun eventHeaderRemainsHiddenUntilRaceDataIsReady() {
        assertEquals(
            emptyList<String>(),
            buildEventHeaderLines(
                raceEvent = "Elbe Cup",
                raceDataReady = false,
                seriesDisplayMetadata = SeriesDisplayMetadata(
                    runName = "Langstrecke",
                    occurrenceNo = 2,
                    plannedRaceCount = 5
                )
            )
        )
    }
}
