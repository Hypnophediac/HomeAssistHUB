package com.homeassisthub.client.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SolarPower
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homeassisthub.client.R
import com.homeassisthub.client.network.model.P1ReadingDto
import com.homeassisthub.client.network.model.DailySummaryDto
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.chart.line.lineSpec
import com.patrykandpatrick.vico.core.entry.entryModelOf

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = viewModel()) {
    val plugs by viewModel.plugs.collectAsState()
    val plugStates by viewModel.plugStates.collectAsState()
    val p1History by viewModel.p1History.collectAsState()
    val dailySummary by viewModel.dailySummary.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            DashboardHeader(
                onRefresh = { viewModel.refresh() },
                plugCount = plugs.size,
                readingCount = p1History.size
            )
        }

        statusMessage?.let { msg ->
            item {
                StatusBanner(message = msg)
            }
        }

        item {
            P1PowerCard(readings = p1History)
        }

        dailySummary?.let { summary ->
            item {
                DailySummaryCard(summary = summary)
            }
        }

        item {
            Text(
                text = "Konnektorok",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
        }

        if (plugs.isEmpty()) {
            item {
                EmptyPlugCard()
            }
        } else {
            items(plugs) { plug ->
                val isOn = plugStates[plug.deviceId] ?: false
                PlugCard(
                    deviceId = plug.deviceId,
                    ipAddress = plug.ipAddress,
                    isOn = isOn,
                    onTurnOn = { viewModel.togglePlug(plug.deviceId, turnOn = true) },
                    onTurnOff = { viewModel.togglePlug(plug.deviceId, turnOn = false) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun DashboardHeader(
    onRefresh: () -> Unit,
    plugCount: Int,
    readingCount: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.dashboard_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    Text(
                        text = "$plugCount eszköz",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "$readingCount mérés",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            FilledIconButton(
                onClick = onRefresh,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Frissítés"
                )
            }
        }
    }
}

@Composable
private fun StatusBanner(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.WifiOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun P1PowerCard(readings: List<P1ReadingDto>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.dashboard_power_chart_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (readings.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Adatok betöltése...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                val latest = readings.last()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatChip(
                        label = "Napelem Termelés",
                        value = if (latest.inverterPowerW > 0.0) {
                            "${latest.inverterPowerW.toInt()} W"
                        } else {
                            "— W"
                        },
                        color = Color(0xFFF59E0B)
                    )
                    val isExporting = latest.powerExportW > latest.powerImportW
                    val netPowerW = kotlin.math.abs(latest.powerImportW - latest.powerExportW).toInt()
                    StatChip(
                        label = if (isExporting) "Betáplálás" else "Vételezés",
                        value = if (netPowerW > 0) "${netPowerW} W" else "— W",
                        color = if (isExporting) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                    )
                    val houseW = maxOf(0.0, latest.realConsumptionW).toInt()
                    StatChip(
                        label = "Ház Fogyasztás",
                        value = if (latest.inverterPowerW > 0.0 || latest.realConsumptionW > 0.0) {
                            "${houseW} W"
                        } else {
                            "— W"
                        },
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Feszültség: %.0f / %.0f / %.0f V".format(latest.l1V, latest.l2V, latest.l3V),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                P1HistoryChart(readings = readings)
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, color: Color = MaterialTheme.colorScheme.primary) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyPlugCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.ElectricalServices,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.dashboard_no_plugs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlugCard(
    deviceId: String,
    ipAddress: String,
    isOn: Boolean,
    onTurnOn: () -> Unit,
    onTurnOff: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isOn)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isOn)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Power,
                        contentDescription = null,
                        tint = if (isOn)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = deviceId,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = ipAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Row {
                    Button(
                        onClick = onTurnOn,
                        enabled = !isOn
                    ) {
                        Text(text = stringResource(R.string.plug_turn_on))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    OutlinedButton(
                        onClick = onTurnOff,
                        enabled = isOn
                    ) {
                        Text(text = stringResource(R.string.plug_turn_off))
                    }
                }
            }
        }
    }
}

@Composable
private fun P1HistoryChart(readings: List<P1ReadingDto>) {
    val importEntries = readings.mapIndexed { index, reading ->
        com.patrykandpatrick.vico.core.entry.entryOf(index.toFloat(), reading.powerImportW.toFloat())
    }
    val exportEntries = readings.mapIndexed { index, reading ->
        com.patrykandpatrick.vico.core.entry.entryOf(index.toFloat(), reading.powerExportW.toFloat())
    }
    val inverterEntries = readings.mapIndexed { index, reading ->
        com.patrykandpatrick.vico.core.entry.entryOf(index.toFloat(), reading.inverterPowerW.toFloat())
    }
    val consumptionEntries = readings.mapIndexed { index, reading ->
        com.patrykandpatrick.vico.core.entry.entryOf(index.toFloat(), reading.realConsumptionW.toFloat())
    }
    val model = entryModelOf(importEntries, exportEntries, inverterEntries, consumptionEntries)

    Chart(
        chart = lineChart(
            lines = listOf(
                lineSpec(lineColor = Color(0xFFEF4444)), // Import - red
                lineSpec(lineColor = Color(0xFF22C55E)), // Export - green
                lineSpec(lineColor = Color(0xFFF59E0B)), // Inverter - amber
                lineSpec(lineColor = Color(0xFF3B82F6)), // Real consumption - blue
            )
        ),
        model = model,
        startAxis = rememberStartAxis(),
        bottomAxis = rememberBottomAxis(),
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
    )
}

@Composable
private fun DailySummaryCard(summary: DailySummaryDto) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.SolarPower,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Napi Összesítő (0–24h)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DailyStatChip(
                    label = "Napelem Termelés",
                    value = "%.2f kWh".format(summary.inverterDailyKwh),
                    color = Color(0xFFF59E0B)
                )
                DailyStatChip(
                    label = "Vételezés",
                    value = "%.2f kWh".format(summary.p1DailyImportKwh),
                    color = MaterialTheme.colorScheme.error
                )
                DailyStatChip(
                    label = "Betáplálás",
                    value = "%.2f kWh".format(summary.p1DailyExportKwh),
                    color = Color(0xFF2E7D32)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Ház Napi Fogyasztás: %.2f kWh".format(summary.houseDailyKwh),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun DailyStatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
