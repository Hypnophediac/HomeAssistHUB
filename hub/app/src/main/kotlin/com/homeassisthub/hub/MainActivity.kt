package com.homeassisthub.hub

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.app.ActivityManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.homeassisthub.hub.controller.HubLogBuffer
import com.homeassisthub.hub.controller.InverterLiveData
import com.homeassisthub.hub.controller.P1HistoryBuffer
import com.homeassisthub.hub.security.DeviceCredential
import com.homeassisthub.hub.security.SecureCredentialStore
import com.homeassisthub.hub.data.HubConfigStore
import com.homeassisthub.hub.service.HubForegroundService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        requestIgnoreBatteryOptimizationsIfNeeded()

        // Auto-start the foreground service on app launch
        ContextCompat.startForegroundService(this, HubForegroundService.startIntent(this))

        val credentialStore = SecureCredentialStore(this)
        val activity = this

        setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HubDashboard(
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

    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                    setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (_: Exception) {
                // Some MIUI/HyperOS builds don't support this intent; fall back
                // to the general battery optimization settings page.
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { /* give up silently */ }
            }
        }
    }
}

@Composable
private fun HubDashboard(
    credentialStore: SecureCredentialStore,
    hubConfigStore: HubConfigStore,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestartService: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isRunning by remember { mutableStateOf(isServiceRunning(context)) }
    var tick by remember { mutableStateOf(0L) }
    var showSettings by remember { mutableStateOf(false) }

    // Poll service state + live data every 2 seconds
    LaunchedEffect(Unit) {
        while (true) {
            isRunning = isServiceRunning(context)
            tick = System.currentTimeMillis()
            kotlinx.coroutines.delay(2000)
        }
    }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Header ──
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "HomeAssist Hub",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = timeFmt.format(Date(tick)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusBadge(isRunning)
            }
        }

        // ── Service controls ──
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Szolgáltatás", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onStart, enabled = !isRunning) {
                            Text("Start")
                        }
                        Button(onClick = onStop, enabled = isRunning) {
                            Text("Stop")
                        }
                        Button(onClick = onRestartService, enabled = isRunning) {
                            Text("Restart")
                        }
                    }
                }
            }
        }

        // ── Live P1 Meter ──
        item { P1StatusCard(tick) }

        // ── Live Inverter ──
        item { InverterStatusCard(tick) }

        // ── Cloud Sync ──
        item { CloudSyncCard(hubConfigStore, tick) }

        // ── Config ──
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Konfiguráció", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Button(onClick = { showSettings = !showSettings }) {
                            Text(if (showSettings) "Elrejt" else "Szerkeszt")
                        }
                    }
                    val hubConfig = hubConfigStore.getConfig()
                    Text("Relé: ${hubConfig?.relayUrl ?: "—"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Home ID: ${hubConfig?.homeId ?: "—"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Kiosk: ${if (hubConfig?.kioskUrl.isNullOrBlank()) "—" else "✓"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Token: ${if (hubConfig?.syncToken.isNullOrBlank()) "—" else hubConfig!!.syncToken.take(8) + "…"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    if (showSettings) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        P1ConfigSection(credentialStore, onRestartService)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        CloudSyncConfigSection(hubConfigStore)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        BaselineConfigSection(hubConfigStore)
                    }
                }
            }
        }

        // ── Log viewer ──
        item { LogViewerCard(tick) }
    }
}

@Composable
private fun StatusBadge(isRunning: Boolean) {
    val color = if (isRunning) Color(0xFF4CAF50) else Color(0xFFF44336)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(
            text = if (isRunning) "Fut" else "Leállítva",
            style = MaterialTheme.typography.labelLarge,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun P1StatusCard(tick: Long) {
    val snap = P1HistoryBuffer.latestSnapshot
    val (dailyImport, dailyExport) = P1HistoryBuffer.getDailyKwhDeltas()
    val hasData = snap != null
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("P1 Smart Meter", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (hasData) {
                InfoRow("Import (hálózatból)", "${snap!!.powerImportW.toInt()} W")
                InfoRow("Export (hálózatba)", "${snap!!.powerExportW.toInt()} W")
                InfoRow("Napi import", "${"%.2f".format(dailyImport)} kWh")
                InfoRow("Napi export", "${"%.2f".format(dailyExport)} kWh")
            } else {
                Text("Nincs adat", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun InverterStatusCard(tick: Long) {
    val isFresh = InverterLiveData.isFresh()
    val power = InverterLiveData.activePowerW
    val daily = InverterLiveData.dailyEnergyKwh
    val consumption = InverterLiveData.realConsumptionW
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Huawei Inverter (Kiosk)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (isFresh) {
                InfoRow("Termelés", "${power.toInt()} W")
                InfoRow("Napi yield", "${"%.2f".format(daily)} kWh")
                InfoRow("Ház fogyasztás", "${consumption.toInt()} W")
            } else {
                Text("Nincs friss adat ( >30 min )", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CloudSyncCard(hubConfigStore: HubConfigStore, tick: Long) {
    val lastSync = hubConfigStore.getLastSyncTime()
    val cursor = hubConfigStore.getSyncCursor()
    val syncAge = if (lastSync > 0) {
        val secs = (System.currentTimeMillis() - lastSync) / 1000
        when {
            secs < 60 -> "${secs}s ezelőtt"
            secs < 3600 -> "${secs / 60}p ezelőtt"
            else -> "${secs / 3600}ó ezelőtt"
        }
    } else "soha"
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Cloud Sync (Render/MongoDB)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            InfoRow("Utolsó sync", syncAge)
            InfoRow("Cursor", if (cursor > 0) "${cursor}" else "—")
            val isStale = lastSync > 0 && (System.currentTimeMillis() - lastSync) > 5 * 60 * 1000L
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (isStale) Color(0xFFFF9800) else Color(0xFF4CAF50)))
                Text(if (isStale) "Késés" else "OK", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LogViewerCard(tick: Long) {
    val entries = remember(tick) { HubLogBuffer.latestEntries }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Log", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF00FF00))
                Text("${entries.size} bejegyzés", style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888))
            }
            if (entries.isEmpty()) {
                Text("(üres)", style = MaterialTheme.typography.bodySmall, color = Color(0xFF666666))
            } else {
                entries.takeLast(50).reversed().forEach { entry ->
                    val levelColor = when (entry.level) {
                        HubLogBuffer.Level.ERROR -> Color(0xFFFF4444)
                        HubLogBuffer.Level.WARN -> Color(0xFFFFAA00)
                        HubLogBuffer.Level.DEBUG -> Color(0xFF666666)
                        HubLogBuffer.Level.INFO -> Color(0xFFCCCCCC)
                    }
                    Text(
                        text = "${timeFmt.format(Date(entry.timestamp))} ${entry.level.label}/${entry.tag}: ${entry.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = levelColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun P1ConfigSection(credentialStore: SecureCredentialStore, onRestartService: () -> Unit) {
    val existingCredential = credentialStore.getCredential("p1_meter")
    var ipText by remember { mutableStateOf(existingCredential?.ipAddress ?: "") }
    var portText by remember { mutableStateOf(existingCredential?.port?.toString() ?: "8989") }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    Text("P1 Smart Meter", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
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
        savedMessage = "Mentve! Szolgáltatás újraindítása..."
        onRestartService()
    }) {
        Text("Mentés és restart")
    }
    savedMessage?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun CloudSyncConfigSection(hubConfigStore: HubConfigStore) {
    val hubConfig = hubConfigStore.getConfig()
    var relayUrl by remember { mutableStateOf(hubConfig?.relayUrl ?: "") }
    var homeId by remember { mutableStateOf(hubConfig?.homeId ?: "") }
    var kioskUrl by remember { mutableStateOf(hubConfig?.kioskUrl ?: "") }
    var syncToken by remember { mutableStateOf(hubConfig?.syncToken ?: "") }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    Text("Cloud Sync", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = relayUrl,
        onValueChange = { relayUrl = it },
        label = { Text("Relé URL") },
        placeholder = { Text("https://homeassisthub.onrender.com") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = homeId,
        onValueChange = { homeId = it },
        label = { Text("Home ID") },
        placeholder = { Text("home1") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = kioskUrl,
        onValueChange = { kioskUrl = it },
        label = { Text("Kiosk URL") },
        placeholder = { Text("https://uni002eu5.fusionsolar.huawei.com/...") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Button(onClick = {
        hubConfigStore.saveConfig(
            com.homeassisthub.hub.data.HubConfig(
                relayUrl = relayUrl.trim(),
                homeId = homeId.trim(),
                kioskUrl = kioskUrl.trim(),
                syncToken = syncToken
            )
        )
        savedMessage = "Mentve!"
    }) {
        Text("Mentés")
    }
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    if (syncToken.isNotBlank()) {
        Text("Sync Token:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        Text(syncToken, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
        Text("Másold be a Kliens Beállításokba.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
    Button(onClick = {
        syncToken = hubConfigStore.generateSyncToken()
        savedMessage = "Új sync token generálva!"
    }) {
        Text(if (syncToken.isBlank()) "Token generálása" else "Új token")
    }
    savedMessage?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun BaselineConfigSection(hubConfigStore: HubConfigStore) {
    val hubConfig = hubConfigStore.getConfig()
    var importKwh by remember { mutableStateOf(hubConfig?.baselineImportKwh?.toString() ?: "") }
    var exportKwh by remember { mutableStateOf(hubConfig?.baselineExportKwh?.toString() ?: "") }
    var date by remember { mutableStateOf(hubConfig?.baselineDate ?: "") }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    Text("Elszámolási nyitóértékek (MVM)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    Text(
        "A P1 mérőóra kumulált állásai az utolsó hivatalos leolvasás napján. Ezekből számolódik az éves vételezés/visszatáplálás.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedTextField(
        value = importKwh,
        onValueChange = { importKwh = it.filter { c -> c.isDigit() || c == '.' } },
        label = { Text("Nyitó vételezés (kWh)") },
        placeholder = { Text("8779.0") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = exportKwh,
        onValueChange = { exportKwh = it.filter { c -> c.isDigit() || c == '.' } },
        label = { Text("Nyitó visszatáplálás (kWh)") },
        placeholder = { Text("5000.0") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = date,
        onValueChange = { date = it },
        label = { Text("Leolvasás dátuma") },
        placeholder = { Text("2026-01-14") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Button(onClick = {
        val imp = importKwh.trim().toDoubleOrNull()
        val exp = exportKwh.trim().toDoubleOrNull()
        val dt = date.trim()
        if (imp == null || exp == null || dt.isBlank()) {
            savedMessage = "Minden mező kitöltése kötelező!"
            return@Button
        }
        hubConfigStore.saveBaseline(imp, exp, dt)
        savedMessage = "Baseline mentve!"
    }) {
        Text("Mentés")
    }
    savedMessage?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

private fun isServiceRunning(context: android.content.Context): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    @Suppress("DEPRECATION")
    val services = manager.getRunningServices(Int.MAX_VALUE)
    return services.any { it.service.className == "com.homeassisthub.hub.service.HubForegroundService" }
}
