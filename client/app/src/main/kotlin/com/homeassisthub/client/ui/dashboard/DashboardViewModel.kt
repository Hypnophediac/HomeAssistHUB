package com.homeassisthub.client.ui.dashboard

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homeassisthub.client.data.ClientConfig
import com.homeassisthub.client.data.ClientConfigStore
import com.homeassisthub.client.network.JsonParsing
import com.homeassisthub.client.network.SocketIoManager
import com.homeassisthub.client.network.model.BaselineData
import com.homeassisthub.client.network.model.DailySummaryDto
import com.homeassisthub.client.network.model.DeviceCredentialSummaryDto
import com.homeassisthub.client.network.model.LivePowerData
import com.homeassisthub.client.network.model.P1ReadingDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val configStore = ClientConfigStore(application)
    private var socketManager: SocketIoManager? = null

    private val _plugs = MutableStateFlow<List<DeviceCredentialSummaryDto>>(emptyList())
    val plugs: StateFlow<List<DeviceCredentialSummaryDto>> = _plugs.asStateFlow()

    private val _p1History = MutableStateFlow<List<P1ReadingDto>>(emptyList())
    val p1History: StateFlow<List<P1ReadingDto>> = _p1History.asStateFlow()

    private val _dailySummary = MutableStateFlow<DailySummaryDto?>(null)
    val dailySummary: StateFlow<DailySummaryDto?> = _dailySummary.asStateFlow()

    private val _plugStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val plugStates: StateFlow<Map<String, Boolean>> = _plugStates.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _livePower = MutableStateFlow<LivePowerData?>(null)
    val livePower: StateFlow<LivePowerData?> = _livePower.asStateFlow()

    private val _baseline = MutableStateFlow<BaselineData?>(null)
    val baseline: StateFlow<BaselineData?> = _baseline.asStateFlow()

    private var pollingJob: Job? = null

    fun refresh() {
        viewModelScope.launch {
            val config = configStore.getConfig()
            if (config == null) {
                _statusMessage.value = "Nincs beállítva a relé kapcsolat (lásd Beállítások)."
                return@launch
            }
            val manager = ensureSocketConnected(config)
            runCatching {
                val devicesResponse = retryCommand(manager, "hub", "list_devices")
                if (!devicesResponse.optBoolean("success")) error(devicesResponse.optString("error", "Unknown error"))
                val devicesJson = devicesResponse.optJSONObject("data")?.optJSONArray("devices")
                _plugs.value = JsonParsing.parseList(devicesJson, DeviceCredentialSummaryDto::class.java)
                    .filter { it.deviceType == "smart_plug" }

                val historyResponse = retryCommand(manager, "hub", "get_p1_history", mapOf("limit" to "100"))
                if (!historyResponse.optBoolean("success")) error(historyResponse.optString("error", "Unknown error"))
                val dataObj = historyResponse.optJSONObject("data")
                val readingsJson = dataObj?.optJSONArray("readings")
                _p1History.value = JsonParsing.parseList(readingsJson, P1ReadingDto::class.java)
                _p1History.value.lastOrNull()?.let { _livePower.value = LivePowerData.fromReading(it) }
                val summaryJson = dataObj?.optJSONObject("dailySummary")
                _dailySummary.value = summaryJson?.let {
                    DailySummaryDto(
                        inverterDailyKwh = it.optDouble("inverterDailyKwh", 0.0),
                        p1DailyImportKwh = it.optDouble("p1DailyImportKwh", 0.0),
                        p1DailyExportKwh = it.optDouble("p1DailyExportKwh", 0.0),
                        houseDailyKwh = it.optDouble("houseDailyKwh", 0.0)
                    )
                }
                val baselineObj = dataObj?.optJSONObject("baseline")
                if (baselineObj != null) {
                    _baseline.value = BaselineData(
                        baselineImportKwh = baselineObj.optDouble("baselineImportKwh", 0.0),
                        baselineExportKwh = baselineObj.optDouble("baselineExportKwh", 0.0),
                        baselineImportT1Kwh = baselineObj.optDouble("baselineImportT1Kwh", 0.0),
                        baselineImportT2Kwh = baselineObj.optDouble("baselineImportT2Kwh", 0.0),
                        baselineExportT1Kwh = baselineObj.optDouble("baselineExportT1Kwh", 0.0),
                        baselineExportT2Kwh = baselineObj.optDouble("baselineExportT2Kwh", 0.0),
                        baselineDate = baselineObj.optString("baselineDate", ""),
                        currentImportTotalKwh = baselineObj.optDouble("currentImportTotalKwh", 0.0),
                        currentExportTotalKwh = baselineObj.optDouble("currentExportTotalKwh", 0.0),
                        currentImportT1Kwh = baselineObj.optDouble("currentImportT1Kwh", 0.0),
                        currentImportT2Kwh = baselineObj.optDouble("currentImportT2Kwh", 0.0),
                        currentExportT1Kwh = baselineObj.optDouble("currentExportT1Kwh", 0.0),
                        currentExportT2Kwh = baselineObj.optDouble("currentExportT2Kwh", 0.0),
                        yearlyImportKwh = baselineObj.optDouble("yearlyImportKwh", 0.0),
                        yearlyExportKwh = baselineObj.optDouble("yearlyExportKwh", 0.0),
                        yearlyImportT1Kwh = baselineObj.optDouble("yearlyImportT1Kwh", 0.0),
                        yearlyImportT2Kwh = baselineObj.optDouble("yearlyImportT2Kwh", 0.0),
                        yearlyExportT1Kwh = baselineObj.optDouble("yearlyExportT1Kwh", 0.0),
                        yearlyExportT2Kwh = baselineObj.optDouble("yearlyExportT2Kwh", 0.0),
                        yearlyBalanceKwh = baselineObj.optDouble("yearlyBalanceKwh", 0.0)
                    )
                }
            }.onFailure {
                _statusMessage.value = "Hiba a Hub elérésekor: ${it.message}"
            }
        }

        startLivePolling()
    }

    /** 2-second polling loop for near-real-time P1 updates.
     *  Every 30s also fetches the full 24h history (1440 readings) for the chart. */
    fun startLivePolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            var tick = 0
            while (isActive) {
                val config = configStore.getConfig()
                if (config == null) { delay(2_000L); continue }
                val manager = ensureSocketConnected(config)
                val limit = if (tick % 15 == 0) "1440" else "1"
                runCatching {
                    val resp = manager.sendCommand("hub", "get_p1_history", mapOf("limit" to limit))
                    if (resp.optBoolean("success")) {
                        val readingsJson = resp.optJSONObject("data")?.optJSONArray("readings")
                        val readings = JsonParsing.parseList(readingsJson, P1ReadingDto::class.java)
                        if (readings.isNotEmpty()) {
                            _livePower.value = LivePowerData.fromReading(readings.last())
                            if (limit == "1440") {
                                _p1History.value = readings
                            }
                        }
                    }
                }.onFailure { Log.w("DashboardVM", "Live poll failed: ${it.message}") }
                tick++
                delay(2_000L)
            }
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
            it.setOnPeerJoined { role ->
                if (role == "hub") {
                    Log.i("DashboardVM", "Hub joined relay, auto-refreshing")
                    refresh()
                }
            }
            it.connect()
            socketManager = it
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    override fun onCleared() {
        pollingJob?.cancel()
        socketManager?.disconnect()
        super.onCleared()
    }
}
