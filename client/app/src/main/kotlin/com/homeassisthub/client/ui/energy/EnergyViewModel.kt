package com.homeassisthub.client.ui.energy

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homeassisthub.client.data.ClientConfigStore
import com.homeassisthub.client.network.JsonParsing
import com.homeassisthub.client.network.RetrofitFactory
import com.homeassisthub.client.network.SocketIoManager
import com.homeassisthub.client.network.model.EnergyDailyResponseDto
import com.homeassisthub.client.network.model.EnergyPeriodResponseDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class EnergyViewModel(application: Application) : AndroidViewModel(application) {

    private val configStore = ClientConfigStore(application)
    private var socketManager: SocketIoManager? = null

    private val _dailyData = MutableStateFlow<EnergyDailyResponseDto?>(null)
    val dailyData: StateFlow<EnergyDailyResponseDto?> = _dailyData.asStateFlow()

    private val _weeklyData = MutableStateFlow<EnergyPeriodResponseDto?>(null)
    val weeklyData: StateFlow<EnergyPeriodResponseDto?> = _weeklyData.asStateFlow()

    private val _monthlyData = MutableStateFlow<EnergyPeriodResponseDto?>(null)
    val monthlyData: StateFlow<EnergyPeriodResponseDto?> = _monthlyData.asStateFlow()

    private val _yearlyData = MutableStateFlow<EnergyPeriodResponseDto?>(null)
    val yearlyData: StateFlow<EnergyPeriodResponseDto?> = _yearlyData.asStateFlow()

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
            _isLoading.value = true
            val manager = ensureSocketConnected(config)
            var anySuccess = false
            runCatching {
                val dailyResp = retryCommand(manager, "get_energy_daily")
                if (dailyResp.optBoolean("success")) {
                    val data = dailyResp.optJSONObject("data")
                    _dailyData.value = parseDailyResponse(data)
                    anySuccess = true
                }

                val weeklyResp = retryCommand(manager, "get_energy_weekly")
                if (weeklyResp.optBoolean("success")) {
                    val data = weeklyResp.optJSONObject("data")
                    _weeklyData.value = parsePeriodResponse(data)
                    anySuccess = true
                }

                val monthlyResp = retryCommand(manager, "get_energy_monthly")
                if (monthlyResp.optBoolean("success")) {
                    val data = monthlyResp.optJSONObject("data")
                    _monthlyData.value = parsePeriodResponse(data)
                    anySuccess = true
                }

                val yearlyResp = retryCommand(manager, "get_energy_yearly")
                if (yearlyResp.optBoolean("success")) {
                    val data = yearlyResp.optJSONObject("data")
                    _yearlyData.value = parsePeriodResponse(data)
                    anySuccess = true
                }
            }.onFailure {
                Log.e("EnergyVM", "Socket.IO refresh failed", it)
            }

            if (!anySuccess) {
                Log.i("EnergyVM", "Socket.IO failed, trying LAN Retrofit API at ${config.hubLocalBaseUrl}")
                runCatching {
                    val api = RetrofitFactory.create(config.hubLocalBaseUrl)
                    _dailyData.value = api.getEnergyDaily()
                    _weeklyData.value = api.getEnergyWeekly()
                    _monthlyData.value = api.getEnergyMonthly()
                    _yearlyData.value = api.getEnergyYearly()
                    anySuccess = true
                }.onFailure {
                    Log.e("EnergyVM", "Retrofit LAN API also failed", it)
                    _statusMessage.value = "Nem érhető el a Hub (sem relé, sem LAN)."
                }
            }

            _isLoading.value = false
        }
    }

    private fun parseDailyResponse(data: JSONObject?): EnergyDailyResponseDto? {
        if (data == null) return null
        val hourlyArr = data.optJSONArray("hourly") ?: return null
        val hourly = (0 until hourlyArr.length()).map { i ->
            val item = hourlyArr.getJSONObject(i)
            com.homeassisthub.client.network.model.EnergyHourlyDto(
                hour = item.optInt("hour"),
                consumedKwh = item.optDouble("consumedKwh"),
                exportedKwh = item.optDouble("exportedKwh")
            )
        }
        return EnergyDailyResponseDto(
            hourly = hourly,
            latestPowerW = data.optDouble("latestPowerW"),
            latestL1V = data.optDouble("latestL1V"),
            latestL2V = data.optDouble("latestL2V"),
            latestL3V = data.optDouble("latestL3V"),
            totalConsumedKwh = data.optDouble("totalConsumedKwh"),
            totalExportedKwh = data.optDouble("totalExportedKwh"),
            latestPowerImportW = data.optDouble("latestPowerImportW"),
            latestPowerExportW = data.optDouble("latestPowerExportW"),
            latestL1A = data.optDouble("latestL1A"),
            latestL2A = data.optDouble("latestL2A"),
            latestL3A = data.optDouble("latestL3A"),
            latestPowerImportL1W = data.optDouble("latestPowerImportL1W"),
            latestPowerImportL2W = data.optDouble("latestPowerImportL2W"),
            latestPowerImportL3W = data.optDouble("latestPowerImportL3W"),
            latestPowerExportL1W = data.optDouble("latestPowerExportL1W"),
            latestPowerExportL2W = data.optDouble("latestPowerExportL2W"),
            latestPowerExportL3W = data.optDouble("latestPowerExportL3W"),
            latestPowerFactor = data.optDouble("latestPowerFactor"),
            latestFrequencyHz = data.optDouble("latestFrequencyHz", 50.0),
            latestCurrentTariff = data.optInt("latestCurrentTariff", 1),
            minPowerW = data.optDouble("minPowerW"),
            maxPowerW = data.optDouble("maxPowerW"),
            avgPowerW = data.optDouble("avgPowerW"),
            maxImportW = data.optDouble("maxImportW"),
            maxExportW = data.optDouble("maxExportW"),
            peakConsumptionHour = data.optInt("peakConsumptionHour", -1),
            peakExportHour = data.optInt("peakExportHour", -1),
            peakConsumptionKwh = data.optDouble("peakConsumptionKwh"),
            peakExportKwh = data.optDouble("peakExportKwh"),
            selfConsumptionRatio = data.optDouble("selfConsumptionRatio"),
            netEnergyKwh = data.optDouble("netEnergyKwh"),
            importT1Kwh = data.optDouble("importT1Kwh"),
            importT2Kwh = data.optDouble("importT2Kwh"),
            exportT1Kwh = data.optDouble("exportT1Kwh"),
            exportT2Kwh = data.optDouble("exportT2Kwh"),
            avgL1V = data.optDouble("avgL1V"),
            avgL2V = data.optDouble("avgL2V"),
            avgL3V = data.optDouble("avgL3V"),
            avgL1A = data.optDouble("avgL1A"),
            avgL2A = data.optDouble("avgL2A"),
            avgL3A = data.optDouble("avgL3A"),
            avgPowerFactor = data.optDouble("avgPowerFactor"),
            avgFrequencyHz = data.optDouble("avgFrequencyHz", 50.0)
        )
    }

    private fun parsePeriodResponse(data: JSONObject?): EnergyPeriodResponseDto? {
        if (data == null) return null
        val entriesArr = data.optJSONArray("entries") ?: return null
        val entries = (0 until entriesArr.length()).map { i ->
            val item = entriesArr.getJSONObject(i)
            com.homeassisthub.client.network.model.EnergyPeriodEntryDto(
                label = item.optString("label"),
                consumedKwh = item.optDouble("consumedKwh"),
                exportedKwh = item.optDouble("exportedKwh")
            )
        }
        return EnergyPeriodResponseDto(
            entries = entries,
            totalConsumedKwh = data.optDouble("totalConsumedKwh"),
            totalExportedKwh = data.optDouble("totalExportedKwh")
        )
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
        socketManager?.disconnect()
        super.onCleared()
    }
}
