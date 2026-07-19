package com.homeassisthub.client.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homeassisthub.client.R
import com.homeassisthub.client.network.model.P1ReadingDto
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.entry.entryModelOf

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = viewModel()) {
    val plugs by viewModel.plugs.collectAsState()
    val plugStates by viewModel.plugStates.collectAsState()
    val p1History by viewModel.p1History.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = stringResource(R.string.dashboard_title))

        statusMessage?.let { Text(text = it) }

        Text(text = stringResource(R.string.dashboard_power_chart_title))
        P1HistoryChart(readings = p1History)

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        if (plugs.isEmpty()) {
            Text(text = stringResource(R.string.dashboard_no_plugs))
        } else {
            LazyColumn {
                items(plugs) { plug ->
                    val isOn = plugStates[plug.deviceId] ?: false
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = plug.deviceId)
                        Row {
                            Button(onClick = { viewModel.togglePlug(plug.deviceId, turnOn = true) }) {
                                Text(text = stringResource(R.string.plug_turn_on))
                            }
                            Button(onClick = { viewModel.togglePlug(plug.deviceId, turnOn = false) }) {
                                Text(text = stringResource(R.string.plug_turn_off))
                            }
                        }
                    }
                    Text(text = if (isOn) "•" else "◦")
                }
            }
        }
    }
}

@Composable
private fun P1HistoryChart(readings: List<P1ReadingDto>) {
    if (readings.isEmpty()) return

    val entries = readings.mapIndexed { index, reading ->
        com.patrykandpatrick.vico.core.entry.entryOf(index.toFloat(), reading.powerW.toFloat())
    }
    val model = entryModelOf(entries)

    Chart(
        chart = lineChart(),
        model = model,
        startAxis = rememberStartAxis(),
        bottomAxis = rememberBottomAxis(),
        modifier = Modifier.fillMaxWidth().height(200.dp)
    )
}
