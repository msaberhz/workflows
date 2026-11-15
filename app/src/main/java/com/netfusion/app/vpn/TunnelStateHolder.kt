package com.netfusion.app.vpn

import com.netfusion.app.domain.model.BondingMetrics
import com.netfusion.app.domain.model.ConnectionMode
import com.netfusion.app.domain.model.LiveTrafficStats
import com.netfusion.app.domain.model.TransportType
import com.netfusion.app.domain.model.TunnelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TunnelStateHolder @Inject constructor() {
    val tunnelState = MutableStateFlow(TunnelState.DISCONNECTED)
    val activeMode = MutableStateFlow(ConnectionMode.SMART)
    val activeSmartTransport = MutableStateFlow<TransportType?>(null)
    val bondingMetrics = MutableStateFlow(BondingMetrics())
    val liveTrafficStats = MutableStateFlow(LiveTrafficStats())
    val lastError = MutableStateFlow<String?>(null)
}
