package com.netfusion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MergeType
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.netfusion.app.R
import com.netfusion.app.domain.model.BondingMetrics
import com.netfusion.app.domain.model.ConnectionMode
import com.netfusion.app.domain.model.LimitationReason
import com.netfusion.app.domain.model.LiveTrafficStats
import com.netfusion.app.domain.model.NetworkPathState
import com.netfusion.app.domain.model.TunnelState
import com.netfusion.app.ui.components.ConnectPowerButton
import com.netfusion.app.ui.components.formatBps
import com.netfusion.app.ui.components.formatBytes
import com.netfusion.app.ui.components.formatDuration
import com.netfusion.app.ui.theme.AmberWarn
import com.netfusion.app.ui.theme.CyanAccent
import com.netfusion.app.ui.theme.EmeraldBond

@Composable
fun DashboardScreen(
    tunnelState: TunnelState,
    preferredMode: ConnectionMode,
    wifiState: NetworkPathState,
    cellularState: NetworkPathState,
    bondingMetrics: BondingMetrics,
    trafficStats: LiveTrafficStats,
    onToggleConnection: () -> Unit
) {
    val statusText = when (tunnelState) {
        TunnelState.DISCONNECTED -> stringResource(R.string.status_disconnected)
        TunnelState.CONNECTING -> stringResource(R.string.status_connecting)
        TunnelState.CONNECTED_SMART -> stringResource(R.string.status_connected_smart)
        TunnelState.CONNECTED_BONDED -> stringResource(R.string.status_connected_bonded)
        TunnelState.CONNECTED_DEGRADED_SINGLE_PATH -> stringResource(R.string.status_degraded_single_path)
        TunnelState.RECONNECTING -> stringResource(R.string.status_reconnecting)
        TunnelState.ERROR -> stringResource(R.string.status_error)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    color = if (bondingMetrics.isTrueBondingActive) EmeraldBond else MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(16.dp))
            ConnectPowerButton(tunnelState = tunnelState, onClick = onToggleConnection)
            Spacer(Modifier.height(12.dp))

            // Real Combined Bandwidth Indicator ONLY when real bonding is active
            if (bondingMetrics.isTrueBondingActive) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = EmeraldBond.copy(alpha = 0.14f))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Rounded.MergeType, contentDescription = null, tint = EmeraldBond)
                        Column {
                            Text(
                                text = stringResource(R.string.bonding_active_badge),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldBond
                            )
                            Text(
                                text = stringResource(
                                    R.string.bonding_share_summary,
                                    bondingMetrics.wifiSharePercent,
                                    bondingMetrics.cellularSharePercent
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            } else if (preferredMode == ConnectionMode.BONDING && bondingMetrics.limitationReason != null && bondingMetrics.limitationReason != LimitationReason.NONE) {
                LimitationBanner(bondingMetrics.limitationReason)
                Spacer(Modifier.height(10.dp))
            }

            // Wi-Fi & Mobile Data Status Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InterfaceStatusCard(
                    title = stringResource(R.string.label_wifi),
                    icon = Icons.Rounded.Wifi,
                    state = wifiState,
                    modifier = Modifier.weight(1f)
                )
                InterfaceStatusCard(
                    title = stringResource(R.string.label_mobile_data),
                    icon = Icons.Rounded.CellTower,
                    state = cellularState,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            // Speed & Latency Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricTile(
                    icon = Icons.Rounded.ArrowDownward,
                    label = stringResource(R.string.metric_download),
                    value = formatBps(trafficStats.downloadBps),
                    tint = CyanAccent,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    icon = Icons.Rounded.ArrowUpward,
                    label = stringResource(R.string.metric_upload),
                    value = formatBps(trafficStats.uploadBps),
                    tint = EmeraldBond,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(10.dp))

            // Ping, Data Usage, Uptime Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricTile(
                    icon = Icons.Rounded.Speed,
                    label = stringResource(R.string.metric_ping),
                    value = if (trafficStats.pingMs > 0) "${trafficStats.pingMs} ms" else "--",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    icon = Icons.Rounded.DataUsage,
                    label = stringResource(R.string.metric_data_usage),
                    value = formatBytes(trafficStats.sessionRxBytes + trafficStats.sessionTxBytes),
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    icon = Icons.Rounded.Timer,
                    label = stringResource(R.string.metric_uptime),
                    value = formatDuration(trafficStats.uptimeSeconds),
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun InterfaceStatusCard(
    title: String,
    icon: ImageVector,
    state: NetworkPathState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (state.isAvailable) EmeraldBond else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp)
                )
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    !state.isAvailable -> stringResource(R.string.net_disconnected)
                    state.isValidated -> stringResource(R.string.net_ready_rtt, if (state.rttMs > 0) "${state.rttMs} ms" else "--")
                    else -> stringResource(R.string.net_limited)
                },
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = stringResource(R.string.net_stability_score, state.stabilityScore.toInt()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun MetricTile(
    icon: ImageVector,
    label: String,
    value: String,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LimitationBanner(reason: LimitationReason) {
    val msg = when (reason) {
        LimitationReason.SERVER_NOT_CONFIGURED -> stringResource(R.string.limit_server_not_configured)
        LimitationReason.SINGLE_INTERFACE_ONLY -> stringResource(R.string.limit_single_interface)
        LimitationReason.CELLULAR_RESTRICTED_OR_UNAVAILABLE -> stringResource(R.string.limit_cellular_restricted)
        LimitationReason.SERVER_UNREACHABLE_SMART_FALLBACK -> stringResource(R.string.limit_server_unreachable)
        LimitationReason.AUTHENTICATION_FAILED -> stringResource(R.string.limit_auth_failed)
        LimitationReason.NONE -> ""
    }
    if (msg.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = AmberWarn.copy(alpha = 0.16f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Rounded.Info, contentDescription = null, tint = AmberWarn)
            Text(msg, style = MaterialTheme.typography.bodySmall)
        }
    }
}
