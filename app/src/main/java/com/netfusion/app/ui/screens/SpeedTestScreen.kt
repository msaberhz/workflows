package com.netfusion.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.netfusion.app.R
import com.netfusion.app.data.local.SpeedTestResultEntity
import com.netfusion.app.domain.model.SpeedTestProgress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SpeedTestScreen(
    progress: SpeedTestProgress,
    history: List<SpeedTestResultEntity>,
    onStartTest: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.nav_speed_test),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(stringResource(R.string.metric_download), style = MaterialTheme.typography.labelSmall)
                        Text(
                            String.format(Locale.US, "%.2f Mbps", progress.currentDownloadMbps),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column {
                        Text(stringResource(R.string.metric_upload), style = MaterialTheme.typography.labelSmall)
                        Text(
                            String.format(Locale.US, "%.2f Mbps", progress.currentUploadMbps),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${stringResource(R.string.metric_ping)}: ${progress.pingMs} ms", style = MaterialTheme.typography.bodySmall)
                    Text("${stringResource(R.string.metric_jitter)}: ${progress.jitterMs} ms", style = MaterialTheme.typography.bodySmall)
                    Text("${stringResource(R.string.metric_packet_loss)}: ${progress.packetLossPercent.toInt()}%", style = MaterialTheme.typography.bodySmall)
                }

                if (progress.isRunning) {
                    LinearProgressIndicator(
                        progress = { progress.progressFraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                progress.errorMessage?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = onStartTest,
                    enabled = !progress.isRunning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (progress.isRunning) {
                            stringResource(R.string.speedtest_running)
                        } else {
                            stringResource(R.string.speedtest_start)
                        }
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.speedtest_history),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(history, key = { it.id }) { item ->
                val dateStr = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(item.timestampMs))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("${item.transportLabel} • ${item.connectionMode}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text(dateStr, style = MaterialTheme.typography.labelSmall)
                        }
                        Column {
                            Text(String.format(Locale.US, "↓ %.1f Mbps  ↑ %.1f Mbps", item.downloadMbps, item.uploadMbps), style = MaterialTheme.typography.bodySmall)
                            Text("Ping ${item.pingMs}ms • Loss ${item.packetLossPercent.toInt()}%", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
