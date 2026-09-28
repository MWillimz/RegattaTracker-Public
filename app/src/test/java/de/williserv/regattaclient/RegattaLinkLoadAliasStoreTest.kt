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
class RegattaLinkLoadAliasStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clearPrefs()
    }

    @After
    fun tearDown() {
        clearPrefs()
    }

    @Test
    fun stableAliasPersistsAcrossStoreInstancesAndCanBeRemoved() {
        val identity = "stable:nmea:0123456789abcdef.0"
        val first = RegattaLinkLoadAliasStore(context)

        assertTrue(first.set(identity, "Vorstag"))
        assertEquals("Vorstag", RegattaLinkLoadAliasStore(context).get(identity))

        assertTrue(RegattaLinkLoadAliasStore(context).set(identity, ""))
        assertNull(RegattaLinkLoadAliasStore(context).get(identity))
    }

    @Test
    fun temporaryIdentityCannotReceivePersistentAlias() {
        val store = RegattaLinkLoadAliasStore(context)

        assertFalse(store.set("temporary:nmea-source:34.0", "Vorstag"))
        assertNull(store.get("temporary:nmea-source:34.0"))
    }

    private fun clearPrefs() {
        context.getSharedPreferences(
            RegattaLinkLoadAliasStore.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }
}
