package com.netfusion.app

import com.netfusion.app.bonding.ReorderBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ReorderBufferTest {

    @Test
    fun `out of order packets are reassembled in sequence`() {
        val buf = ReorderBuffer(maxHoldbackMs = 100L)
        assertEquals(1, buf.push(1L, byteArrayOf(1), nowMs = 100L).size)

        // Packet 3 arrives before packet 2
        val out3 = buf.push(3L, byteArrayOf(3), nowMs = 110L)
        assertEquals(0, out3.size)

        // Packet 2 arrives -> drains 2 and 3 in order
        val out2 = buf.push(2L, byteArrayOf(2), nowMs = 120L)
        assertEquals(2, out2.size)
        assertArrayEquals(byteArrayOf(2), out2[0])
        assertArrayEquals(byteArrayOf(3), out2[1])
        assertEquals(1L, buf.reorderedCount)
    }

    @Test
    fun `expired gap flushes buffered packets after holdback timeout`() {
        val buf = ReorderBuffer(maxHoldbackMs = 40L)
        buf.push(1L, byteArrayOf(1), nowMs = 100L)
        buf.push(3L, byteArrayOf(3), nowMs = 110L) // Packet 2 lost

        val flushed = buf.flushExpired(nowMs = 160L)
        assertEquals(1, flushed.size)
        assertArrayEquals(byteArrayOf(3), flushed[0])
    }
}
