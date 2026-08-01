package com.homeassisthub.client.ui.energy

import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SolarPower
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homeassisthub.client.network.model.EnergyDailyResponseDto
import com.homeassisthub.client.network.model.EnergyPeriodResponseDto
import com.homeassisthub.client.network.model.P1ReadingDto
import com.homeassisthub.client.ui.components.FreshnessBadge
import com.homeassisthub.client.ui.components.CloudSyncBadge
import com.homeassisthub.client.util.formatKwh

@Composable
fun EnergyDashboardScreen(viewModel: EnergyViewModel = viewModel()) {
    val dailyData by viewModel.dailyData.collectAsState()
    val weeklyData by viewModel.weeklyData.collectAsState()
    val monthlyData by viewModel.monthlyData.collectAsState()
    val yearlyData by viewModel.yearlyData.collectAsState()
    val rangeData by viewModel.rangeData.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val liveReadings by viewModel.liveReadings.collectAsState()
    val liveDailySummary by viewModel.liveDailySummary.collectAsState()
    val pvForecast by viewModel.pvForecast.collectAsState()
    val cloudSyncLastTime by viewModel.cloudSyncLastTime.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("Napi", "Heti", "Havi", "Éves", "Egyedi")
    var showDateRangePicker by remember { mutableStateOf(false) }
    var selectedRangeLabel by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            EnergyHeader(
                onRefresh = { viewModel.refresh() },
                isLoading = isLoading
            )
        }

        statusMessage?.let { msg ->
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        if (liveReadings.isNotEmpty()) {
            item {
                LiveFlowCards(latest = liveReadings.last(), cloudSyncLastTime = cloudSyncLastTime)
            }
        }

        item {
            ForecastCard(
                forecast = pvForecast,
                actualTodayKwh = liveDailySummary?.inverterDailyKwh
            )
        }

        item {
            TabRow(selectedTabIndex = selectedTab) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            if (index == 4) showDateRangePicker = true
                        },
                        text = { Text(title) }
                    )
                }
            }
        }

        when (selectedTab) {
            0 -> {
                dailyData?.let { data ->
                    item { SummaryCards(data = data) }
                    item {
                        // Only show hours up to now — later hours haven't happened
                        // yet today and would always be zero, forcing a pointless
                        // horizontal scroll to see the actual (early) data.
                        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                        val visibleHourly = data.hourly.filter { it.hour <= currentHour }
                        EnergyColumnChart(
                            labels = visibleHourly.map { "${it.hour}h" },
                            consumedValues = visibleHourly.map { it.consumedKwh },
                            exportedValues = visibleHourly.map { it.exportedKwh }
                        )
                    }
                } ?: item { LoadingPlaceholder() }
            }
            1 -> {
                weeklyData?.let { data ->
                    item { PeriodSummaryCards(data = data) }
                    item {
                        EnergyColumnChart(
                            labels = data.entries.map { it.label },
                            consumedValues = data.entries.map { it.consumedKwh },
                            exportedValues = data.entries.map { it.exportedKwh }
                        )
                    }
                } ?: item { LoadingPlaceholder() }
            }
            2 -> {
                monthlyData?.let { data ->
                    item { PeriodSummaryCards(data = data) }
                    item {
                        EnergyColumnChart(
                            labels = data.entries.map { it.label },
                            consumedValues = data.entries.map { it.consumedKwh },
                            exportedValues = data.entries.map { it.exportedKwh }
                        )
                    }
                } ?: item { LoadingPlaceholder() }
            }
            3 -> {
                yearlyData?.let { data ->
                    item { PeriodSummaryCards(data = data) }
                    item {
                        EnergyColumnChart(
                            labels = data.entries.map { it.label },
                            consumedValues = data.entries.map { it.consumedKwh },
                            exportedValues = data.entries.map { it.exportedKwh }
                        )
                    }
                } ?: item { LoadingPlaceholder() }
            }
            4 -> {
                item {
                    OutlinedButton(
                        onClick = { showDateRangePicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = selectedRangeLabel ?: "Válassz dátumtartományt")
                    }
                }
                rangeData?.let { data ->
                    item { PeriodSummaryCards(data = data) }
                    item {
                        EnergyColumnChart(
                            labels = data.entries.map { it.label },
                            consumedValues = data.entries.map { it.consumedKwh },
                            exportedValues = data.entries.map { it.exportedKwh }
                        )
                    }
                } ?: item {
                    Text(
                        text = "Válassz egy dátumtartományt a fenti gombbal.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }

    if (showDateRangePicker) {
        EnergyDateRangePickerDialog(
            onDismiss = { showDateRangePicker = false },
            onConfirm = { startDate, endDate, label ->
                showDateRangePicker = false
                selectedRangeLabel = label
                viewModel.fetchRange(startDate, endDate)
            }
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EnergyDateRangePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (startDate: String, endDate: String, label: String) -> Unit
) {
    val state = androidx.compose.material3.rememberDateRangePickerState()
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val start = state.selectedStartDateMillis
                val end = state.selectedEndDateMillis
                if (start != null && end != null) {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    val startStr = sdf.format(java.util.Date(start))
                    val endStr = sdf.format(java.util.Date(end))
                    onConfirm(startStr, endStr, "$startStr – $endStr")
                }
            }) {
                Text("Alkalmaz")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Mégse") }
        }
    ) {
        androidx.compose.material3.DateRangePicker(state = state, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EnergyHeader(
    onRefresh: () -> Unit,
    isLoading: Boolean
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
                    text = "Energia Monitor",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Vételezés és visszatáplálás",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            FilledIconButton(
                onClick = onRefresh,
                modifier = Modifier.size(48.dp),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Frissítés"
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveFlowCards(latest: P1ReadingDto, cloudSyncLastTime: Long = 0L) {
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
                Text(
                    text = "Élő Adatok",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                FreshnessBadge(timestampMs = latest.timestamp)
                CloudSyncBadge(lastSyncTimeMs = cloudSyncLastTime)
            }
            Spacer(modifier = Modifier.height(12.dp))
            val houseW = maxOf(0.0, latest.realConsumptionW).toInt()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FlowCard(
                    modifier = Modifier.weight(1f),
                    title = "Napelem Termelés",
                    value = if (latest.inverterPowerW > 0.0) "${latest.inverterPowerW.toInt()} W" else "— W",
                    icon = Icons.Filled.SolarPower,
                    color = Color(0xFF10B981)
                )
                FlowCard(
                    modifier = Modifier.weight(1f),
                    title = "Ház Fogyasztás",
                    value = if (latest.inverterPowerW > 0.0 || latest.realConsumptionW > 0.0) "$houseW W" else "— W",
                    icon = Icons.Filled.Bolt,
                    color = Color(0xFF8B5CF6)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FlowCard(
                    modifier = Modifier.weight(1f),
                    title = "Import (Vételezés)",
                    value = "${latest.powerImportW.toInt()} W",
                    icon = Icons.Filled.Bolt,
                    color = Color(0xFFF59E0B)
                )
                FlowCard(
                    modifier = Modifier.weight(1f),
                    title = "Export (Betáplálás)",
                    value = "${latest.powerExportW.toInt()} W",
                    icon = Icons.Filled.SolarPower,
                    color = Color(0xFF3B82F6)
                )
            }
        }
    }
}

@Composable
private fun FlowCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ForecastCard(
    forecast: com.homeassisthub.client.data.PvForecastResult?,
    actualTodayKwh: Double?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.WbSunny,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Termelési Előrejelzés",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (forecast == null) {
                Text(
                    text = "Állítsd be a helyszínt és a napelem kapacitást a Beállításokban az időjárás-alapú előrejelzéshez.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FlowCard(
                        modifier = Modifier.weight(1f),
                        title = "Mára várható",
                        value = formatKwh(forecast.estimatedTodayKwh, decimals = 1),
                        icon = Icons.Filled.WbSunny,
                        color = Color(0xFFF59E0B)
                    )
                    FlowCard(
                        modifier = Modifier.weight(1f),
                        title = "Eddig termelt",
                        value = actualTodayKwh?.let { formatKwh(it, decimals = 1) } ?: "— kWh",
                        icon = Icons.Filled.SolarPower,
                        color = Color(0xFF10B981)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                val tempText = forecast.currentTemperatureC?.let { "%.0f°C".format(it) } ?: "—"
                val cloudText = forecast.currentCloudCoverPercent?.let { "${it.toInt()}% felhőzet" } ?: ""
                Text(
                    text = "Jelenleg: $tempText, $cloudText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SummaryCards(data: EnergyDailyResponseDto) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryCard(
            modifier = Modifier.weight(1f),
            title = "Vételezés",
            value = formatKwh(data.totalConsumedKwh),
            color = Color(0xFFE65100),
            icon = Icons.Filled.Bolt
        )
        SummaryCard(
            modifier = Modifier.weight(1f),
            title = "Visszatáplálás",
            value = formatKwh(data.totalExportedKwh),
            color = Color(0xFF2E7D32),
            icon = Icons.Filled.SolarPower
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Vételezés",
            value = "${data.latestPowerImportW.toInt()} W"
        )
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Visszatáplálás",
            value = "${data.latestPowerExportW.toInt()} W"
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Feszültség",
            value = "%.0f / %.0f / %.0f V".format(data.latestL1V, data.latestL2V, data.latestL3V)
        )
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Áramerősség",
            value = "%.1f / %.1f / %.1f A".format(data.latestL1A, data.latestL2A, data.latestL3A)
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Power Factor",
            value = "%.3f".format(data.latestPowerFactor)
        )
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Frekvencia",
            value = "%.2f Hz".format(data.latestFrequencyHz)
        )
    }
    // ── Daily Statistics ──
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "Napi statisztika",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Min. teljesítmény",
            value = "${data.minPowerW.toInt()} W"
        )
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Max. teljesítmény",
            value = "${data.maxPowerW.toInt()} W"
        )
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Átlag teljesítmény",
            value = "${data.avgPowerW.toInt()} W"
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Max. vételezés",
            value = "${data.maxImportW.toInt()} W"
        )
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Max. visszatáplálás",
            value = "${data.maxExportW.toInt()} W"
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Csúcs vételezés",
            value = if (data.peakConsumptionHour >= 0) "${data.peakConsumptionHour}:00 (${formatKwh(data.peakConsumptionKwh)})" else "N/A"
        )
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Csúcs visszatáplálás",
            value = if (data.peakExportHour >= 0) "${data.peakExportHour}:00 (${formatKwh(data.peakExportKwh)})" else "N/A"
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Önfogyasztási arány",
            value = "%.1f%%".format(data.selfConsumptionRatio * 100)
        )
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Hálózati egyenleg",
            value = formatKwh(data.netEnergyKwh)
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Tariff 1 (nappal)",
            value = formatKwh(data.importT1Kwh)
        )
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Tariff 2 (éjszaka)",
            value = formatKwh(data.importT2Kwh)
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Export T1",
            value = formatKwh(data.exportT1Kwh)
        )
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Export T2",
            value = formatKwh(data.exportT2Kwh)
        )
    }
    // ── Per-phase daily averages ──
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "Napi átlagok fázisonként",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Átlag feszültség",
            value = "%.0f / %.0f / %.0f V".format(data.avgL1V, data.avgL2V, data.avgL3V)
        )
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Átlag áramerősség",
            value = "%.1f / %.1f / %.1f A".format(data.avgL1A, data.avgL2A, data.avgL3A)
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Átlag power factor",
            value = "%.3f".format(data.avgPowerFactor)
        )
        LiveStatCard(
            modifier = Modifier.weight(1f),
            title = "Átlag frekvencia",
            value = "%.2f Hz".format(data.avgFrequencyHz)
        )
    }
}

@Composable
private fun PeriodSummaryCards(data: EnergyPeriodResponseDto) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryCard(
            modifier = Modifier.weight(1f),
            title = "Vételezés",
            value = formatKwh(data.totalConsumedKwh),
            color = Color(0xFFE65100),
            icon = Icons.Filled.Bolt
        )
        SummaryCard(
            modifier = Modifier.weight(1f),
            title = "Visszatáplálás",
            value = formatKwh(data.totalExportedKwh),
            color = Color(0xFF2E7D32),
            icon = Icons.Filled.SolarPower
        )
    }
    if (data.totalProducedKwh > 0.0) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SummaryCard(
                modifier = Modifier.weight(1f),
                title = "Összes Termelés",
                value = formatKwh(data.totalProducedKwh),
                color = Color(0xFF10B981),
                icon = Icons.Filled.WbSunny
            )
            val selfConsumptionRatio = if (data.totalProducedKwh > 0.0) {
                ((data.totalProducedKwh - data.totalExportedKwh).coerceAtLeast(0.0) / data.totalProducedKwh) * 100.0
            } else 0.0
            SummaryCard(
                modifier = Modifier.weight(1f),
                title = "Önfogyasztási arány",
                value = "%.1f%%".format(selfConsumptionRatio),
                color = Color(0xFF8B5CF6),
                icon = Icons.Filled.Bolt
            )
        }
    }
}

@Composable
private fun SummaryCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LiveStatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val ConsumedBarColor = Color(0xFFE65100)
private val ExportedBarColor = Color(0xFF2E7D32)
private const val MIN_CHART_SCALE = 1.0f
private const val MAX_CHART_SCALE = 10.0f

/**
 * Interactive wrapper around [EnergyColumnChart]: adds a fullscreen button
 * that opens the same chart in an enlarged Dialog with the same gestures.
 */
@Composable
private fun EnergyColumnChart(
    labels: List<String>,
    consumedValues: List<Double>,
    exportedValues: List<Double>
) {
    if (consumedValues.isEmpty()) {
        LoadingPlaceholder()
        return
    }

    var showFullscreen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        ZoomableEnergyColumnChart(
            labels = labels,
            consumedValues = consumedValues,
            exportedValues = exportedValues,
            height = 240.dp
        )
        FilledIconButton(
            onClick = { showFullscreen = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp)
                .size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Fullscreen,
                contentDescription = "Teljes képernyő",
                modifier = Modifier.size(18.dp)
            )
        }
    }

    if (showFullscreen) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showFullscreen = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    ZoomableEnergyColumnChart(
                        labels = labels,
                        consumedValues = consumedValues,
                        exportedValues = exportedValues,
                        height = 480.dp,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    )
                    FilledIconButton(
                        onClick = { showFullscreen = false },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FullscreenExit,
                            contentDescription = "Bezárás"
                        )
                    }
                }
            }
        }
    }
}

/**
 * Custom Canvas-based grouped bar chart with pinch-to-zoom (horizontal,
 * clamped 1x-10x), drag-to-pan, and double-tap-to-reset gestures. Unlike
 * Vico's columnChart, this always renders using the full available width
 * as the 1x baseline (no hidden horizontal scroll that silently clips data
 * off-screen at rest).
 */
@Composable
private fun ZoomableEnergyColumnChart(
    labels: List<String>,
    consumedValues: List<Double>,
    exportedValues: List<Double>,
    height: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    var scale by remember(labels) { mutableStateOf(1f) }
    var offsetX by remember(labels) { mutableStateOf(0f) }

    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = androidx.compose.ui.platform.LocalDensity.current
    val axisFontSizePx = with(density) { 10.sp.toPx() }
    val yLabelPaddingPx = with(density) { 4.dp.toPx() }
    val xLabelHeightPx = with(density) { 16.dp.toPx() }
    val yLabelWidthPx = with(density) { 44.dp.toPx() }

    val maxDataValue = maxOf(
        consumedValues.maxOrNull()?.toFloat() ?: 1f,
        exportedValues.maxOrNull()?.toFloat() ?: 1f
    )
    val niceMax = maxOf(maxDataValue * 1.15f, 0.1f)

    androidx.compose.foundation.Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(top = 8.dp)
            .pointerInput(labels) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(MIN_CHART_SCALE, MAX_CHART_SCALE)
                    scale = newScale
                    offsetX += pan.x
                }
            }
            .pointerInput(labels) {
                detectTapGestures(onDoubleTap = {
                    scale = MIN_CHART_SCALE
                    offsetX = 0f
                })
            }
    ) {
        val canvasW = size.width
        val labelW = yLabelWidthPx
        val xLabelH = xLabelHeightPx
        val chartW = canvasW - labelW
        val chartH = size.height - xLabelH

        val contentW = chartW * scale
        val maxOffset = (contentW - chartW).coerceAtLeast(0f)
        offsetX = offsetX.coerceIn(-maxOffset, 0f)

        val barGroupW = if (consumedValues.isNotEmpty()) contentW / consumedValues.size else 0f
        val barW = barGroupW * 0.38f

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
                String.format(java.util.Locale.US, "%.1f", value),
                labelW - yLabelPaddingPx,
                y + axisFontSizePx / 3f,
                yPaint
            )
        }

        drawLine(color = axisColor, start = Offset(labelW, 0f), end = Offset(labelW, chartH), strokeWidth = 1.5f)
        drawLine(color = axisColor, start = Offset(labelW, chartH), end = Offset(canvasW, chartH), strokeWidth = 1.5f)

        val visibleGroupCount = if (barGroupW > 0f) (chartW / barGroupW).toInt() + 2 else labels.size
        val labelEvery = if (visibleGroupCount <= 12) 1 else kotlin.math.max(1, visibleGroupCount / 8)
        val xPaint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = axisFontSizePx
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }

        clipRect(left = labelW, top = 0f, right = canvasW, bottom = size.height) {
            consumedValues.forEachIndexed { index, consumed ->
                val groupX = labelW + offsetX + index * barGroupW
                if (groupX + barGroupW < labelW || groupX > canvasW) return@forEachIndexed

                val consumedH = (consumed / niceMax * chartH).toFloat()
                drawRect(
                    color = ConsumedBarColor,
                    topLeft = Offset(groupX + barGroupW * 0.08f, chartH - consumedH),
                    size = Size(barW, consumedH)
                )
                val exported = exportedValues.getOrElse(index) { 0.0 }
                val exportedH = (exported / niceMax * chartH).toFloat()
                drawRect(
                    color = ExportedBarColor,
                    topLeft = Offset(groupX + barGroupW * 0.54f, chartH - exportedH),
                    size = Size(barW, exportedH)
                )

                if (index % labelEvery == 0) {
                    drawContext.canvas.nativeCanvas.drawText(
                        labels.getOrElse(index) { "" },
                        groupX + barGroupW / 2f,
                        chartH + xLabelH - yLabelPaddingPx,
                        xPaint
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
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
}
