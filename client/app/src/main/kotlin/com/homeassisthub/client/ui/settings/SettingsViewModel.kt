package com.homeassisthub.client.ui.settings

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homeassisthub.client.data.ClientConfig
import com.homeassisthub.client.data.ClientConfigStore
import com.homeassisthub.client.network.RetrofitFactory
import com.homeassisthub.client.network.model.DeviceCredentialRequestDto
import com.homeassisthub.client.network.model.DeviceCredentialSummaryDto
import com.homeassisthub.client.network.model.DiscoveredDeviceDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    init {
        if (config.value.hubLocalBaseUrl.isNotBlank()) loadSavedDevices()
    }

    fun saveConfig(relayUrl: String, homeId: String, hubLocalBaseUrl: String) {
        val newConfig = ClientConfig(relayUrl, homeId, hubLocalBaseUrl)
        configStore.saveConfig(newConfig)
        config.value = newConfig
        _statusMessage.value = "Beállítások elmentve."
        loadSavedDevices()
    }

    fun discoverDevices() {
        viewModelScope.launch {
            val cfg = config.value
            if (cfg.hubLocalBaseUrl.isBlank()) {
                _statusMessage.value = "Előbb add meg a Hub helyi API URL-jét."
                return@launch
            }
            runCatching {
                val api = RetrofitFactory.create(cfg.hubLocalBaseUrl)
                _discovered.value = api.discoverDevices()
            }.onFailure { _statusMessage.value = "Discovery hiba: ${it.message}" }
        }
    }

    fun loadSavedDevices() {
        viewModelScope.launch {
            val cfg = config.value
            if (cfg.hubLocalBaseUrl.isBlank()) return@launch
            runCatching {
                val api = RetrofitFactory.create(cfg.hubLocalBaseUrl)
                _savedDevices.value = api.getDevices()
            }.onFailure { _statusMessage.value = "Hiba a mentett eszközök lekérésekor: ${it.message}" }
        }
    }

    fun saveCredential(deviceId: String, deviceType: String, ipAddress: String, port: Int, username: String, password: String) {
        viewModelScope.launch {
            val cfg = config.value
            if (cfg.hubLocalBaseUrl.isBlank()) {
                _statusMessage.value = "Előbb add meg a Hub helyi API URL-jét."
                return@launch
            }
            runCatching {
                val api = RetrofitFactory.create(cfg.hubLocalBaseUrl)
                api.saveDevice(DeviceCredentialRequestDto(deviceId, deviceType, ipAddress, port, username, password))
                _statusMessage.value = "Eszköz elmentve: $deviceId"
                loadSavedDevices()
            }.onFailure { _statusMessage.value = "Mentési hiba: ${it.message}" }
        }
    }

    fun deleteCredential(deviceId: String) {
        viewModelScope.launch {
            val cfg = config.value
            runCatching {
                val api = RetrofitFactory.create(cfg.hubLocalBaseUrl)
                api.deleteDevice(deviceId)
                loadSavedDevices()
            }.onFailure { _statusMessage.value = "Törlési hiba: ${it.message}" }
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}
