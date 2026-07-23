package com.homeassisthub.client.ui.camera

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homeassisthub.client.data.ClientConfig
import com.homeassisthub.client.data.ClientConfigStore
import com.homeassisthub.client.network.JsonParsing
import com.homeassisthub.client.network.SocketIoManager
import com.homeassisthub.client.network.model.DeviceCredentialSummaryDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val configStore = ClientConfigStore(application)
    private var socketManager: SocketIoManager? = null

    private val _cameras = MutableStateFlow<List<DeviceCredentialSummaryDto>>(emptyList())
    val cameras: StateFlow<List<DeviceCredentialSummaryDto>> = _cameras.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _ptzStatus = MutableStateFlow<String?>(null)
    val ptzStatus: StateFlow<String?> = _ptzStatus.asStateFlow()

    private val _snapshots = MutableStateFlow<Map<String, String>>(emptyMap())
    val snapshots: StateFlow<Map<String, String>> = _snapshots.asStateFlow()

    private val _snapshotLoading = MutableStateFlow<Set<String>>(emptySet())
    val snapshotLoading: StateFlow<Set<String>> = _snapshotLoading.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val config = configStore.getConfig()
            if (config == null) {
                _statusMessage.value = "Nincs beállítva a relé kapcsolat (lásd Beállítások)."
                return@launch
            }
            val manager = ensureSocketConnected(config)
            runCatching {
                val response = retryCommand(manager, "hub", "list_devices")
                if (!response.optBoolean("success")) error(response.optString("error", "Unknown error"))
                val devicesJson = response.optJSONObject("data")?.optJSONArray("devices")
                _cameras.value = JsonParsing.parseList(devicesJson, DeviceCredentialSummaryDto::class.java)
                    .filter { it.deviceType == "v380_ptz" || it.deviceType == "rtsp_camera" }
            }.onFailure {
                _statusMessage.value = "Hiba a kamerák lekérésekor: ${it.message}"
            }
        }
    }

    fun sendPtzCommand(deviceId: String, action: String) {
        viewModelScope.launch {
            val config = configStore.getConfig() ?: return@launch
            val manager = ensureSocketConnected(config)
            _ptzStatus.value = "Parancs küldése: $action..."
            val response = manager.sendCommand(deviceId, action)
            if (response.optBoolean("success")) {
                _ptzStatus.value = "$action sikeres"
            } else {
                _ptzStatus.value = "Hiba: ${response.optString("error")}"
            }
        }
    }

    fun fetchSnapshot(deviceId: String) {
        viewModelScope.launch {
            val config = configStore.getConfig() ?: return@launch
            val manager = ensureSocketConnected(config)
            _snapshotLoading.value = _snapshotLoading.value + deviceId
            runCatching {
                val response = retryCommand(manager, deviceId, "get_snapshot")
                if (response.optBoolean("success")) {
                    val snapshot = response.optJSONObject("data")?.optString("snapshot")
                    if (snapshot != null) {
                        _snapshots.value = _snapshots.value + (deviceId to snapshot)
                    }
                }
            }.onFailure {
                Log.e("CameraVM", "Snapshot error for $deviceId: ${it.message}")
            }
            _snapshotLoading.value = _snapshotLoading.value - deviceId
        }
    }

    fun refreshAllSnapshots() {
        val cams = _cameras.value.filter { it.deviceType == "rtsp_camera" }
        cams.forEach { fetchSnapshot(it.deviceId) }
    }

    fun clearStatus() {
        _statusMessage.value = null
        _ptzStatus.value = null
    }

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

    private fun ensureSocketConnected(config: ClientConfig): SocketIoManager {
        return socketManager ?: SocketIoManager(config.relayUrl, config.homeId).also {
            it.setOnPeerJoined { role ->
                if (role == "hub") {
                    Log.i("CameraVM", "Hub joined relay, auto-refreshing")
                    refresh()
                }
            }
            it.connect()
            socketManager = it
        }
    }

    override fun onCleared() {
        socketManager?.disconnect()
        super.onCleared()
    }
}
