package com.netfusion.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.MergeType
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.netfusion.app.R
import com.netfusion.app.domain.model.ConnectionMode
import com.netfusion.app.domain.model.NetworkPathState
import com.netfusion.app.domain.model.TransportType

@Composable
fun ConnectionsScreen(
    selectedMode: ConnectionMode,
    wifiState: NetworkPathState,
    cellularState: NetworkPathState,
    activeSmartTransport: TransportType?,
    onSelectMode: (ConnectionMode) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.nav_connections),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        ModeOptionCard(
            title = stringResource(R.string.mode_smart_title),
            subtitle = stringResource(R.string.mode_smart_desc),
            icon = Icons.Rounded.AutoAwesome,
            selected = selectedMode == ConnectionMode.SMART,
            onClick = { onSelectMode(ConnectionMode.SMART) }
        )

        ModeOptionCard(
            title = stringResource(R.string.mode_bonding_title),
            subtitle = stringResource(R.string.mode_bonding_desc),
            icon = Icons.Rounded.MergeType,
            selected = selectedMode == ConnectionMode.BONDING,
            onClick = { onSelectMode(ConnectionMode.BONDING) }
        )

        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.connections_health_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        DetailedPathCard(
            title = stringResource(R.string.label_wifi),
            icon = Icons.Rounded.Wifi,
            state = wifiState,
            isSelectedSmart = activeSmartTransport == TransportType.WIFI
        )

        DetailedPathCard(
            title = stringResource(R.string.label_mobile_data),
            icon = Icons.Rounded.CellTower,
            state = cellularState,
            isSelectedSmart = activeSmartTransport == TransportType.CELLULAR
        )
    }
}

@Composable
private fun ModeOptionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}

@Composable
private fun DetailedPathCard(
    title: String,
    icon: ImageVector,
    state: NetworkPathState,
    isSelectedSmart: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                if (isSelectedSmart) {
                    Text(
                        text = stringResource(R.string.label_active_route),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            LinearProgressIndicator(
                progress = { (state.stabilityScore / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("RTT: ${if (state.rttMs > 0) "${state.rttMs} ms" else "--"}", style = MaterialTheme.typography.bodySmall)
                Text("Jitter: ${state.jitterMs} ms", style = MaterialTheme.typography.bodySmall)
                Text("Loss: ${state.packetLossPercent.toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
