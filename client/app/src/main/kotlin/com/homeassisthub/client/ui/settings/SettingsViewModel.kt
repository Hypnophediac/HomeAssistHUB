package com.homeassisthub.client.ui.settings

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homeassisthub.client.data.ClientConfig
import com.homeassisthub.client.data.ClientConfigStore
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

    fun saveConfig(relayUrl: String, homeId: String, hubLocalBaseUrl: String) {
        val newConfig = ClientConfig(relayUrl, homeId, hubLocalBaseUrl)
        configStore.saveConfig(newConfig)
        config.value = newConfig
        socketManager?.disconnect()
        socketManager = null
        _statusMessage.value = "Beállítások elmentve."
        loadSavedDevices()
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

    override fun onCleared() {
        socketManager?.disconnect()
        super.onCleared()
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}
