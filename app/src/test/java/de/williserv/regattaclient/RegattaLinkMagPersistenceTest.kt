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
                return object: SharedPreferences.Editor by edit {
                    override fun remove(key: String?): SharedPreferences.Editor {
                        edit.remove(key)
                        return this
                    }
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        edit.putString(key,value)
                        return this
                    }
                    override fun commit() = false
                }
            }
        }
        val journal = MagJournal(pending = MagPending(MagObjectKind.AUTO_2D,MagTestData.binding,123u,objectBytes(),11u))
        // Exercise both remove-only and putString paths without escaping the failing editor.
        for (record in listOf(MagJournal(),journal)) {
            assertThrows(IllegalStateException::class.java) { PreferencesMagJournalStore(failing).save("boat",record) }
        }
    }
    // Model Android's commitToMemory-before-disk behavior, including a second
    // failed disk result while restoring the previous cache contents.
    private fun failingAfterCacheUpdate(): SharedPreferences = object: SharedPreferences by prefs {
        override fun edit(): SharedPreferences.Editor {
            val editor = prefs.edit()
            return object: SharedPreferences.Editor by editor {
                override fun remove(key: String?): SharedPreferences.Editor {
                    editor.remove(key); return this
                }
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    editor.putString(key,value); return this
                }
                override fun commit(): Boolean {
                    editor.commit()
                    return false
                }
            }
        }
    }

    @Test fun failedCleanupPreservesPendingAndBackupForAnotherStoreInstance() {
        val pending = MagPending(MagObjectKind.AUTO_2D,MagTestData.binding,123u,objectBytes(),11u)
        val backup = MagBackup(MagTestData.binding,objectBytes(),-17,456u,MagRestoreStage.RESTORE_TRIM)
        for (record in listOf(MagJournal(pending=pending),MagJournal(backup=backup))) {
            PreferencesMagJournalStore(prefs).save("boat",record)
            val before = prefs.getString("v21.boat",null)
            assertThrows(IllegalStateException::class.java) {
                PreferencesMagJournalStore(failingAfterCacheUpdate()).save("boat",MagJournal())
            }
            assertEquals(before,prefs.getString("v21.boat",null))
            val recovered = PreferencesMagJournalStore(prefs).load("boat")
            if (record.pending != null) {
                assertEquals(pending.requestId,recovered.pending!!.requestId)
                assertArrayEquals(pending.objectBytes,recovered.pending.objectBytes)
            } else {
                assertEquals(MagRestoreStage.RESTORE_TRIM,recovered.backup!!.stage)
                assertEquals(-17,recovered.backup.trimDeg)
                assertArrayEquals(backup.objectBytes,recovered.backup.objectBytes)
            }
            PreferencesMagJournalStore(prefs).save("boat",MagJournal())
            assertEquals(MagJournal(),PreferencesMagJournalStore(prefs).load("boat"))
        }
    }

    @Test fun failedReplacementPreservesPreviousRecordAndFailedFirstSaveLeavesNoPending() {
        val pending = MagPending(MagObjectKind.AUTO_2D,MagTestData.binding,123u,objectBytes(),11u)
        val failing = PreferencesMagJournalStore(failingAfterCacheUpdate())
        assertThrows(IllegalStateException::class.java) { failing.save("boat",MagJournal(pending=pending)) }
        assertEquals(MagJournal(),PreferencesMagJournalStore(prefs).load("boat"))
        PreferencesMagJournalStore(prefs).save("boat",MagJournal(pending=pending))
        assertThrows(IllegalStateException::class.java) {
            failing.save("boat",MagJournal(pending=pending.copy(requestId=124u)))
        }
        assertEquals(123u,PreferencesMagJournalStore(prefs).load("boat").pending!!.requestId)
    }

    @Test fun failedLegacyDiscardRestoresOnlyTheAffectedDeviceKeys() {
        prefs.edit().putLong("boat.id",123).putLong("boat.crc",456)
            .putString("v21.another-boat","another journal").commit()
        assertThrows(IllegalStateException::class.java) {
            PreferencesMagJournalStore(failingAfterCacheUpdate()).save("boat",MagJournal())
        }
        assertEquals(123L,prefs.getLong("boat.id",0))
        assertEquals(456L,prefs.getLong("boat.crc",0))
        assertEquals("another journal",prefs.getString("v21.another-boat",null))
        assertThrows(IllegalArgumentException::class.java) { PreferencesMagJournalStore(prefs).load("boat") }
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
