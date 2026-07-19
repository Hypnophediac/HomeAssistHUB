package com.homeassisthub.client.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homeassisthub.client.R

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val config = viewModel.config.value
    val discovered by viewModel.discovered.collectAsState()
    val savedDevices by viewModel.savedDevices.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    var relayUrl by remember { mutableStateOf(config.relayUrl) }
    var homeId by remember { mutableStateOf(config.homeId) }
    var hubLocalBaseUrl by remember { mutableStateOf(config.hubLocalBaseUrl) }

    var deviceId by remember { mutableStateOf("") }
    var deviceType by remember { mutableStateOf("smart_plug") }
    var ipAddress by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("80") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(text = stringResource(R.string.settings_title))
        statusMessage?.let { Text(text = it) }

        OutlinedTextField(
            value = relayUrl,
            onValueChange = { relayUrl = it },
            label = { Text(stringResource(R.string.settings_relay_url)) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = homeId,
            onValueChange = { homeId = it },
            label = { Text(stringResource(R.string.settings_home_id)) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = hubLocalBaseUrl,
            onValueChange = { hubLocalBaseUrl = it },
            label = { Text(stringResource(R.string.settings_hub_local_url)) },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { viewModel.saveConfig(relayUrl, homeId, hubLocalBaseUrl) }) {
            Text(text = stringResource(R.string.settings_save))
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Button(onClick = { viewModel.discoverDevices() }) {
            Text(text = stringResource(R.string.settings_discover))
        }
        Text(text = stringResource(R.string.settings_discovered_devices))
        discovered.forEach { device ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "${device.name} (${device.source})")
                Text(text = "${device.ipAddress}:${device.port}")
                TextButton(onClick = {
                    ipAddress = device.ipAddress
                    port = device.port.toString()
                }) {
                    Text(text = "Kiválaszt")
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Text(text = stringResource(R.string.settings_add_credential))
        OutlinedTextField(
            value = deviceId,
            onValueChange = { deviceId = it },
            label = { Text(stringResource(R.string.settings_device_id)) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = deviceType,
            onValueChange = { deviceType = it },
            label = { Text(stringResource(R.string.settings_device_type)) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = ipAddress,
            onValueChange = { ipAddress = it },
            label = { Text("IP") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Port") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.settings_username)) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.settings_password)) },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            viewModel.saveCredential(
                deviceId = deviceId,
                deviceType = deviceType,
                ipAddress = ipAddress,
                port = port.toIntOrNull() ?: 80,
                username = username,
                password = password
            )
        }) {
            Text(text = stringResource(R.string.settings_save_credential))
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Text(text = stringResource(R.string.settings_saved_devices))
        LazyColumn {
            items(savedDevices) { saved ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "${saved.deviceId} (${saved.deviceType}) - ${saved.ipAddress}:${saved.port}")
                    TextButton(onClick = { viewModel.deleteCredential(saved.deviceId) }) {
                        Text(text = "Törlés")
                    }
                }
            }
        }
    }
}
