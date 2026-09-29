package de.williserv.regattaclient

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RaceLegalAcceptanceStoreTest {
    private lateinit var context: Context
    private lateinit var store: RaceLegalAcceptanceStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(
            RaceLegalAcceptanceStore.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        store = RaceLegalAcceptanceStore(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(
            RaceLegalAcceptanceStore.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }

    @Test
    fun emptyStoreDoesNotMatchAnything() {
        assertNull(store.load())
        assertFalse(store.matches("Event A", "hash-a"))
    }

    @Test
    fun successfulPairRoundTripsAndMatchesExactly() {
        store.save(" Event A ", " hash-a ")

        assertEquals(
            RaceLegalAcceptanceCacheEntry("Event A", "hash-a"),
            store.load()
        )
        assertTrue(store.matches("Event A", "hash-a"))
        assertFalse(store.matches("Event A", "hash-b"))
        assertFalse(store.matches("Event B", "hash-a"))
    }

    @Test
    fun laterSuccessfulAcceptanceReplacesPreviousPair() {
        store.save("Event A", "hash-a")
        store.save("Event B", "hash-b")

        assertFalse(store.matches("Event A", "hash-a"))
        assertTrue(store.matches("Event B", "hash-b"))
    }

    @Test
    fun blankValuesAreNeverCached() {
        store.save("", "hash-a")
        assertNull(store.load())

        store.save("Event A", "")
        assertNull(store.load())
    }
}
