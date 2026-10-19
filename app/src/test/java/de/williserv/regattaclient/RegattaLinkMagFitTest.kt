package de.williserv.regattaclient

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class RegattaLinkMagFitTest {
    private fun archive(flat: Boolean = false, sectors: Int = 144): RegattaLinkMagArchive {
        val rows = (0 until 144).map { i ->
            if (i >= sectors) RegattaLinkMagSector(emptyList())
            else {
                val az = i * Math.PI * (3.0 - sqrt(5.0))
                val z = if (flat) 0.0 else 1.0 - 2.0 * (i + 0.5) / 144.0
                val r = sqrt(1.0 - z*z)
                RegattaLinkMagSector(listOf(RegattaLinkMagSample(
                    x = 9.0 + r*cos(az)*52.0, y = -5.0 + r*sin(az)*47.0,
                    z = 4.0 + z*49.0, downX = 0, downY = 0, downZ = 0
                )))
            }
        }
        return RegattaLinkMagArchive(RegattaLinkMagManifest(1u, 2u, 3u), rows)
    }

    @Test fun robustFitRecoversEllipsoidAndRejectsPlanar() {
        val fit = RegattaLinkMagFitter.fit(archive())
        assertTrue(fit.usedSectors >= 36)
        assertTrue(fit.octants >= 7)
        assertEquals(9.0, fit.offset[0], 1.0)
        assertEquals(-5.0, fit.offset[1], 1.0)
        assertEquals(4.0, fit.offset[2], 1.0)
        assertTrue(fit.rmsUt < 2.5)
        assertThrows(IllegalArgumentException::class.java) {
            RegattaLinkMagFitter.fit(archive(sectors = 20))
        }
        assertThrows(Exception::class.java) {
            RegattaLinkMagFitter.fit(archive(flat = true))
        }
    }
}
