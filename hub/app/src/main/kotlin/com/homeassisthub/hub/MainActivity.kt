package com.homeassisthub.hub

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.app.ActivityManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.homeassisthub.hub.security.DeviceCredential
import com.homeassisthub.hub.security.SecureCredentialStore
import com.homeassisthub.hub.data.HubConfigStore
import com.homeassisthub.hub.service.HubForegroundService

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        // Auto-start the foreground service on app launch
        ContextCompat.startForegroundService(this, HubForegroundService.startIntent(this))

        val credentialStore = SecureCredentialStore(this)
        val activity = this

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HubControlScreen(
                        credentialStore = credentialStore,
                        hubConfigStore = HubConfigStore(activity),
                        onStart = { ContextCompat.startForegroundService(activity, HubForegroundService.startIntent(activity)) },
                        onStop = { activity.startService(HubForegroundService.stopIntent(activity)) },
                        onRestartService = {
                            activity.startService(HubForegroundService.stopIntent(activity))
                            Thread.sleep(500)
                            ContextCompat.startForegroundService(activity, HubForegroundService.startIntent(activity))
                        }
                    )
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun HubControlScreen(
    credentialStore: SecureCredentialStore,
    hubConfigStore: HubConfigStore,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestartService: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isRunning by remember { mutableStateOf(isServiceRunning(context)) }

    // Poll service state every 2 seconds so the UI reflects actual state
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            isRunning = isServiceRunning(context)
            kotlinx.coroutines.delay(2000)
        }
    }

    val existingCredential = credentialStore.getCredential("p1_meter")
    var ipText by remember { mutableStateOf(existingCredential?.ipAddress ?: "") }
    var portText by remember { mutableStateOf(existingCredential?.port?.toString() ?: "8989") }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = if (isRunning) stringResource(R.string.service_running) else stringResource(R.string.service_stopped),
            style = MaterialTheme.typography.bodyMedium
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                onStart()
                isRunning = true
            }) {
                Text(text = stringResource(R.string.start_service))
            }
            Button(onClick = {
                onStop()
                isRunning = false
            }) {
                Text(text = stringResource(R.string.stop_service))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "P1 Smart Meter beállítás",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        OutlinedTextField(
            value = ipText,
            onValueChange = { ipText = it },
            label = { Text("IP cím") },
            placeholder = { Text("192.168.0.148") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter { c -> c.isDigit() } },
            label = { Text("Port") },
            placeholder = { Text("8989") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(onClick = {
            val ip = ipText.trim()
            val port = portText.trim().toIntOrNull() ?: 8989
            if (ip.isEmpty()) {
                savedMessage = "IP cím megadása kötelező!"
                return@Button
            }
            credentialStore.saveCredential(
                DeviceCredential(
                    deviceId = "p1_meter",
                    deviceType = "p1_meter",
                    ipAddress = ip,
                    port = port,
                    username = "",
                    password = ""
                )
            )
            savedMessage = "P1 meter elmentve! Szolgáltatás újraindítása..."
            onRestartService()
            isRunning = true
        }) {
            Text("Mentés és szolgáltatás újraindítása")
        }

        savedMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Cloud Sync",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        val hubConfig = hubConfigStore.getConfig()
        Text(
            text = "Relé URL: ${hubConfig?.relayUrl ?: "nincs beállítva"}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = "Home ID: ${hubConfig?.homeId ?: "nincs beállítva"}",
            style = MaterialTheme.typography.bodySmall
        )

        var syncToken by remember { mutableStateOf(hubConfig?.syncToken ?: "") }

        if (syncToken.isNotBlank()) {
            Text(
                text = "Sync Token:",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = syncToken,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Másold be ezt a tokent a Kliens Beállításokba.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        Button(onClick = {
            syncToken = hubConfigStore.generateSyncToken()
            savedMessage = "Új sync token generálva!"
        }) {
            Text(if (syncToken.isBlank()) "Sync token generálása" else "Új token generálása")
        }
    }
}

private fun isServiceRunning(context: android.content.Context): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    @Suppress("DEPRECATION")
    val services = manager.getRunningServices(Int.MAX_VALUE)
    return services.any { it.service.className == "com.homeassisthub.hub.service.HubForegroundService" }
}
