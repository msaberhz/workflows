package com.netfusion.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.netfusion.app.R
import com.netfusion.app.domain.model.TrafficSample
import com.netfusion.app.domain.model.TunnelState
import com.netfusion.app.ui.theme.AmberWarn
import com.netfusion.app.ui.theme.CoralError
import com.netfusion.app.ui.theme.CyanAccent
import com.netfusion.app.ui.theme.EmeraldBond
import java.util.Locale

@Composable
fun ConnectPowerButton(
    tunnelState: TunnelState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnected = tunnelState == TunnelState.CONNECTED_BONDED ||
        tunnelState == TunnelState.CONNECTED_SMART ||
        tunnelState == TunnelState.CONNECTED_DEGRADED_SINGLE_PATH
    val isConnecting = tunnelState == TunnelState.CONNECTING || tunnelState == TunnelState.RECONNECTING

    val targetColor = when (tunnelState) {
        TunnelState.CONNECTED_BONDED -> EmeraldBond
        TunnelState.CONNECTED_SMART -> CyanAccent
        TunnelState.CONNECTED_DEGRADED_SINGLE_PATH -> AmberWarn
        TunnelState.CONNECTING, TunnelState.RECONNECTING -> AmberWarn
        TunnelState.ERROR -> CoralError
        TunnelState.DISCONNECTED -> MaterialTheme.colorScheme.primary
    }

    val ringColor by animateColorAsState(targetValue = targetColor, animationSpec = tween(400), label = "ringColor")

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isConnected || isConnecting) 1.15f else 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isConnecting) 700 else 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(196.dp)
    ) {
        Box(
            modifier = Modifier
                .size(182.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(ringColor.copy(alpha = 0.14f))
        )
        Box(
            modifier = Modifier
                .size(152.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            ringColor.copy(alpha = 0.28f),
                            MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                )
                .border(3.dp, ringColor, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = null,
                    tint = ringColor,
                    modifier = Modifier.size(46.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (isConnected || isConnecting) {
                        stringResource(R.string.action_disconnect)
                    } else {
                        stringResource(R.string.action_connect)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun LiveTrafficGraph(
    samples: List<TrafficSample>,
    modifier: Modifier = Modifier
) {
    val dlColor = CyanAccent
    val ulColor = EmeraldBond
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.stats_realtime_graph),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("● DL", color = dlColor, style = MaterialTheme.typography.labelSmall)
                    Text("● UL", color = ulColor, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(12.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
            ) {
                // Grid lines
                for (i in 1..3) {
                    val y = size.height * (i / 4f)
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }

                if (samples.size < 2) return@Canvas
                val maxBps = samples.maxOf { maxOf(it.downloadBps, it.uploadBps) }.coerceAtLeast(100_000L).toFloat()

                fun buildPath(selector: (TrafficSample) -> Long): Path {
                    val path = Path()
                    samples.forEachIndexed { idx, sample ->
                        val x = (idx.toFloat() / (samples.size - 1).coerceAtLeast(1)) * size.width
                        val y = size.height - ((selector(sample) / maxBps).coerceIn(0f, 1f) * size.height)
                        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    return path
                }

                drawPath(buildPath { it.downloadBps }, color = dlColor, style = Stroke(width = 4f, cap = StrokeCap.Round))
                drawPath(buildPath { it.uploadBps }, color = ulColor, style = Stroke(width = 3f, cap = StrokeCap.Round))
            }
        }
    }
}

fun formatBps(bps: Long): String {
    return when {
        bps >= 1_000_000L -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
        bps >= 1_000L -> String.format(Locale.US, "%.0f Kbps", bps / 1_000.0)
        else -> "$bps bps"
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1_024L -> String.format(Locale.US, "%.0f KB", bytes / 1_024.0)
        else -> "$bytes B"
    }
}

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
