package de.williserv.regattaclient

import android.content.Context
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PromotionUiHardeningTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clearCompatibilityPrefs()
    }

    @After
    fun tearDown() {
        clearCompatibilityPrefs()
    }

    @Test
    fun versionMetadataAlone_doesNotCountAsServerOperatorMetadata() {
        val metadata = ServerMetadata(
            operator = null,
            publicUrl = null,
            contactEmail = null,
            serverBuildId = "dev-debug",
            serverBuildNumber = 42,
            serverBuildType = "dev-debug"
        )

        assertTrue(metadata.hasAnyValue())
        assertFalse(hasServerOperatorMetadata(metadata))
        assertTrue(hasServerOperatorMetadata(metadata.copy(operator = "Race Office")))
    }

    @Test
    fun compatibilityBlockChanges_areObservableForOpenUi() {
        var callbacks = 0
        val stopObserving = ClientCompatibilityBlockStore.observeBlockChanges(context) {
            callbacks += 1
        }

        try {
            ClientCompatibilityBlockStore.markBlocked(
                context = context,
                serverUrl = "https://raceoffice.example.org",
                versionCode = 2322
            )
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(callbacks >= 1)
            assertTrue(ClientCompatibilityBlockStore.hasAnyBlockForVersion(context, 2322))

            ClientCompatibilityBlockStore.clearBlocked(
                context = context,
                serverUrl = "https://raceoffice.example.org",
                versionCode = 2322
            )
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(callbacks >= 2)
            assertFalse(ClientCompatibilityBlockStore.hasAnyBlockForVersion(context, 2322))
        } finally {
            stopObserving()
        }
    }

    private fun clearCompatibilityPrefs() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private companion object {
        const val PREFS_NAME = "regatta_local_status"
    }
}
