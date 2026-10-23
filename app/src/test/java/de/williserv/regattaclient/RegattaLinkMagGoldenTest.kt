package de.williserv.regattaclient

import org.junit.Assert.*
import org.junit.Test

class RegattaLinkMagGoldenTest {
    private fun lines(name: String) = javaClass.getResourceAsStream("/mag/$name")!!.bufferedReader().readLines()
    private fun expected(lines: List<String>, key: String) = lines.first { it.startsWith("# $key=") }
        .substringAfter('=').split(',').map(String::toDouble).toDoubleArray()
    private fun points(lines: List<String>) = lines.filterNot { it.startsWith('#') || it.isBlank() }
        .map { it.split(',').map(String::toDouble).toDoubleArray() }
    @Test fun twoDimensionalFitMatchesIndependentFirmwareCli() {
        val data = lines("auto2d-cli.csv")
        val e = expected(data,"expected_offset_radius_rms")
        val fit = RegattaLinkMagAutoFitter.fit(points(data),MagTestData.binding,MagTestData.basis)
        assertArrayEquals(e.copyOfRange(0,3),fit.offset,1e-9)
        assertEquals(e[3],fit.radiusUt,1e-9); assertEquals(e[4],fit.rmsUt,1e-9)
        val objectBytes = encodeRegattaLinkMagAutoModel(fit.binding,fit.basis,fit.offset)
        val encoded = java.nio.ByteBuffer.wrap(objectBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(3) { assertEquals(e[it].toFloat().toRawBits(),encoded.getInt(16+it*4)) }
    }
    @Test fun weightedOriginalXyzFitMatchesIndependentFirmwareCli() {
        val data = lines("manual3d-cli.csv")
        val e = expected(data,"expected_offset_radius_rms")
        val cells = points(data).chunked(5).map { values -> RegattaLinkMagSector(values.map { RegattaLinkMagSample(it[0],it[1],it[2],0,0,0) }) }.toMutableList()
        while(cells.size < 144) cells += RegattaLinkMagSector(emptyList())
        val fit = RegattaLinkMagFitter.fit(RegattaLinkMagArchive(RegattaLinkMagManifest(0x12345678u,0u,5u),cells))
        assertArrayEquals(e.copyOfRange(0,3),fit.offset,1e-8)
        assertEquals(e[3],fit.radiusUt,1e-8); assertEquals(e[4],fit.rmsUt,1e-8)
        assertArrayEquals(expected(data,"expected_matrix"),fit.matrix,1e-9)
        assertEquals(120,fit.usedSectors)
    }
}
