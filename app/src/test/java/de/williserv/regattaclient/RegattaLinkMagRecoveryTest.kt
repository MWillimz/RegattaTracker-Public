package de.williserv.regattaclient

import org.junit.Assert.*
import org.junit.Test

class RegattaLinkMagRecoveryTest {
    private fun pending(kind: MagObjectKind = MagObjectKind.AUTO_2D): MagPending {
        val bytes = if (kind == MagObjectKind.AUTO_2D) encodeRegattaLinkMagAutoModel(MagTestData.binding,MagTestData.basis,doubleArrayOf(6.0,-4.0,0.0))
        else encodeRegattaLinkMagModel(RegattaLinkMagManifest(MagTestData.binding.factoryCrc,0u,3u),
            RegattaLinkMagFit(doubleArrayOf(6.0,-4.0,0.0),doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),50.0,0.1,0.1,50,0,8,1.0))
        return MagPending(kind,MagTestData.binding,123u,bytes,11u)
    }
    @Test fun durableAutoReceiptSurvivesMcuRebootAndUnsubscribeContextChange() {
        val p = pending()
        val m = MagTestData.metadata().copy(storedRequestId=123u,storedObjectCrc=p.crc,bootId=999u,streamContext=987u,phase=0)
        assertEquals(MagReceipt.STORED,magReceipt(p,m,null,"boat"))
        assertEquals(MagReceipt.UNKNOWN,magReceipt(p,m.copy(offset=doubleArrayOf(6.1,-4.0,0.0)),null,"boat"))
        assertEquals(MagReceipt.UNKNOWN,magReceipt(p,m.copy(flags=m.flags and 4.inv()),null,"boat"))
        assertEquals(MagReceipt.UNKNOWN,magReceipt(p,m.copy(storedRequestId=124u),null,"boat"))
        assertEquals(MagReceipt.UNKNOWN,magReceipt(p,m.copy(uprightRef=7u),null,"boat"))
        assertEquals(MagReceipt.UNKNOWN,magReceipt(p,m.copy(factoryCrc=7u),null,"boat"))
        assertEquals(MagReceipt.UNKNOWN,magReceipt(p,m,null,"another-boat"))
    }
    @Test fun fullCrcAloneIsNotEvidenceForTheExactOffset() {
        val p = pending()
        val another = encodeRegattaLinkMagAutoModel(MagTestData.binding,MagTestData.basis,doubleArrayOf(8.0,0.0,0.0))
        // IEEE CRC32 over the full object including its trailing CRC has the codeword residue.
        assertEquals(p.crc,magCrc32(another))
        assertEquals(MagReceipt.UNKNOWN,magReceipt(p,MagTestData.metadata().copy(storedRequestId=p.requestId,
            storedObjectCrc=p.crc,offset=doubleArrayOf(8.0,0.0,0.0)),null,"boat"))
    }
    @Test fun terminalErrorsMustMatchRequestAndObject() {
        val p = pending()
        val m = MagTestData.metadata().copy(phase=5,errorCode=4,requestId=p.requestId,objectCrc=p.crc)
        assertEquals(MagReceipt.REJECTED,magReceipt(p,m,null,"boat"))
        assertEquals(MagReceipt.UNKNOWN,magReceipt(p,m.copy(requestId=4u),null,"boat"))
    }
    @Test fun manualReceiptRequiresRetainedCorrelatedStatusAndSameBoot() {
        val p = pending(MagObjectKind.MANUAL_3D)
        val status = RegattaLinkMagStatus(4,0,1,true,123u,MagTestData.binding.factoryCrc,3u,p.crc)
        val m = MagTestData.metadata().copy(storedRequestId=123u,storedObjectCrc=p.crc)
        assertEquals(MagReceipt.STORED,magReceipt(p,m,status,"boat"))
        assertEquals(MagReceipt.UNKNOWN,magReceipt(p,m.copy(bootId=12u),status,"boat"))
        assertEquals(MagReceipt.UNKNOWN,magReceipt(p,m,null,"boat"))
        assertEquals(MagReceipt.UNKNOWN,magReceipt(p,m,status.copy(requestId=124u),"boat"))
        assertEquals(MagReceipt.UNKNOWN,magReceipt(p,m,status.copy(installed3d=false),"boat"))
    }
}
