package com.netfusion.app

import com.netfusion.app.domain.model.NetworkPathState
import com.netfusion.app.domain.model.TransportType
import com.netfusion.app.network.SmartModeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartModeEngineTest {

    @Test
    fun `initial selection picks higher stability network`() {
        val engine = SmartModeEngine()
        val wifi = NetworkPathState(type = TransportType.WIFI, isAvailable = true, isValidated = true, stabilityScore = 88f, rttMs = 20)
        val cell = NetworkPathState(type = TransportType.CELLULAR, isAvailable = true, isValidated = true, stabilityScore = 65f, rttMs = 55)

        val decision = engine.evaluate(wifi, cell, nowMs = 1_000L)
        assertEquals(TransportType.WIFI, decision.selectedTransport)
        assertTrue(decision.switched)
    }

    @Test
    fun `hysteresis prevents rapid ping-pong switching within dwell time`() {
        val engine = SmartModeEngine()
        val wifiGood = NetworkPathState(type = TransportType.WIFI, isAvailable = true, isValidated = true, stabilityScore = 80f, rttMs = 30)
        val cellOk = NetworkPathState(type = TransportType.CELLULAR, isAvailable = true, isValidated = true, stabilityScore = 70f, rttMs = 45)
        engine.evaluate(wifiGood, cellOk, nowMs = 1_000L)

        // Cellular slightly better, but within 15s dwell time
        val wifiLower = wifiGood.copy(stabilityScore = 72f)
        val cellHigher = cellOk.copy(stabilityScore = 90f)
        val holdDecision = engine.evaluate(wifiLower, cellHigher, nowMs = 5_000L)
        assertEquals(TransportType.WIFI, holdDecision.selectedTransport)
        assertFalse(holdDecision.switched)

        // After dwell time passes, switch occurs
        val switchDecision = engine.evaluate(wifiLower, cellHigher, nowMs = 20_000L)
        assertEquals(TransportType.CELLULAR, switchDecision.selectedTransport)
        assertTrue(switchDecision.switched)
    }

    @Test
    fun `emergency failover triggers immediately on high packet loss`() {
        val engine = SmartModeEngine()
        val wifi = NetworkPathState(type = TransportType.WIFI, isAvailable = true, isValidated = true, stabilityScore = 85f, rttMs = 25)
        val cell = NetworkPathState(type = TransportType.CELLULAR, isAvailable = true, isValidated = true, stabilityScore = 70f, rttMs = 50)
        engine.evaluate(wifi, cell, nowMs = 1_000L)

        val wifiFailing = wifi.copy(packetLossPercent = 35f, stabilityScore = 40f)
        val failover = engine.evaluate(wifiFailing, cell, nowMs = 2_000L)
        assertEquals(TransportType.CELLULAR, failover.selectedTransport)
        assertTrue(failover.switched)
    }
}
