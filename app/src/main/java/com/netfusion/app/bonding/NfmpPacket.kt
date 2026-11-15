package com.netfusion.app.bonding

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NetFusion Multipath Protocol (NFMP) v1 wire header (24 bytes):
 *  - Magic (2B): 0x4E, 0x46 ('NF')
 *  - Version (1B): 0x01
 *  - Type (1B): 1=HELLO, 2=WELCOME, 3=DATA, 4=PROBE, 5=PROBE_ACK, 6=CLOSE
 *  - SessionId (8B): uint64
 *  - SeqNum (4B): uint32 monotonic sequence for DATA packets
 *  - PathId (1B): 0=WIFI, 1=CELLULAR
 *  - Flags (1B): bit 0 = encrypted
 *  - Reserved (2B): 0x0000
 *  - TimestampMs (4B): lower 32 bits of epoch/monotonic ms for RTT measurement
 *
 * When encrypted (Type == DATA):
 *  - 12-byte GCM Nonce = [PathId(1B) | 0(3B) | SessionIdLow(4B) | SeqNum(4B)] (or random 12B prefix)
 *  - Ciphertext + 16-byte GCM Tag
 */
object NfmpProtocol {
    const val MAGIC_0: Byte = 0x4E
    const val MAGIC_1: Byte = 0x46
    const val VERSION: Byte = 0x01
    const val HEADER_SIZE = 24
    const val GCM_TAG_BITS = 128

    const val TYPE_HELLO: Byte = 1
    const val TYPE_WELCOME: Byte = 2
    const val TYPE_DATA: Byte = 3
    const val TYPE_PROBE: Byte = 4
    const val TYPE_PROBE_ACK: Byte = 5
    const val TYPE_CLOSE: Byte = 6

    const val PATH_WIFI: Byte = 0
    const val PATH_CELLULAR: Byte = 1

    const val FLAG_ENCRYPTED: Byte = 0x01
}

data class NfmpHeader(
    val type: Byte,
    val sessionId: Long,
    val seqNum: Long,
    val pathId: Byte,
    val flags: Byte,
    val timestampMs: Int
) {
    fun encodeHeader(buf: ByteBuffer) {
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.put(NfmpProtocol.MAGIC_0)
        buf.put(NfmpProtocol.MAGIC_1)
        buf.put(NfmpProtocol.VERSION)
        buf.put(type)
        buf.putLong(sessionId)
        buf.putInt(seqNum.toInt())
        buf.put(pathId)
        buf.put(flags)
        buf.putShort(0)
        buf.putInt(timestampMs)
    }

    companion object {
        fun decode(data: ByteArray, length: Int): NfmpHeader? {
            if (length < NfmpProtocol.HEADER_SIZE) return null
            if (data[0] != NfmpProtocol.MAGIC_0 || data[1] != NfmpProtocol.MAGIC_1) return null
            if (data[2] != NfmpProtocol.VERSION) return null
            val buf = ByteBuffer.wrap(data, 3, NfmpProtocol.HEADER_SIZE - 3).order(ByteOrder.BIG_ENDIAN)
            val type = buf.get()
            val sessionId = buf.long
            val seqNum = buf.int.toLong() and 0xFFFFFFFFL
            val pathId = buf.get()
            val flags = buf.get()
            buf.short // reserved
            val ts = buf.int
            return NfmpHeader(type, sessionId, seqNum, pathId, flags, ts)
        }
    }
}

class PacketCrypto(sharedSecretToken: String) {
    private val aesKey: SecretKeySpec
    private val hmacKey: SecretKeySpec

    init {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(sharedSecretToken.toByteArray(Charsets.UTF_8))
        aesKey = SecretKeySpec(keyBytes, "AES")
        val macBytes = digest.digest(("nfmp-auth:" + sharedSecretToken).toByteArray(Charsets.UTF_8))
        hmacKey = SecretKeySpec(macBytes, "HmacSHA256")
    }

    fun computeAuthTag(sessionId: Long, timestampMs: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKey)
        val buf = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(sessionId)
        buf.putInt(timestampMs)
        return mac.doFinal(buf.array())
    }

    fun buildNonce(pathId: Byte, sessionId: Long, seqNum: Long): ByteArray {
        val nonce = ByteArray(12)
        val buf = ByteBuffer.wrap(nonce).order(ByteOrder.BIG_ENDIAN)
        buf.put(pathId)
        buf.put(0)
        buf.put(0)
        buf.put(0)
        buf.putInt((sessionId and 0xFFFFFFFFL).toInt())
        buf.putInt((seqNum and 0xFFFFFFFFL).toInt())
        return nonce
    }

    fun encryptDataPacket(
        header: NfmpHeader,
        plaintext: ByteArray,
        offset: Int,
        length: Int
    ): ByteArray {
        val nonce = buildNonce(header.pathId, header.sessionId, header.seqNum)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(NfmpProtocol.GCM_TAG_BITS, nonce))

        val headerBytes = ByteArray(NfmpProtocol.HEADER_SIZE)
        header.encodeHeader(ByteBuffer.wrap(headerBytes))
        cipher.updateAAD(headerBytes)

        val ciphertext = cipher.doFinal(plaintext, offset, length)
        val out = ByteArray(NfmpProtocol.HEADER_SIZE + ciphertext.size)
        System.arraycopy(headerBytes, 0, out, 0, NfmpProtocol.HEADER_SIZE)
        System.arraycopy(ciphertext, 0, out, NfmpProtocol.HEADER_SIZE, ciphertext.size)
        return out
    }

    fun decryptDataPacket(
        raw: ByteArray,
        totalLength: Int,
        header: NfmpHeader
    ): ByteArray? {
        if (totalLength <= NfmpProtocol.HEADER_SIZE + 16) return null
        return try {
            val nonce = buildNonce(header.pathId, header.sessionId, header.seqNum)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(NfmpProtocol.GCM_TAG_BITS, nonce))
            cipher.updateAAD(raw, 0, NfmpProtocol.HEADER_SIZE)
            cipher.doFinal(raw, NfmpProtocol.HEADER_SIZE, totalLength - NfmpProtocol.HEADER_SIZE)
        } catch (_: Exception) {
            null
        }
    }
}
