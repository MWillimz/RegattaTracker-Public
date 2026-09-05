package de.williserv.regattaclient

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackingSessionMetadataResetTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DB_NAME)
        context.getSharedPreferences("tracking_config", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
        context.getSharedPreferences("tracking_config", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `new tracking session emits profile again without waiting for heartbeat`() {
        TrackingProfileConfig.write(context, TrackingProfile.NORMAL)
        val helper = TrackingDbHelper(context)
        val accessContextId = requireNotNull(
            helper.getOrCreateAccessContext(
                serverUrl = "https://raceoffice.example.org",
                accessIdentifier = "Event A",
                accessSecret = "secret"
            )
        )

        val firstId = insertSample(helper, accessContextId, 1L)
        val secondId = insertSample(helper, accessContextId, 2L)

        val beforeReset = helper.getPendingSamples(10).associateBy { it.localId }
        assertEquals("normal", beforeReset.getValue(firstId).trackingProfile)
        assertNull(beforeReset.getValue(secondId).trackingProfile)

        helper.resetTrackingSessionMetadata()

        val restartedSessionId = insertSample(helper, accessContextId, 3L)
        val afterReset = helper.getPendingSamples(10).associateBy { it.localId }
        assertEquals("normal", afterReset.getValue(restartedSessionId).trackingProfile)

        helper.close()
    }

    private fun insertSample(
        helper: TrackingDbHelper,
        accessContextId: Long,
        sequenceId: Long
    ): Long {
        return helper.insertSample(
            sequenceId = sequenceId,
            timestamp = "2026-09-05T00:00:00",
            boatName = "Test Boat",
            captainName = "Test Captain",
            hullColor = "white",
            sailNumber = "GER 1",
            yardstick = 100.0,
            boatType = "Test",
            lat = 53.0,
            lon = 10.0,
            accuracy = 5f,
            cog = 0f,
            sog = 0f,
            accelX = 0f,
            accelY = 0f,
            accelZ = 0f,
            gyroX = 0f,
            gyroY = 0f,
            gyroZ = 0f,
            batteryPercent = 50,
            batteryCharging = false,
            trackingProfile = null,
            accessContextId = accessContextId
        )
    }

    private companion object {
        const val DB_NAME = "regatta_tracking.db"
    }
}
