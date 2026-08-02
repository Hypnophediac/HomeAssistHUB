package com.homeassisthub.hub.controller

import com.homeassisthub.hub.data.db.P1DataEntity
import com.homeassisthub.hub.data.db.P1Dao
import com.homeassisthub.hub.data.db.P1RawData
import com.homeassisthub.hub.data.db.P1RawDao
import com.homeassisthub.hub.data.network.P1MeterResponse
import com.homeassisthub.hub.security.DeviceCredential
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Polls a local ADA P1 meter every [pollIntervalMs] (default 60s) via a
 * plain HTTP GET, parses the JSON response with Moshi and persists each
 * reading into [P1Dao]. All network + DB work runs on [Dispatchers.IO].
 */
class P1MeterController(
    private val credential: DeviceCredential,
    private val p1Dao: P1Dao,
    private val httpClient: OkHttpClient,
    moshi: Moshi,
    private val scope: CoroutineScope,
    private val p1RawDao: P1RawDao? = null,
    private val pollIntervalMs: Long = 60_000L
) : DeviceController {

    override val deviceId: String = credential.deviceId

    private val adapter = moshi.adapter(P1MeterResponse::class.java)
    private var pollingJob: Job? = null

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        Log.i(TAG, "Starting P1 polling for ${credential.ipAddress}:${credential.port}")
        pollingJob = scope.launch(Dispatchers.IO) {
            while (true) {
                fetchOnce()
                delay(pollIntervalMs)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    suspend fun fetchOnce(): CommandResult = withContext(Dispatchers.IO) {
        runCatching {
            val url = "http://${credential.ipAddress}:${credential.port}/json"
            val request = Request.Builder().url(url).get().build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code} from P1 meter")
                }
                val body = response.body?.string().orEmpty()
                Log.i(TAG, "P1 raw JSON: $body")
                val parsed = adapter.fromJson(body) ?: error("Empty/invalid P1 meter response")

                val ts = System.currentTimeMillis()
                val entity = P1DataEntity(
                    timestamp = ts,
                    powerW = parsed.powerW,
                    voltageV = parsed.voltageV,
                    powerImportW = parsed.powerImportW,
                    powerExportW = parsed.powerExportW,
                    l1V = parsed.l1V,
                    l2V = parsed.l2V,
                    l3V = parsed.l3V,
                    l1A = parsed.l1A,
                    l2A = parsed.l2A,
                    l3A = parsed.l3A,
                    powerImportL1W = parsed.powerImportL1W,
                    powerImportL2W = parsed.powerImportL2W,
                    powerImportL3W = parsed.powerImportL3W,
                    powerExportL1W = parsed.powerExportL1W,
                    powerExportL2W = parsed.powerExportL2W,
                    powerExportL3W = parsed.powerExportL3W,
                    powerFactor = parsed.powerFactor,
                    frequencyHz = parsed.frequencyHz,
                    importT1Kwh = parsed.importT1Kwh,
                    importT2Kwh = parsed.importT2Kwh,
                    exportT1Kwh = parsed.exportT1Kwh,
                    exportT2Kwh = parsed.exportT2Kwh,
                    currentTariff = parsed.currentTariff
                )
                p1Dao.insert(entity)

                // Push into the time-series buffer for Kiosk scraper synchronization
                P1HistoryBuffer.add(
                    P1HistoryBuffer.P1Snapshot(
                        timestamp = ts,
                        powerImportW = parsed.powerImportW,
                        powerExportW = parsed.powerExportW,
                        importTotalKwh = parsed.importTotalKwh,
                        exportTotalKwh = parsed.exportTotalKwh
                    )
                )

                p1RawDao?.insert(P1RawData(
                    timestamp = ts,
                    importT1Kwh = parsed.importT1Kwh,
                    importT2Kwh = parsed.importT2Kwh,
                    exportT1Kwh = parsed.exportT1Kwh,
                    exportT2Kwh = parsed.exportT2Kwh,
                    importTotalKwh = parsed.importTotalKwh,
                    exportTotalKwh = parsed.exportTotalKwh,
                    currentPowerW = parsed.powerW,
                    powerImportW = parsed.powerImportW,
                    powerExportW = parsed.powerExportW,
                    l1V = parsed.l1V,
                    l2V = parsed.l2V,
                    l3V = parsed.l3V,
                    l1A = parsed.l1A,
                    l2A = parsed.l2A,
                    l3A = parsed.l3A,
                    powerImportL1W = parsed.powerImportL1W,
                    powerImportL2W = parsed.powerImportL2W,
                    powerImportL3W = parsed.powerImportL3W,
                    powerExportL1W = parsed.powerExportL1W,
                    powerExportL2W = parsed.powerExportL2W,
                    powerExportL3W = parsed.powerExportL3W,
                    powerFactor = parsed.powerFactor,
                    powerFactorL1 = parsed.powerFactorL1,
                    powerFactorL2 = parsed.powerFactorL2,
                    powerFactorL3 = parsed.powerFactorL3,
                    frequencyHz = parsed.frequencyHz,
                    reactiveImportKwh = parsed.reactiveImportKwh,
                    reactiveExportKwh = parsed.reactiveExportKwh,
                    currentTariff = parsed.currentTariff,
                    meterSerial = parsed.meterSerial,
                    deviceName = parsed.deviceName,
                    firmwareVersion = parsed.firmwareVersion,
                    circuitBreakerStatus = parsed.circuitBreakerStatus,
                    limiterThreshold = parsed.limiterThresholdStr?.toDoubleOrNull() ?: 0.0
                ))

                entity
            }
        }.fold(
            onSuccess = { entity ->
                Log.i(TAG, "P1 reading: power=${entity.powerW}W voltage=${entity.voltageV}V " +
                    "impL1=${entity.powerImportL1W}W impL2=${entity.powerImportL2W}W impL3=${entity.powerImportL3W}W " +
                    "expL1=${entity.powerExportL1W}W expL2=${entity.powerExportL2W}W expL3=${entity.powerExportL3W}W")
                CommandResult.Success(
                    mapOf(
                        "timestamp" to entity.timestamp,
                        "power_w" to entity.powerW,
                        "voltage_v" to entity.voltageV
                    )
                )
            },
            onFailure = { throwable ->
                Log.e(TAG, "P1 fetch failed: ${throwable.message}", throwable)
                CommandResult.Failure(throwable.message ?: "Unknown P1 meter error")
            }
        )
    }

    override suspend fun executeCommand(action: String, params: Map<String, String>): CommandResult {
        return when (action) {
            "refresh" -> fetchOnce()
            else -> CommandResult.Failure("Unsupported action '$action' for P1 meter")
        }
    }

    companion object {
        private const val TAG = "P1MeterController"
    }
}
