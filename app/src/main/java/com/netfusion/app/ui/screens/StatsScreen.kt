package com.netfusion.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.netfusion.app.R
import com.netfusion.app.data.local.DailyUsageEntity
import com.netfusion.app.domain.model.BondingMetrics
import com.netfusion.app.domain.model.LiveTrafficStats
import com.netfusion.app.ui.components.LiveTrafficGraph
import com.netfusion.app.ui.components.formatBytes

@Composable
fun StatsScreen(
    trafficStats: LiveTrafficStats,
    bondingMetrics: BondingMetrics,
    todayUsage: DailyUsageEntity?,
    monthlyUsage: List<DailyUsageEntity>
) {
    val todayWifi = (todayUsage?.wifiRxBytes ?: 0L) + (todayUsage?.wifiTxBytes ?: 0L)
    val todayCell = (todayUsage?.cellularRxBytes ?: 0L) + (todayUsage?.cellularTxBytes ?: 0L)
    val monthWifi = monthlyUsage.sumOf { it.wifiRxBytes + it.wifiTxBytes }
    val monthCell = monthlyUsage.sumOf { it.cellularRxBytes + it.cellularTxBytes }
    val totalBondedSeconds = monthlyUsage.sumOf { it.bondedSeconds }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.nav_stats),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        LiveTrafficGraph(samples = trafficStats.historySamples)

        UsageSummaryCard(
            title = stringResource(R.string.stats_daily_usage),
            wifiBytes = todayWifi,
            cellBytes = todayCell
        )

        UsageSummaryCard(
            title = stringResource(R.string.stats_monthly_usage),
            wifiBytes = monthWifi,
            cellBytes = monthCell
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.stats_bonding_perf),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.stats_reordered_packets), style = MaterialTheme.typography.bodySmall)
                    Text("${bondingMetrics.reorderedPackets}", fontWeight = FontWeight.SemiBold)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.stats_dropped_late), style = MaterialTheme.typography.bodySmall)
                    Text("${bondingMetrics.droppedOutOfOrderPackets}", fontWeight = FontWeight.SemiBold)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.stats_total_bonded_time), style = MaterialTheme.typography.bodySmall)
                    Text("${totalBondedSeconds / 60} min", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun UsageSummaryCard(title: String, wifiBytes: Long, cellBytes: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.label_wifi), style = MaterialTheme.typography.bodySmall)
                Text(formatBytes(wifiBytes), fontWeight = FontWeight.SemiBold)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.label_mobile_data), style = MaterialTheme.typography.bodySmall)
                Text(formatBytes(cellBytes), fontWeight = FontWeight.SemiBold)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.stats_total), style = MaterialTheme.typography.bodySmall)
                Text(formatBytes(wifiBytes + cellBytes), fontWeight = FontWeight.Bold)
            }
        }
    }
}
