package com.homeassisthub.client.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homeassisthub.client.data.ClientConfig
import com.homeassisthub.client.data.ClientConfigStore
import com.homeassisthub.client.network.JsonParsing
import com.homeassisthub.client.network.SocketIoManager
import com.homeassisthub.client.network.model.DeviceCredentialSummaryDto
import com.homeassisthub.client.network.model.P1ReadingDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val configStore = ClientConfigStore(application)
    private var socketManager: SocketIoManager? = null

    private val _plugs = MutableStateFlow<List<DeviceCredentialSummaryDto>>(emptyList())
    val plugs: StateFlow<List<DeviceCredentialSummaryDto>> = _plugs.asStateFlow()

    private val _p1History = MutableStateFlow<List<P1ReadingDto>>(emptyList())
    val p1History: StateFlow<List<P1ReadingDto>> = _p1History.asStateFlow()

    private val _plugStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val plugStates: StateFlow<Map<String, Boolean>> = _plugStates.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    /**
     * Refreshes plug list + P1 history through the cloud relay
     * (`command_request`/`command_response`), so this works from anywhere
     * (mobile data), not just when the Client is on the Hub's local LAN.
     */
    fun refresh() {
        viewModelScope.launch {
            val config = configStore.getConfig()
            if (config == null) {
                _statusMessage.value = "Nincs beállítva a relé kapcsolat (lásd Beállítások)."
                return@launch
            }
            val manager = ensureSocketConnected(config)
            runCatching {
                val devicesResponse = manager.sendCommand("hub", "list_devices")
                if (!devicesResponse.optBoolean("success")) error(devicesResponse.optString("error", "Unknown error"))
                val devicesJson = devicesResponse.optJSONObject("data")?.optJSONArray("devices")
                _plugs.value = JsonParsing.parseList(devicesJson, DeviceCredentialSummaryDto::class.java)
                    .filter { it.deviceType == "smart_plug" }

                val historyResponse = manager.sendCommand("hub", "get_p1_history", mapOf("limit" to "100"))
                if (!historyResponse.optBoolean("success")) error(historyResponse.optString("error", "Unknown error"))
                val readingsJson = historyResponse.optJSONObject("data")?.optJSONArray("readings")
                _p1History.value = JsonParsing.parseList(readingsJson, P1ReadingDto::class.java)
            }.onFailure {
                _statusMessage.value = "Hiba a Hub elérésekor: ${it.message}"
            }
        }
    }

    fun togglePlug(deviceId: String, turnOn: Boolean) {
        viewModelScope.launch {
            val config = configStore.getConfig() ?: return@launch
            val manager = ensureSocketConnected(config)
            val response = manager.sendCommand(deviceId, if (turnOn) "turn_on" else "turn_off")
            if (response.optBoolean("success")) {
                _plugStates.value = _plugStates.value + (deviceId to turnOn)
            } else {
                _statusMessage.value = "Hiba: ${response.optString("error")}"
            }
        }
    }

    private fun ensureSocketConnected(config: ClientConfig): SocketIoManager {
        return socketManager ?: SocketIoManager(config.relayUrl, config.homeId).also {
            it.connect()
            socketManager = it
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    override fun onCleared() {
        socketManager?.disconnect()
        super.onCleared()
    }
}
