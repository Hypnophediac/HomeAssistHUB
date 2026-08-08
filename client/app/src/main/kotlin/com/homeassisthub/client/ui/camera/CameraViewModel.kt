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
import kotlinx.coroutines.Dispatchers
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

    private val _liveFrames = MutableStateFlow<Map<String, String>>(emptyMap())
    val liveFrames: StateFlow<Map<String, String>> = _liveFrames.asStateFlow()

    private val _streamingDevices = MutableStateFlow<Set<String>>(emptySet())
    val streamingDevices: StateFlow<Set<String>> = _streamingDevices.asStateFlow()

    fun refresh() {
        Log.i(TAG, "refresh() called")
        viewModelScope.launch {
            val config = configStore.getConfig()
            if (config == null) {
                Log.w(TAG, "No config found, cannot refresh cameras")
                _statusMessage.value = "Nincs beállítva a relé kapcsolat (lásd Beállítások)."
                return@launch
            }
            Log.i(TAG, "Config OK, relayUrl=${config.relayUrl}, homeId=${config.homeId}")
            val manager = ensureSocketConnected(config)
            Log.i(TAG, "Socket manager ready, sending list_devices command")
            runCatching {
                val response = retryCommand(manager, "hub", "list_devices")
                Log.i(TAG, "list_devices response: success=${response.optBoolean("success")}")
                if (!response.optBoolean("success")) error(response.optString("error", "Unknown error"))
                val devicesJson = response.optJSONObject("data")?.optJSONArray("devices")
                val allDevices = JsonParsing.parseList(devicesJson, DeviceCredentialSummaryDto::class.java)
                Log.i(TAG, "Got ${allDevices.size} devices, filtering for cameras")
                val cameras = allDevices.filter { it.deviceType == "v380_ptz" || it.deviceType == "rtsp_camera" }
                Log.i(TAG, "Found ${cameras.size} cameras: ${cameras.map { "${it.deviceId}(${it.deviceType})" }}")
                _cameras.value = cameras
            }.onFailure {
                Log.e(TAG, "refresh failed: ${it.message}", it)
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

    fun startStream(deviceId: String) {
        viewModelScope.launch {
            val config = configStore.getConfig() ?: return@launch
            val manager = ensureSocketConnected(config)
            Log.i(TAG, "Starting stream for $deviceId")
            val response = manager.sendCommand(deviceId, "start_stream", timeoutMs = 10_000L)
            if (response.optBoolean("success")) {
                _streamingDevices.value = _streamingDevices.value + deviceId
                Log.i(TAG, "Stream started for $deviceId")
            } else {
                Log.e(TAG, "Failed to start stream for $deviceId: ${response.optString("error")}")
                _statusMessage.value = "Stream ind\u00edt\u00e1sa sikertelen: ${response.optString("error")}"
            }
        }
    }

    fun stopStream(deviceId: String) {
        viewModelScope.launch {
            val config = configStore.getConfig() ?: return@launch
            val manager = ensureSocketConnected(config)
            Log.i(TAG, "Stopping stream for $deviceId")
            manager.sendCommand(deviceId, "stop_stream", timeoutMs = 5_000L)
            _streamingDevices.value = _streamingDevices.value - deviceId
            _liveFrames.value = _liveFrames.value - deviceId
        }
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
            Log.i(TAG, "Creating new SocketIoManager, relayUrl=${config.relayUrl}")
            it.setOnPeerJoined { role ->
                Log.i(TAG, "Peer joined: $role")
                if (role == "hub") {
                    Log.i(TAG, "Hub joined relay, auto-refreshing")
                    refresh()
                }
            }
            it.setOnCameraFrame { deviceId, base64 ->
                _liveFrames.value = _liveFrames.value + (deviceId to base64)
            }
            it.connect()
            Log.i(TAG, "SocketIoManager.connect() called")
            socketManager = it
        }
    }

    override fun onCleared() {
        val mgr = socketManager
        val devices = _streamingDevices.value.toList()
        if (mgr != null && devices.isNotEmpty()) {
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                for (deviceId in devices) {
                    mgr.sendCommand(deviceId, "stop_stream")
                }
                mgr.disconnect()
            }
        } else {
            socketManager?.disconnect()
        }
        super.onCleared()
    }

    companion object {
        private const val TAG = "CameraVM"
    }
}
