package de.williserv.regattaclient

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReplayMapBackgroundTest {

    @Test
    fun storedViewportJsonUsesStrictPublishedMapContract() {
        val parsed = parseCourseMapViewportJson(
            """{"projection":"web_mercator","zoom":12,"left_px":100.5,"top_px":200.5,"width_px":800,"height_px":600,"generation_id":"g42"}"""
        )

        assertEquals("web_mercator", parsed?.projection)
        assertEquals(12, parsed?.zoom)
        assertEquals(100.5, parsed?.leftPx ?: Double.NaN, 0.000001)
        assertEquals(200.5, parsed?.topPx ?: Double.NaN, 0.000001)
        assertEquals(800, parsed?.widthPx)
        assertEquals(600, parsed?.heightPx)
        assertEquals("g42", parsed?.generationId)

        assertNull(
            parseCourseMapViewportJson(
                """{"projection":"other","zoom":12,"left_px":100.5,"top_px":200.5,"width_px":800,"height_px":600,"generation_id":"g42"}"""
            )
        )
        assertNull(parseCourseMapViewportJson(""))
    }

    @Test
    fun manualSessionHasNoMapCandidates() {
        val session = session(mode = "manual", accessContextId = null)

        assertEquals(
            emptyList<ReplayMapCandidate>(),
            replayMapCandidates(session, emptyList())
        )
    }

    @Test
    fun repeatedSamplesOfSameRaceContextProduceOneCandidate() {
        val session = session()
        val samples = listOf(
            sample(id = 1, raceContextId = 10, event = "Run 1", generation = "g1"),
            sample(id = 2, raceContextId = 10, event = "Run 1", generation = "g1")
        )

        val candidates = replayMapCandidates(session, samples)

        assertEquals(1, candidates.size)
        assertEquals(10L, candidates.single().key.raceContextId)
        assertEquals("Run 1", candidates.single().key.resolvedEventName)
        assertEquals("g1", candidates.single().key.generationId)
    }

    @Test
    fun multipleRaceContextsRemainDistinctAndSelectionFollowsSample() {
        val session = session()
        val samples = listOf(
            sample(id = 1, raceContextId = 10, event = "Run 1", generation = "g1"),
            sample(id = 2, raceContextId = 20, event = "Run 2", generation = "g2")
        )
        val candidates = replayMapCandidates(session, samples)
        assertEquals(2, candidates.size)

        val backgrounds = candidates.associate { candidate ->
            candidate.key to ReplayMapBackground(
                candidate = candidate,
                bitmap = Bitmap.createBitmap(
                    candidate.viewport.widthPx,
                    candidate.viewport.heightPx,
                    Bitmap.Config.ARGB_8888
                )
            )
        }

        assertEquals(
            candidates[0].key,
            replayMapBackgroundForSample(samples[0], backgrounds)?.candidate?.key
        )
        assertEquals(
            candidates[1].key,
            replayMapBackgroundForSample(samples[1], backgrounds)?.candidate?.key
        )
    }

    @Test
    fun missingRaceContextDoesNotBorrowDifferentContextMap() {
        val session = session()
        val known = sample(id = 1, raceContextId = 10, event = "Run 1", generation = "g1")
        val missing = sample(id = 2, raceContextId = 20, event = "Run 2", generation = "g2")
        val candidate = replayMapCandidates(session, listOf(known)).single()
        val background = ReplayMapBackground(
            candidate = candidate,
            bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        )

        assertNull(
            replayMapBackgroundForSample(
                missing,
                mapOf(candidate.key to background)
            )
        )
    }

    @Test
    fun exactGenerationMustMatchEvenWhenRaceContextIdMatches() {
        val requested = sample(
            id = 2,
            raceContextId = 10,
            event = "Run 1",
            generation = "g2"
        )
        val oldCandidate = replayMapCandidates(
            session = session(),
            samples = listOf(
                sample(
                    id = 1,
                    raceContextId = 10,
                    event = "Run 1",
                    generation = "g1"
                )
            )
        ).single()
        val oldBackground = ReplayMapBackground(
            candidate = oldCandidate,
            bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        )

        assertNull(
            replayMapBackgroundForSample(
                requested,
                mapOf(oldCandidate.key to oldBackground)
            )
        )
    }

    @Test
    fun contextfulSampleWithoutViewportDoesNotUseSessionFallback() {
        val session = session(
            resolvedEventName = "Legacy Run",
            courseMapViewportJson = viewportJson("legacy")
        )
        val sampleWithContext = sample(
            id = 1,
            raceContextId = 10,
            event = "Legacy Run",
            generation = null
        )

        assertEquals(
            emptyList<ReplayMapCandidate>(),
            replayMapCandidates(session, listOf(sampleWithContext))
        )
    }

    @Test
    fun legacySessionMetadataProvidesSingleFallbackCandidate() {
        val session = session(
            resolvedEventName = "Legacy Run",
            courseMapViewportJson = viewportJson("legacy")
        )

        val candidate = replayMapCandidates(session, emptyList()).single()

        assertEquals(null, candidate.key.raceContextId)
        assertEquals("Legacy Run", candidate.key.resolvedEventName)
        assertEquals("legacy", candidate.key.generationId)
    }

    @Test
    fun legacySampleWithoutContextCanUseSingleLoadedBackground() {
        val session = session(
            resolvedEventName = "Legacy Run",
            courseMapViewportJson = viewportJson("legacy")
        )
        val candidate = replayMapCandidates(session, emptyList()).single()
        val background = ReplayMapBackground(
            candidate = candidate,
            bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        )
        val legacySample = sample(
            id = 1,
            raceContextId = null,
            event = null,
            generation = null
        )

        assertSame(
            background,
            replayMapBackgroundForSample(
                legacySample,
                mapOf(candidate.key to background)
            )
        )
    }

    private fun session(
        mode: String = "race",
        accessContextId: Long? = 5L,
        resolvedEventName: String? = null,
        courseMapViewportJson: String? = null
    ): TrackingSession = TrackingSession(
        id = 1L,
        startedAt = 1_000L,
        endedAt = 2_000L,
        mode = mode,
        accessContextId = accessContextId,
        displayName = "Session",
        resolvedEventName = resolvedEventName,
        courseMapViewportJson = courseMapViewportJson
    )

    private fun sample(
        id: Long,
        raceContextId: Long?,
        event: String?,
        generation: String?
    ): SessionTrackingSample = SessionTrackingSample(
        localId = id,
        timestamp = "2026-10-04T12:00:00",
        utcOffsetMinutes = 120,
        lat = 54.0,
        lon = 10.0,
        accuracy = 5f,
        cog = 90f,
        sog = 3f,
        raceContextId = raceContextId,
        resolvedEventName = event,
        courseMapViewportJson = generation?.let(::viewportJson)
    )

    private fun viewportJson(generation: String): String =
        """{"projection":"web_mercator","zoom":12,"left_px":100.0,"top_px":200.0,"width_px":400,"height_px":300,"generation_id":"$generation"}"""
}
