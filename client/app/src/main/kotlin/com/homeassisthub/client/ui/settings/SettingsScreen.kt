package com.homeassisthub.client.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.SolarPower
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homeassisthub.client.R

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val config = viewModel.config.value
    val pvConfig = viewModel.pvForecastConfig.value
    val discovered by viewModel.discovered.collectAsState()
    val savedDevices by viewModel.savedDevices.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    var relayUrl by remember { mutableStateOf(config.relayUrl) }
    var homeId by remember { mutableStateOf(config.homeId) }
    var hubLocalBaseUrl by remember { mutableStateOf(config.hubLocalBaseUrl) }
    var syncToken by remember { mutableStateOf(config.syncToken) }
    var kioskUrl by remember { mutableStateOf("") }

    var locationName by remember { mutableStateOf(pvConfig.locationName) }
    var selectedLat by remember { mutableStateOf(pvConfig.latitude) }
    var selectedLon by remember { mutableStateOf(pvConfig.longitude) }
    var pvCapacityKwp by remember { mutableStateOf(pvConfig.pvCapacityKwp?.toString() ?: "") }
    var performanceRatioPercent by remember { mutableStateOf((pvConfig.performanceRatio * 100).toInt().toString()) }
    val geocodingResults by viewModel.geocodingResults.collectAsState()
    val isGeocoding by viewModel.isGeocoding.collectAsState()

    val baseline by viewModel.baseline.collectAsState()

    var baselineImport by remember { mutableStateOf("") }
    var baselineExport by remember { mutableStateOf("") }
    var baselineImportT1 by remember { mutableStateOf("") }
    var baselineImportT2 by remember { mutableStateOf("") }
    var baselineExportT1 by remember { mutableStateOf("") }
    var baselineExportT2 by remember { mutableStateOf("") }
    var baselineDate by remember { mutableStateOf("") }

    var deviceId by remember { mutableStateOf("") }
    var deviceType by remember { mutableStateOf("smart_plug") }
    var ipAddress by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("80") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SettingsHeader(
                title = stringResource(R.string.settings_title),
                modifier = Modifier.padding(top = 16.dp)
            )
        }

        statusMessage?.let { msg ->
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        item {
            ConnectionCard(
                relayUrl = relayUrl,
                onRelayUrlChange = { relayUrl = it },
                homeId = homeId,
                onHomeIdChange = { homeId = it },
                hubLocalBaseUrl = hubLocalBaseUrl,
                onHubLocalBaseUrlChange = { hubLocalBaseUrl = it },
                syncToken = syncToken,
                onSyncTokenChange = { syncToken = it },
                onSave = { viewModel.saveConfig(relayUrl, homeId, hubLocalBaseUrl, syncToken) }
            )
        }

        item {
            DiscoveryCard(
                onDiscover = { viewModel.discoverDevices() },
                discovered = discovered,
                onSelectDevice = { dev ->
                    ipAddress = dev.ipAddress
                    port = dev.port.toString()
                }
            )
        }

        item {
            AddCredentialCard(
                deviceId = deviceId,
                onDeviceIdChange = { deviceId = it },
                deviceType = deviceType,
                onDeviceTypeChange = { deviceType = it },
                ipAddress = ipAddress,
                onIpAddressChange = { ipAddress = it },
                port = port,
                onPortChange = { port = it },
                username = username,
                onUsernameChange = { username = it },
                password = password,
                onPasswordChange = { password = it },
                onSave = {
                    viewModel.saveCredential(
                        deviceId = deviceId,
                        deviceType = deviceType,
                        ipAddress = ipAddress,
                        port = port.toIntOrNull() ?: 80,
                        username = username,
                        password = password
                    )
                }
            )
        }

        item {
            SavedDevicesCard(
                savedDevices = savedDevices,
                onDelete = { viewModel.deleteCredential(it) }
            )
        }

        item {
            KioskUrlCard(
                kioskUrl = kioskUrl,
                onKioskUrlChange = { kioskUrl = it },
                onSave = { viewModel.saveKioskUrl(kioskUrl) },
                onLoad = { viewModel.loadKioskUrl() }
            )
        }

        item {
            BaselineSettingsCard(
                importKwh = baselineImport.ifBlank { baseline.importKwh },
                onImportKwhChange = { baselineImport = it },
                exportKwh = baselineExport.ifBlank { baseline.exportKwh },
                onExportKwhChange = { baselineExport = it },
                importT1 = baselineImportT1.ifBlank { baseline.importT1 },
                onImportT1Change = { baselineImportT1 = it },
                importT2 = baselineImportT2.ifBlank { baseline.importT2 },
                onImportT2Change = { baselineImportT2 = it },
                exportT1 = baselineExportT1.ifBlank { baseline.exportT1 },
                onExportT1Change = { baselineExportT1 = it },
                exportT2 = baselineExportT2.ifBlank { baseline.exportT2 },
                onExportT2Change = { baselineExportT2 = it },
                date = baselineDate.ifBlank { baseline.date },
                onDateChange = { baselineDate = it },
                onSave = { viewModel.saveBaseline(
                    baselineImport.ifBlank { baseline.importKwh },
                    baselineExport.ifBlank { baseline.exportKwh },
                    baselineDate.ifBlank { baseline.date },
                    baselineImportT1.ifBlank { baseline.importT1 },
                    baselineImportT2.ifBlank { baseline.importT2 },
                    baselineExportT1.ifBlank { baseline.exportT1 },
                    baselineExportT2.ifBlank { baseline.exportT2 }
                )},
                onLoad = { viewModel.loadBaseline() }
            )
        }

        item {
            PvForecastCard(
                locationName = locationName,
                onLocationNameChange = { locationName = it },
                selectedLat = selectedLat,
                selectedLon = selectedLon,
                pvCapacityKwp = pvCapacityKwp,
                onPvCapacityKwpChange = { pvCapacityKwp = it },
                performanceRatioPercent = performanceRatioPercent,
                onPerformanceRatioPercentChange = { performanceRatioPercent = it },
                geocodingResults = geocodingResults,
                isGeocoding = isGeocoding,
                onSearch = { viewModel.searchLocation(locationName) },
                onSelectResult = { result ->
                    locationName = result.name + (result.admin1?.let { ", $it" } ?: "") + (result.country?.let { ", $it" } ?: "")
                    selectedLat = result.latitude
                    selectedLon = result.longitude
                    viewModel.clearGeocodingResults()
                },
                onSave = {
                    viewModel.savePvForecastConfig(locationName, selectedLat, selectedLon, pvCapacityKwp, performanceRatioPercent)
                }
            )
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun SettingsHeader(title: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun SectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ConnectionCard(
    relayUrl: String,
    onRelayUrlChange: (String) -> Unit,
    homeId: String,
    onHomeIdChange: (String) -> Unit,
    hubLocalBaseUrl: String,
    onHubLocalBaseUrlChange: (String) -> Unit,
    syncToken: String,
    onSyncTokenChange: (String) -> Unit,
    onSave: () -> Unit
) {
    SectionCard(icon = Icons.Filled.Cloud, title = "Relé kapcsolat") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = relayUrl,
                onValueChange = onRelayUrlChange,
                label = { Text(stringResource(R.string.settings_relay_url)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = homeId,
                onValueChange = onHomeIdChange,
                label = { Text(stringResource(R.string.settings_home_id)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = hubLocalBaseUrl,
                onValueChange = onHubLocalBaseUrlChange,
                label = { Text(stringResource(R.string.settings_hub_local_url)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = syncToken,
                onValueChange = onSyncTokenChange,
                label = { Text("Sync Token (Cloud Sync)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("A Hub által generált token") }
            )
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Save,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = stringResource(R.string.settings_save))
            }
        }
    }
}

@Composable
private fun DiscoveryCard(
    onDiscover: () -> Unit,
    discovered: List<com.homeassisthub.client.network.model.DiscoveredDeviceDto>,
    onSelectDevice: (com.homeassisthub.client.network.model.DiscoveredDeviceDto) -> Unit
) {
    SectionCard(icon = Icons.Filled.Radar, title = "Eszköz keresés") {
        FilledTonalButton(
            onClick = onDiscover,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Filled.Sensors,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = stringResource(R.string.settings_discover))
        }

        if (discovered.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.settings_discovered_devices),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            discovered.forEach { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = device.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${device.ipAddress}:${device.port} (${device.source})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { onSelectDevice(device) }) {
                            Text(text = "Kiválaszt")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddCredentialCard(
    deviceId: String,
    onDeviceIdChange: (String) -> Unit,
    deviceType: String,
    onDeviceTypeChange: (String) -> Unit,
    ipAddress: String,
    onIpAddressChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    onSave: () -> Unit
) {
    SectionCard(icon = Icons.Filled.Add, title = stringResource(R.string.settings_add_credential)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = deviceId,
                onValueChange = onDeviceIdChange,
                label = { Text(stringResource(R.string.settings_device_id)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = deviceType,
                onValueChange = onDeviceTypeChange,
                label = { Text(stringResource(R.string.settings_device_type)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = onIpAddressChange,
                    label = { Text("IP cím") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = onPortChange,
                    label = { Text("Port") },
                    modifier = Modifier.width(100.dp),
                    singleLine = true
                )
            }
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.settings_username)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.settings_password)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Save,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = stringResource(R.string.settings_save_credential))
            }
        }
    }
}

@Composable
private fun SavedDevicesCard(
    savedDevices: List<com.homeassisthub.client.network.model.DeviceCredentialSummaryDto>,
    onDelete: (String) -> Unit
) {
    SectionCard(icon = Icons.Filled.Devices, title = stringResource(R.string.settings_saved_devices)) {
        if (savedDevices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Még nincs mentett eszköz.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            savedDevices.forEach { saved ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Home,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = saved.deviceId,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${saved.deviceType} - ${saved.ipAddress}:${saved.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = { onDelete(saved.deviceId) }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Törlés",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "Törlés")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KioskUrlCard(
    kioskUrl: String,
    onKioskUrlChange: (String) -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit
) {
    SectionCard(icon = Icons.Filled.SolarPower, title = "Huawei FusionSolar Kiosk") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Illeszd be a FusionSolar Kiosk URL-t. A Hub regex-szel kinyeri a kk tokent és a szerver domaint.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = kioskUrl,
                onValueChange = onKioskUrlChange,
                label = { Text("Kiosk URL") },
                placeholder = { Text("https://uni002eu5.fusionsolar.huawei.com/...?kk=...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Save,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Mentés")
                }
                OutlinedButton(
                    onClick = onLoad,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "Betöltés")
                }
            }
        }
    }
}

@Composable
private fun BaselineSettingsCard(
    importKwh: String,
    onImportKwhChange: (String) -> Unit,
    exportKwh: String,
    onExportKwhChange: (String) -> Unit,
    importT1: String,
    onImportT1Change: (String) -> Unit,
    importT2: String,
    onImportT2Change: (String) -> Unit,
    exportT1: String,
    onExportT1Change: (String) -> Unit,
    exportT2: String,
    onExportT2Change: (String) -> Unit,
    date: String,
    onDateChange: (String) -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit
) {
    SectionCard(icon = Icons.Filled.Save, title = "Elszámolási nyitóértékek (MVM)") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "A P1 mérőóra kumulált állásai az utolsó hivatalos MVM leolvasás napján. Ezekből számolódik az éves vételezés/visszatáplálás az Energia fül Éves tabján. A tarifánkénti (T1/T2) értékek opcionálisak.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = importKwh,
                onValueChange = { onImportKwhChange(it.filter { c -> c.isDigit() || c == '.' || c == ',' }) },
                label = { Text("Nyitó vételezés összesen (kWh)") },
                placeholder = { Text("8779.0") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = importT1,
                    onValueChange = { onImportT1Change(it.filter { c -> c.isDigit() || c == '.' || c == ',' }) },
                    label = { Text("Import T1 (nappal)") },
                    placeholder = { Text("5000.0") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = importT2,
                    onValueChange = { onImportT2Change(it.filter { c -> c.isDigit() || c == '.' || c == ',' }) },
                    label = { Text("Import T2 (éjszaka)") },
                    placeholder = { Text("3779.0") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = exportKwh,
                onValueChange = { onExportKwhChange(it.filter { c -> c.isDigit() || c == '.' || c == ',' }) },
                label = { Text("Nyitó visszatáplálás összesen (kWh)") },
                placeholder = { Text("5000.0") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = exportT1,
                    onValueChange = { onExportT1Change(it.filter { c -> c.isDigit() || c == '.' || c == ',' }) },
                    label = { Text("Export T1") },
                    placeholder = { Text("3000.0") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = exportT2,
                    onValueChange = { onExportT2Change(it.filter { c -> c.isDigit() || c == '.' || c == ',' }) },
                    label = { Text("Export T2") },
                    placeholder = { Text("2000.0") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = date,
                onValueChange = { onDateChange(it) },
                label = { Text("Leolvasás dátuma") },
                placeholder = { Text("2026-01-14") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Mentés")
                }
                OutlinedButton(
                    onClick = onLoad,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Betöltés")
                }
            }
        }
    }
}

@Composable
private fun PvForecastCard(
    locationName: String,
    onLocationNameChange: (String) -> Unit,
    selectedLat: Double?,
    selectedLon: Double?,
    pvCapacityKwp: String,
    onPvCapacityKwpChange: (String) -> Unit,
    performanceRatioPercent: String,
    onPerformanceRatioPercentChange: (String) -> Unit,
    geocodingResults: List<com.homeassisthub.client.network.GeocodingService.GeocodingResult>,
    isGeocoding: Boolean,
    onSearch: () -> Unit,
    onSelectResult: (com.homeassisthub.client.network.GeocodingService.GeocodingResult) -> Unit,
    onSave: () -> Unit
) {
    SectionCard(icon = Icons.Filled.WbSunny, title = "Napelem & Helyszín (Előrejelzés)") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "A helyszín és a napelem kapacitás szükséges az Energia fülön megjelenő időjárás-alapú termelési előrejelzéshez.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                OutlinedTextField(
                    value = locationName,
                    onValueChange = onLocationNameChange,
                    label = { Text("Helyszín") },
                    placeholder = { Text("pl. Budapest, Debrecen, Győr") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )
                FilledTonalButton(
                    onClick = onSearch,
                    enabled = locationName.isNotBlank() && !isGeocoding,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    if (isGeocoding) {
                        Text("...")
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Keresés")
                    }
                }
            }
            if (geocodingResults.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "Válassz helyszínt:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        geocodingResults.forEach { result ->
                            TextButton(
                                onClick = { onSelectResult(result) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text(
                                        text = result.name + (result.admin1?.let { ", $it" } ?: ""),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = (result.country ?: "") + "  (${String.format("%.4f", result.latitude)}, ${String.format("%.4f", result.longitude)})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (selectedLat != null && selectedLon != null) {
                Text(
                    text = "Kiválasztott: ${String.format("%.4f", selectedLat)}, ${String.format("%.4f", selectedLon)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = pvCapacityKwp,
                    onValueChange = onPvCapacityKwpChange,
                    label = { Text("PV kapacitás (kWp)") },
                    placeholder = { Text("5.5") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = performanceRatioPercent,
                    onValueChange = onPerformanceRatioPercentChange,
                    label = { Text("Rendszer hatásfok (%)") },
                    placeholder = { Text("80") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            Button(
                onClick = onSave,
                enabled = selectedLat != null && selectedLon != null && pvCapacityKwp.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Save,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Mentés")
            }
        }
    }
}
