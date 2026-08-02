package com.homeassisthub.client.ui.energy

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homeassisthub.client.data.ClientConfigStore
import com.homeassisthub.client.data.PvForecastCalculator
import com.homeassisthub.client.data.PvForecastResult
import com.homeassisthub.client.network.JsonParsing
import com.homeassisthub.client.network.RenderApiService
import com.homeassisthub.client.network.RetrofitFactory
import com.homeassisthub.client.network.SocketIoManager
import com.homeassisthub.client.network.WeatherForecastService
import com.homeassisthub.client.network.model.DailySummaryDto
import com.homeassisthub.client.network.model.EnergyDailyResponseDto
import com.homeassisthub.client.network.model.EnergyPeriodResponseDto
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

class EnergyViewModel(application: Application) : AndroidViewModel(application) {

    private val configStore = ClientConfigStore(application)
    private var socketManager: SocketIoManager? = null
    private var renderApi: RenderApiService? = null

    private val _dailyData = MutableStateFlow<EnergyDailyResponseDto?>(null)
    val dailyData: StateFlow<EnergyDailyResponseDto?> = _dailyData.asStateFlow()

    private val _weeklyData = MutableStateFlow<EnergyPeriodResponseDto?>(null)
    val weeklyData: StateFlow<EnergyPeriodResponseDto?> = _weeklyData.asStateFlow()

    private val _monthlyData = MutableStateFlow<EnergyPeriodResponseDto?>(null)
    val monthlyData: StateFlow<EnergyPeriodResponseDto?> = _monthlyData.asStateFlow()

    private val _yearlyData = MutableStateFlow<EnergyPeriodResponseDto?>(null)
    val yearlyData: StateFlow<EnergyPeriodResponseDto?> = _yearlyData.asStateFlow()

    private val _rangeData = MutableStateFlow<EnergyPeriodResponseDto?>(null)
    val rangeData: StateFlow<EnergyPeriodResponseDto?> = _rangeData.asStateFlow()

    private val _liveReadings = MutableStateFlow<List<P1ReadingDto>>(emptyList())
    val liveReadings: StateFlow<List<P1ReadingDto>> = _liveReadings.asStateFlow()

    private val _liveDailySummary = MutableStateFlow<DailySummaryDto?>(null)
    val liveDailySummary: StateFlow<DailySummaryDto?> = _liveDailySummary.asStateFlow()

    private val _pvForecast = MutableStateFlow<PvForecastResult?>(null)
    val pvForecast: StateFlow<PvForecastResult?> = _pvForecast.asStateFlow()

    private val _cloudSyncLastTime = MutableStateFlow<Long>(0L)
    val cloudSyncLastTime: StateFlow<Long> = _cloudSyncLastTime.asStateFlow()

    private val _livePower = MutableStateFlow<LivePowerData?>(null)
    val livePower: StateFlow<LivePowerData?> = _livePower.asStateFlow()

    private var pollingJob: Job? = null

    private val weatherService by lazy { WeatherForecastService.create() }

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val config = configStore.getConfig() ?: run {
                _statusMessage.value = "Nincs beállítva a relé kapcsolat (lásd Beállítások)."
                return@launch
            }
            if (config.syncToken.isBlank()) {
                _statusMessage.value = "Nincs beállítva a sync token (lásd Beállítások)."
                return@launch
            }
            _isLoading.value = true
            val authHeader = "Bearer ${config.syncToken}"
            val api = renderApi ?: RetrofitFactory.createRender(config.relayUrl).also { renderApi = it }

            // Historical energy data from Render/MongoDB
            var anySuccess = false
            runCatching {
                _dailyData.value = api.getEnergyDaily(config.homeId, authHeader)
                anySuccess = true
            }.onFailure { Log.e("EnergyVM", "Render daily fetch failed", it) }

            runCatching {
                _weeklyData.value = api.getEnergyWeekly(config.homeId, authHeader)
                anySuccess = true
            }.onFailure { Log.e("EnergyVM", "Render weekly fetch failed", it) }

            runCatching {
                _monthlyData.value = api.getEnergyMonthly(config.homeId, authHeader)
                anySuccess = true
            }.onFailure { Log.e("EnergyVM", "Render monthly fetch failed", it) }

            runCatching {
                _yearlyData.value = api.getEnergyYearly(config.homeId, authHeader)
                anySuccess = true
            }.onFailure { Log.e("EnergyVM", "Render yearly fetch failed", it) }

            // Live readings stay on Socket.IO (real-time, direct to Hub)
            val manager = ensureSocketConnected(config)
            runCatching {
                val liveResp = retryCommand(manager, "get_p1_history", mapOf("limit" to "5"))
                if (liveResp.optBoolean("success")) {
                    val liveDataObj = liveResp.optJSONObject("data")
                    val readingsJson = liveDataObj?.optJSONArray("readings")
                    _liveReadings.value = JsonParsing.parseList(readingsJson, P1ReadingDto::class.java)
                    _liveReadings.value.lastOrNull()?.let { _livePower.value = LivePowerData.fromReading(it) }
                    val summaryJson = liveDataObj?.optJSONObject("dailySummary")
                    _liveDailySummary.value = summaryJson?.let {
                        DailySummaryDto(
                            inverterDailyKwh = it.optDouble("inverterDailyKwh", 0.0),
                            p1DailyImportKwh = it.optDouble("p1DailyImportKwh", 0.0),
                            p1DailyExportKwh = it.optDouble("p1DailyExportKwh", 0.0),
                            houseDailyKwh = it.optDouble("houseDailyKwh", 0.0)
                        )
                    }
                    anySuccess = true
                    val cloudSyncObj = liveDataObj?.optJSONObject("cloudSync")
                    if (cloudSyncObj != null) {
                        _cloudSyncLastTime.value = cloudSyncObj.optLong("lastSyncTime", 0L)
                    }
                }
            }.onFailure {
                Log.e("EnergyVM", "Socket.IO live readings failed", it)
            }

            if (!anySuccess) {
                _statusMessage.value = "Nem érhető el a Render API vagy a Hub."
            }

            _isLoading.value = false
        }

        startLivePolling()
        fetchPvForecast()
    }

    /** Starts a 2-second polling loop that fetches the latest P1 reading
     *  via Socket.IO, so the Élő Adatok card updates in near-real-time.
     *  Every 30s also fetches the full 5-point history for the Energy screen. */
    fun startLivePolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            var tick = 0
            while (isActive) {
                val config = configStore.getConfig()
                if (config == null || config.syncToken.isBlank()) {
                    delay(2_000L)
                    continue
                }
                val manager = ensureSocketConnected(config)
                val limit = if (tick % 15 == 0) "5" else "1"
                runCatching {
                    val resp = manager.sendCommand("hub", "get_p1_history", mapOf("limit" to limit))
                    if (resp.optBoolean("success")) {
                        val readingsJson = resp.optJSONObject("data")?.optJSONArray("readings")
                        val readings = JsonParsing.parseList(readingsJson, P1ReadingDto::class.java)
                        if (readings.isNotEmpty()) {
                            _livePower.value = LivePowerData.fromReading(readings.last())
                            if (limit == "5") {
                                _liveReadings.value = readings
                            }
                        }
                    }
                }.onFailure {
                    Log.w("EnergyVM", "Live poll failed: ${it.message}")
                }
                tick++
                delay(2_000L)
            }
        }
    }

    fun fetchRange(startDate: String, endDate: String) {
        viewModelScope.launch {
            val config = configStore.getConfig() ?: run {
                _statusMessage.value = "Nincs beállítva a relé kapcsolat (lásd Beállítások)."
                return@launch
            }
            if (config.syncToken.isBlank()) {
                _statusMessage.value = "Nincs beállítva a sync token (lásd Beállítások)."
                return@launch
            }
            _isLoading.value = true
            val authHeader = "Bearer ${config.syncToken}"
            val api = renderApi ?: RetrofitFactory.createRender(config.relayUrl).also { renderApi = it }
            runCatching {
                _rangeData.value = api.getEnergyRange(config.homeId, authHeader, startDate, endDate)
            }.onFailure {
                Log.e("EnergyVM", "Render range fetch failed", it)
                _statusMessage.value = "Nem érhető el a Render API az egyedi időszakhoz."
            }
            _isLoading.value = false
        }
    }

    private fun fetchPvForecast() {
        viewModelScope.launch {
            val pvConfig = configStore.getPvForecastConfig()
            if (!pvConfig.isConfigured) {
                _pvForecast.value = null
                return@launch
            }
            runCatching {
                val forecast = weatherService.getForecast(
                    latitude = pvConfig.latitude!!,
                    longitude = pvConfig.longitude!!
                )
                _pvForecast.value = PvForecastCalculator.estimateToday(
                    forecast = forecast,
                    pvCapacityKwp = pvConfig.pvCapacityKwp!!,
                    performanceRatio = pvConfig.performanceRatio
                )
            }.onFailure {
                Log.e("EnergyVM", "Weather forecast fetch failed", it)
            }
        }
    }

    private suspend fun retryCommand(
        manager: SocketIoManager,
        action: String,
        params: Map<String, String> = emptyMap()
    ): JSONObject {
        var lastResponse = JSONObject().put("success", false).put("error", "No attempts made")
        for (attempt in 1..3) {
            lastResponse = manager.sendCommand("hub", action, params)
            if (lastResponse.optBoolean("success")) return lastResponse
            val errorMsg = lastResponse.optString("error", "")
            if (errorMsg.contains("Timeout") && attempt < 3) {
                kotlinx.coroutines.delay(3_000L)
            } else {
                return lastResponse
            }
        }
        return lastResponse
    }

    private fun ensureSocketConnected(config: com.homeassisthub.client.data.ClientConfig): SocketIoManager {
        return socketManager ?: SocketIoManager(config.relayUrl, config.homeId).also {
            it.setOnPeerJoined { role ->
                if (role == "hub") {
                    Log.i("EnergyVM", "Hub joined relay, auto-refreshing")
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
