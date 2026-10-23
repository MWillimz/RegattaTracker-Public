package de.williserv.regattaclient

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RegattaLinkMagPersistenceTest {
    private lateinit var prefs: SharedPreferences
    @Before fun setup() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("mag_persistence_test",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }
    private fun objectBytes() = encodeRegattaLinkMagAutoModel(MagTestData.binding,MagTestData.basis,doubleArrayOf(6.0,-4.0,0.0))
    @Test fun newStoreInstanceRecoversExactObjectAndEveryRestoreStage() {
        val pending = MagPending(MagObjectKind.AUTO_2D,MagTestData.binding,0xfedcba98u,objectBytes(),11u)
        for (stage in MagRestoreStage.entries) {
            val backup = MagBackup(MagTestData.binding,objectBytes(),-17,0x87654321u,stage)
            PreferencesMagJournalStore(prefs).save("boat",MagJournal(pending,backup))
            val restored = PreferencesMagJournalStore(prefs).load("boat")
            assertEquals(pending.binding,restored.pending!!.binding)
            assertEquals(pending.requestId,restored.pending.requestId)
            assertArrayEquals(pending.objectBytes,restored.pending.objectBytes)
            assertEquals(stage,restored.backup!!.stage)
            assertEquals(-17,restored.backup.trimDeg)
            assertArrayEquals(backup.objectBytes,restored.backup.objectBytes)
        }
    }
    @Test fun failedCommitIsReportedBeforeCallerMayWriteBle() {
        val failing = object: SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val edit = prefs.edit()
                return object: SharedPreferences.Editor by edit { override fun commit() = false }
            }
        }
        assertThrows(IllegalStateException::class.java) { PreferencesMagJournalStore(failing).save("boat",MagJournal()) }
    }
    @Test fun corruptedOrOldPendingRecordCannotDisappearSilently() {
        prefs.edit().putString("v21.boat","{broken").commit()
        assertThrows(Exception::class.java) { PreferencesMagJournalStore(prefs).load("boat") }
        prefs.edit().clear().putLong("boat.id",123).putLong("boat.crc",42).commit()
        assertThrows(IllegalArgumentException::class.java) { PreferencesMagJournalStore(prefs).load("boat") }
        PreferencesMagJournalStore(prefs).save("boat",MagJournal()) // explicit discard
        assertEquals(MagJournal(),PreferencesMagJournalStore(prefs).load("boat"))
    }
}
