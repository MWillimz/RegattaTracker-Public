package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Test

class RaceSetupPersistenceTest {

    @Test
    fun legacyDisplayPayload_removesLocalizedLabelPrefix() {
        assertEquals("planned", legacyDisplayPayload("Race: planned"))
        assertEquals("planned", legacyDisplayPayload("Regatta: planned"))
        assertEquals("2026-09-01T12:00:00Z", legacyDisplayPayload("Départ : 2026-09-01T12:00:00Z"))
        assertEquals("2026-09-01T12:00:00Z", legacyDisplayPayload("Salida: 2026-09-01T12:00:00Z"))
    }

    @Test
    fun legacyDisplayPayload_preservesUnprefixedValues() {
        assertEquals("planned", legacyDisplayPayload("planned"))
        assertEquals("--", legacyDisplayPayload("--"))
    }
}
