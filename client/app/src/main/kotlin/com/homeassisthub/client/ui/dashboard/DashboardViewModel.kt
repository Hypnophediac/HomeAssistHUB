package com.homeassisthub.client.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homeassisthub.client.data.ClientConfigStore
import com.homeassisthub.client.network.RetrofitFactory
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

    fun refresh() {
        viewModelScope.launch {
            val config = configStore.getConfig()
            if (config == null) {
                _statusMessage.value = "Nincs beállítva a Hub kapcsolat (lásd Beállítások)."
                return@launch
            }
            runCatching {
                val api = RetrofitFactory.create(config.hubLocalBaseUrl)
                _plugs.value = api.getDevices().filter { it.deviceType == "smart_plug" }
                _p1History.value = api.getP1History(100)
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

    private fun ensureSocketConnected(config: com.homeassisthub.client.data.ClientConfig): SocketIoManager {
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
