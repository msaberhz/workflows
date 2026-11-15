package com.netfusion.app

import com.netfusion.app.bonding.MultipathScheduler
import com.netfusion.app.bonding.NfmpProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultipathSchedulerTest {

    @Test
    fun `scheduler distributes packets across both healthy paths`() {
        val scheduler = MultipathScheduler()
        scheduler.updatePath(NfmpProtocol.PATH_WIFI, MultipathScheduler.PathMetrics(isUp = true, rttMs = 20, estimatedKbps = 40_000))
        scheduler.updatePath(NfmpProtocol.PATH_CELLULAR, MultipathScheduler.PathMetrics(isUp = true, rttMs = 45, estimatedKbps = 25_000))

        var wifiCount = 0
        var cellCount = 0
        repeat(100) {
            when (scheduler.selectPath(1200)) {
                NfmpProtocol.PATH_WIFI -> wifiCount++
                NfmpProtocol.PATH_CELLULAR -> cellCount++
            }
        }
        assertTrue(wifiCount > 0)
        assertTrue(cellCount > 0)
        assertTrue(wifiCount > cellCount)
    }

    @Test
    fun `scheduler falls back to single healthy path when other drops`() {
        val scheduler = MultipathScheduler()
        scheduler.updatePath(NfmpProtocol.PATH_WIFI, MultipathScheduler.PathMetrics(isUp = false))
        scheduler.updatePath(NfmpProtocol.PATH_CELLULAR, MultipathScheduler.PathMetrics(isUp = true, rttMs = 40))

        repeat(10) {
            assertEquals(NfmpProtocol.PATH_CELLULAR, scheduler.selectPath(1000))
        }
    }
}
