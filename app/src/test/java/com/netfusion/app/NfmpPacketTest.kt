package com.netfusion.app

import com.netfusion.app.bonding.NfmpHeader
import com.netfusion.app.bonding.NfmpProtocol
import com.netfusion.app.bonding.PacketCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NfmpPacketTest {

    @Test
    fun `aes gcm encrypt and decrypt roundtrip succeeds`() {
        val crypto = PacketCrypto("super-secret-token")
        val header = NfmpHeader(
            type = NfmpProtocol.TYPE_DATA,
            sessionId = 987654321L,
            seqNum = 42L,
            pathId = NfmpProtocol.PATH_CELLULAR,
            flags = NfmpProtocol.FLAG_ENCRYPTED,
            timestampMs = 12345
        )
        val payload = "Hello NetFusion Multipath".toByteArray()
        val encrypted = crypto.encryptDataPacket(header, payload, 0, payload.size)

        val decodedHeader = NfmpHeader.decode(encrypted, encrypted.size)
        assertNotNull(decodedHeader)
        assertEquals(42L, decodedHeader!!.seqNum)
        assertEquals(NfmpProtocol.PATH_CELLULAR, decodedHeader.pathId)

        val decrypted = crypto.decryptDataPacket(encrypted, encrypted.size, decodedHeader)
        assertNotNull(decrypted)
        assertArrayEquals(payload, decrypted)
    }

    @Test
    fun `wrong token fails authentication`() {
        val cryptoA = PacketCrypto("token-a")
        val cryptoB = PacketCrypto("token-b")
        val header = NfmpHeader(NfmpProtocol.TYPE_DATA, 1L, 1L, NfmpProtocol.PATH_WIFI, NfmpProtocol.FLAG_ENCRYPTED, 100)
        val encrypted = cryptoA.encryptDataPacket(header, byteArrayOf(1, 2, 3, 4), 0, 4)

        val decoded = NfmpHeader.decode(encrypted, encrypted.size)!!
        assertNull(cryptoB.decryptDataPacket(encrypted, encrypted.size, decoded))
    }
}
