package com.homeassisthub.client.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homeassisthub.client.R
import com.homeassisthub.client.network.model.P1ReadingDto
import com.homeassisthub.client.network.model.LivePowerData
import com.homeassisthub.client.network.model.DailySummaryDto
import com.homeassisthub.client.ui.components.FreshnessBadge
import com.homeassisthub.client.util.formatKwh
import com.homeassisthub.client.util.formatW

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = viewModel()) {
    val plugs by viewModel.plugs.collectAsState()
    val plugStates by viewModel.plugStates.collectAsState()
    val p1History by viewModel.p1History.collectAsState()
    val livePower by viewModel.livePower.collectAsState()
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
            P1PowerCard(readings = p1History, livePower = livePower)
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
private fun P1PowerCard(readings: List<P1ReadingDto>, livePower: LivePowerData?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                if (readings.isNotEmpty()) {
                    FreshnessBadge(timestampMs = readings.last().timestamp)
                }
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
                val lp = livePower
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatChip(
                        label = "Napelem",
                        value = if (lp != null && lp.inverterPowerW > 0.0) {
                            formatW(lp.inverterPowerW)
                        } else {
                            "— W"
                        },
                        color = Color(0xFFF59E0B)
                    )
                    StatChip(
                        label = "Ház Fogy.",
                        value = if (lp != null && lp.hasInverter) {
                            formatW(lp.houseW)
                        } else {
                            "— W"
                        },
                        color = MaterialTheme.colorScheme.primary
                    )
                    StatChip(
                        label = "Vételezés",
                        value = if (lp != null && lp.importW > 0) formatW(lp.importW) else "0 W",
                        color = MaterialTheme.colorScheme.error
                    )
                    StatChip(
                        label = "Betáplálás",
                        value = if (lp != null && lp.exportW > 0) formatW(lp.exportW) else "0 W",
                        color = Color(0xFF2E7D32)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (lp != null) {
                        "Feszültség: %.0f / %.0f / %.0f V".format(lp.l1V, lp.l2V, lp.l3V)
                    } else {
                        "Feszültség: — / — / — V"
                    },
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
    val chartReadings = readings.takeLast(60)
    if (chartReadings.isEmpty()) return

    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    val timeLabels = chartReadings.map { r -> sdf.format(java.util.Date(r.timestamp)) }
    val labelEvery = if (timeLabels.size > 8) timeLabels.size / 6 else 1

    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = androidx.compose.ui.platform.LocalDensity.current
    val axisFontSizePx = with(density) { 9.sp.toPx() }
    val yLabelPaddingPx = with(density) { 4.dp.toPx() }
    val xLabelHeightPx = with(density) { 14.dp.toPx() }
    val yLabelWidthPx = with(density) { 40.dp.toPx() }

    val importColor = Color(0xFFEF4444)
    val exportColor = Color(0xFF22C55E)
    val inverterColor = Color(0xFFF59E0B)
    val consumptionColor = Color(0xFF3B82F6)

    val importSeries = chartReadings.map { it.powerImportW.toFloat() }
    val exportSeries = chartReadings.map { it.powerExportW.toFloat() }
    val inverterSeries = chartReadings.map { it.inverterPowerW.toFloat() }
    val consumptionSeries = chartReadings.map {
        maxOf(0f, it.inverterPowerW.toFloat() + it.powerImportW.toFloat() - it.powerExportW.toFloat())
    }

    // Fixed Y-scale: 5kW = inverter max capacity
    val niceMax = 5000f

    // Pan/zoom state
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val transformState = Modifier.pointerInput(Unit) {
        detectTransformGestures { _, pan, zoom, _ ->
            scale = (scale * zoom).coerceIn(1f, 5f)
            offsetX = (offsetX + pan.x).coerceIn(0f, 10000f)
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .padding(top = 8.dp)
            .then(transformState)
    ) {
        val canvasW = size.width
        val labelW = yLabelWidthPx
        val xLabelH = xLabelHeightPx
        val chartW = canvasW - labelW
        val chartH = size.height - xLabelH

        // Apply zoom: visible window shrinks/grows
        val visibleW = chartW / scale
        val maxOffset = (chartW - visibleW).coerceAtLeast(0f)
        val effectiveOffsetX = if (scale > 1f) offsetX.coerceIn(0f, maxOffset) else 0f

        val gridSteps = 4
        val yPaint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = axisFontSizePx
            textAlign = android.graphics.Paint.Align.RIGHT
            isAntiAlias = true
        }
        for (i in 0..gridSteps) {
            val y = chartH - (chartH * i / gridSteps)
            val value = niceMax * i / gridSteps
            drawLine(
                color = gridColor,
                start = Offset(labelW, y),
                end = Offset(canvasW, y),
                strokeWidth = 1f
            )
            drawContext.canvas.nativeCanvas.drawText(
                String.format(java.util.Locale.US, "%.0f", value),
                labelW - yLabelPaddingPx,
                y + axisFontSizePx / 3f,
                yPaint
            )
        }

        drawLine(color = axisColor, start = Offset(labelW, 0f), end = Offset(labelW, chartH), strokeWidth = 1.5f)
        drawLine(color = axisColor, start = Offset(labelW, chartH), end = Offset(canvasW, chartH), strokeWidth = 1.5f)

        val stepX = if (chartReadings.size > 1) chartW / (chartReadings.size - 1) else chartW

        // Clip to visible area for pan/zoom
        drawContext.canvas.nativeCanvas.save()
        drawContext.canvas.nativeCanvas.clipRect(labelW, 0f, canvasW, chartH)

        fun drawLineSeries(series: List<Float>, color: Color) {
            if (series.size < 2) return
            val paint = android.graphics.Paint().apply {
                this.color = color.toArgb()
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f
            }
            val path = android.graphics.Path()
            for (i in series.indices) {
                val x = labelW + i * stepX - effectiveOffsetX
                val y = chartH - (series[i] / niceMax * chartH)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawContext.canvas.nativeCanvas.drawPath(path, paint)
        }

        drawLineSeries(importSeries, importColor)
        drawLineSeries(exportSeries, exportColor)
        drawLineSeries(inverterSeries, inverterColor)
        drawLineSeries(consumptionSeries, consumptionColor)

        drawContext.canvas.nativeCanvas.restore()

        val xPaint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = axisFontSizePx
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        for (i in chartReadings.indices) {
            if (i % labelEvery == 0) {
                val x = labelW + i * stepX - effectiveOffsetX
                if (x >= labelW && x <= canvasW) {
                    drawContext.canvas.nativeCanvas.drawText(
                        timeLabels[i],
                        x,
                        chartH + xLabelH - yLabelPaddingPx,
                        xPaint
                    )
                }
            }
        }
    }
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
            // Round to 2 decimals so visual addition is correct: Termelés + Vételezés - Betáplálás = Ház
            val rInverter = "%.2f".format(summary.inverterDailyKwh).replace(',', '.').toDouble()
            val rImport = "%.2f".format(summary.p1DailyImportKwh).replace(',', '.').toDouble()
            val rExport = "%.2f".format(summary.p1DailyExportKwh).replace(',', '.').toDouble()
            val rHouse = "%.2f".format(summary.houseDailyKwh).replace(',', '.').toDouble()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DailyStatChip(
                    label = "Napelem Termelés",
                    value = formatKwh(rInverter),
                    color = Color(0xFFF59E0B)
                )
                DailyStatChip(
                    label = "Vételezés",
                    value = formatKwh(rImport),
                    color = MaterialTheme.colorScheme.error
                )
                DailyStatChip(
                    label = "Betáplálás",
                    value = formatKwh(rExport),
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
                        text = "Ház Napi Fogyasztás: ${formatKwh(rHouse)}",
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
