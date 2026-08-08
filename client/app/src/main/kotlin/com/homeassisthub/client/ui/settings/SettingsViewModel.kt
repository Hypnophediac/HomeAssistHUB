package com.homeassisthub.client.ui.settings

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homeassisthub.client.data.ClientConfig
import com.homeassisthub.client.data.ClientConfigStore
import com.homeassisthub.client.data.PvForecastConfig
import com.homeassisthub.client.network.GeocodingService
import com.homeassisthub.client.network.JsonParsing
import com.homeassisthub.client.network.SocketIoManager
import com.homeassisthub.client.network.model.DeviceCredentialSummaryDto
import com.homeassisthub.client.network.model.DiscoveredDeviceDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val configStore = ClientConfigStore(application)

    val config = mutableStateOf(
        configStore.getConfig() ?: ClientConfig(relayUrl = "", homeId = "", hubLocalBaseUrl = "")
    )

    val pvForecastConfig = mutableStateOf(configStore.getPvForecastConfig())

    private val _geocodingResults = MutableStateFlow<List<GeocodingService.GeocodingResult>>(emptyList())
    val geocodingResults: StateFlow<List<GeocodingService.GeocodingResult>> = _geocodingResults.asStateFlow()

    private val _isGeocoding = MutableStateFlow(false)
    val isGeocoding: StateFlow<Boolean> = _isGeocoding.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredDeviceDto>>(emptyList())
    val discovered: StateFlow<List<DiscoveredDeviceDto>> = _discovered.asStateFlow()

    private val _savedDevices = MutableStateFlow<List<DeviceCredentialSummaryDto>>(emptyList())
    val savedDevices: StateFlow<List<DeviceCredentialSummaryDto>> = _savedDevices.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private var socketManager: SocketIoManager? = null

    init {
        if (config.value.relayUrl.isNotBlank() && config.value.homeId.isNotBlank()) loadSavedDevices()
    }

    fun saveConfig(relayUrl: String, homeId: String, hubLocalBaseUrl: String, syncToken: String = "") {
        val currentSyncToken = if (syncToken.isNotBlank()) syncToken else config.value.syncToken
        val newConfig = ClientConfig(relayUrl, homeId, hubLocalBaseUrl, currentSyncToken)
        configStore.saveConfig(newConfig)
        config.value = newConfig
        socketManager?.disconnect()
        socketManager = null
        _statusMessage.value = "Beállítások elmentve."
        loadSavedDevices()
    }

    fun searchLocation(query: String) {
        viewModelScope.launch {
            _isGeocoding.value = true
            val results = GeocodingService.search(query)
            _geocodingResults.value = results
            _isGeocoding.value = false
            if (results.isEmpty()) {
                _statusMessage.value = "Nem található helyszín: '$query'"
            }
        }
    }

    fun clearGeocodingResults() {
        _geocodingResults.value = emptyList()
    }

    fun savePvForecastConfig(locationName: String, latitude: Double?, longitude: Double?, pvCapacityKwp: String, performanceRatioPercent: String) {
        val newConfig = PvForecastConfig(
            latitude = latitude,
            longitude = longitude,
            pvCapacityKwp = pvCapacityKwp.replace(",", ".").toDoubleOrNull(),
            performanceRatio = (performanceRatioPercent.replace(",", ".").toDoubleOrNull() ?: 80.0) / 100.0,
            locationName = locationName
        )
        configStore.savePvForecastConfig(newConfig)
        pvForecastConfig.value = newConfig
        _geocodingResults.value = emptyList()
        _statusMessage.value = "Napelem beállítások elmentve."
    }

    /** All Hub interactions go through the relay ("hub" pseudo-device commands), so this works over mobile data too. */
    private fun ensureSocketConnected(cfg: ClientConfig): SocketIoManager {
        return socketManager ?: SocketIoManager(cfg.relayUrl, cfg.homeId).also {
            it.connect()
            socketManager = it
        }
    }

    /** Retries a command up to 3 times with 3s delay, in case the Hub isn't connected to the relay yet. */
    private suspend fun retryCommand(
        manager: SocketIoManager,
        deviceId: String,
        action: String,
        params: Map<String, String> = emptyMap()
    ): JSONObject {
        var lastResponse: JSONObject = JSONObject().put("success", false).put("error", "No attempts made")
        for (attempt in 1..3) {
            lastResponse = manager.sendCommand(deviceId, action, params)
            if (lastResponse.optBoolean("success")) return lastResponse
            val errorMsg = lastResponse.optString("error", "")
            if (errorMsg.contains("Timeout") && attempt < 3) {
                delay(3_000L)
            } else {
                return lastResponse
            }
        }
        return lastResponse
    }

    fun discoverDevices() {
        viewModelScope.launch {
            val cfg = config.value
            if (cfg.relayUrl.isBlank() || cfg.homeId.isBlank()) {
                _statusMessage.value = "Előbb add meg a relé URL-t és a homeId-t."
                return@launch
            }
            val manager = ensureSocketConnected(cfg)
            runCatching {
                val response = retryCommand(manager, "hub", "discover_devices")
                if (!response.optBoolean("success")) error(response.optString("error", "Unknown error"))
                val devicesJson = response.optJSONObject("data")?.optJSONArray("devices")
                _discovered.value = JsonParsing.parseList(devicesJson, DiscoveredDeviceDto::class.java)
            }.onFailure { _statusMessage.value = "Discovery hiba: ${it.message}" }
        }
    }

    fun loadSavedDevices() {
        viewModelScope.launch {
            val cfg = config.value
            if (cfg.relayUrl.isBlank() || cfg.homeId.isBlank()) return@launch
            val manager = ensureSocketConnected(cfg)
            runCatching {
                val response = retryCommand(manager, "hub", "list_devices")
                if (!response.optBoolean("success")) error(response.optString("error", "Unknown error"))
                val devicesJson = response.optJSONObject("data")?.optJSONArray("devices")
                _savedDevices.value = JsonParsing.parseList(devicesJson, DeviceCredentialSummaryDto::class.java)
            }.onFailure { _statusMessage.value = "Hiba a mentett eszközök lekérésekor: ${it.message}" }
        }
    }

    fun saveCredential(deviceId: String, deviceType: String, ipAddress: String, port: Int, username: String, password: String) {
        viewModelScope.launch {
            val cfg = config.value
            if (cfg.relayUrl.isBlank() || cfg.homeId.isBlank()) {
                _statusMessage.value = "Előbb add meg a relé URL-t és a homeId-t."
                return@launch
            }
            val manager = ensureSocketConnected(cfg)
            runCatching {
                val params = mapOf(
                    "deviceId" to deviceId,
                    "deviceType" to deviceType,
                    "ipAddress" to ipAddress,
                    "port" to port.toString(),
                    "username" to username,
                    "password" to password
                )
                val response = manager.sendCommand("hub", "save_credential", params)
                if (!response.optBoolean("success")) error(response.optString("error", "Unknown error"))
                _statusMessage.value = "Eszköz elmentve: $deviceId"
                loadSavedDevices()
            }.onFailure { _statusMessage.value = "Mentési hiba: ${it.message}" }
        }
    }

    fun deleteCredential(deviceId: String) {
        viewModelScope.launch {
            val cfg = config.value
            val manager = ensureSocketConnected(cfg)
            runCatching {
                val response = manager.sendCommand("hub", "delete_credential", mapOf("deviceId" to deviceId))
                if (!response.optBoolean("success")) error(response.optString("error", "Unknown error"))
                loadSavedDevices()
            }.onFailure { _statusMessage.value = "Törlési hiba: ${it.message}" }
        }
    }

    fun saveKioskUrl(kioskUrl: String) {
        viewModelScope.launch {
            val cfg = config.value
            if (cfg.relayUrl.isBlank() || cfg.homeId.isBlank()) {
                _statusMessage.value = "Előbb add meg a relé URL-t és a homeId-t."
                return@launch
            }
            val manager = ensureSocketConnected(cfg)
            runCatching {
                val response = retryCommand(manager, "hub", "save_kiosk_url", mapOf("kioskUrl" to kioskUrl))
                if (!response.optBoolean("success")) error(response.optString("error", "Unknown error"))
                _statusMessage.value = "Kiosk URL elmentve. A scraper 5 percenként frissíti az adatokat."
            }.onFailure { _statusMessage.value = "Kiosk mentési hiba: ${it.message}" }
        }
    }

    fun loadKioskUrl() {
        viewModelScope.launch {
            val cfg = config.value
            if (cfg.relayUrl.isBlank() || cfg.homeId.isBlank()) {
                _statusMessage.value = "Előbb add meg a relé URL-t és a homeId-t."
                return@launch
            }
            val manager = ensureSocketConnected(cfg)
            runCatching {
                val response = retryCommand(manager, "hub", "get_kiosk_url")
                if (!response.optBoolean("success")) error(response.optString("error", "Unknown error"))
                val url = response.optJSONObject("data")?.optString("kioskUrl") ?: ""
                _statusMessage.value = if (url.isNotBlank()) "Kiosk URL: $url" else "Nincs Kiosk URL beállítva."
            }.onFailure { _statusMessage.value = "Kiosk lekérdezési hiba: ${it.message}" }
        }
    }

    data class BaselineState(
        val importKwh: String = "",
        val exportKwh: String = "",
        val date: String = "",
        val importT1: String = "",
        val importT2: String = "",
        val exportT1: String = "",
        val exportT2: String = ""
    )

    private val _baseline = MutableStateFlow(BaselineState())
    val baseline: StateFlow<BaselineState> = _baseline.asStateFlow()

    fun loadBaseline() {
        viewModelScope.launch {
            val cfg = config.value
            if (cfg.relayUrl.isBlank() || cfg.homeId.isBlank()) return@launch
            val manager = ensureSocketConnected(cfg)
            runCatching {
                val response = retryCommand(manager, "hub", "get_baseline")
                if (!response.optBoolean("success")) error(response.optString("error", "Unknown error"))
                val data = response.optJSONObject("data")
                _baseline.value = BaselineState(
                    importKwh = data?.optDouble("baselineImportKwh", 0.0)?.toString() ?: "",
                    exportKwh = data?.optDouble("baselineExportKwh", 0.0)?.toString() ?: "",
                    date = data?.optString("baselineDate", "") ?: "",
                    importT1 = data?.optDouble("baselineImportT1Kwh", 0.0)?.let { if (it == 0.0) "" else it.toString() } ?: "",
                    importT2 = data?.optDouble("baselineImportT2Kwh", 0.0)?.let { if (it == 0.0) "" else it.toString() } ?: "",
                    exportT1 = data?.optDouble("baselineExportT1Kwh", 0.0)?.let { if (it == 0.0) "" else it.toString() } ?: "",
                    exportT2 = data?.optDouble("baselineExportT2Kwh", 0.0)?.let { if (it == 0.0) "" else it.toString() } ?: ""
                )
            }.onFailure { _statusMessage.value = "Baseline lekérdezési hiba: ${it.message}" }
        }
    }

    fun saveBaseline(
        importKwh: String, exportKwh: String, date: String,
        importT1: String = "", importT2: String = "",
        exportT1: String = "", exportT2: String = ""
    ) {
        viewModelScope.launch {
            val cfg = config.value
            if (cfg.relayUrl.isBlank() || cfg.homeId.isBlank()) {
                _statusMessage.value = "Előbb add meg a relé URL-t és a homeId-t."
                return@launch
            }
            val manager = ensureSocketConnected(cfg)
            val params = mutableMapOf(
                "importKwh" to importKwh.replace(",", "."),
                "exportKwh" to exportKwh.replace(",", "."),
                "date" to date
            )
            if (importT1.isNotBlank()) params["importT1Kwh"] = importT1.replace(",", ".")
            if (importT2.isNotBlank()) params["importT2Kwh"] = importT2.replace(",", ".")
            if (exportT1.isNotBlank()) params["exportT1Kwh"] = exportT1.replace(",", ".")
            if (exportT2.isNotBlank()) params["exportT2Kwh"] = exportT2.replace(",", ".")
            runCatching {
                val response = retryCommand(manager, "hub", "save_baseline", params)
                if (!response.optBoolean("success")) error(response.optString("error", "Unknown error"))
                _statusMessage.value = "Elszámolási nyitóértékek elmentve!"
                _baseline.value = BaselineState(importKwh, exportKwh, date, importT1, importT2, exportT1, exportT2)
            }.onFailure { _statusMessage.value = "Baseline mentési hiba: ${it.message}" }
        }
    }

    override fun onCleared() {
        socketManager?.disconnect()
        super.onCleared()
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}
