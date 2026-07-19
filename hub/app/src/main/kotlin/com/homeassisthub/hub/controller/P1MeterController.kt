package com.homeassisthub.hub.controller

import com.homeassisthub.hub.data.db.P1DataEntity
import com.homeassisthub.hub.data.db.P1Dao
import com.homeassisthub.hub.data.network.P1MeterResponse
import com.homeassisthub.hub.security.DeviceCredential
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val pollIntervalMs: Long = 60_000L
) : DeviceController {

    override val deviceId: String = credential.deviceId

    private val adapter = moshi.adapter(P1MeterResponse::class.java)
    private var pollingJob: Job? = null

    fun startPolling() {
        if (pollingJob?.isActive == true) return
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
            val url = "http://${credential.ipAddress}:${credential.port}/api/v1/data"
            val request = Request.Builder().url(url).get().build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code} from P1 meter")
                }
                val body = response.body?.string().orEmpty()
                val parsed = adapter.fromJson(body) ?: error("Empty/invalid P1 meter response")

                val entity = P1DataEntity(
                    timestamp = parsed.timestamp ?: System.currentTimeMillis(),
                    powerW = parsed.powerW,
                    voltageV = parsed.voltageV
                )
                p1Dao.insert(entity)
                entity
            }
        }.fold(
            onSuccess = { entity ->
                CommandResult.Success(
                    mapOf(
                        "timestamp" to entity.timestamp,
                        "power_w" to entity.powerW,
                        "voltage_v" to entity.voltageV
                    )
                )
            },
            onFailure = { throwable -> CommandResult.Failure(throwable.message ?: "Unknown P1 meter error") }
        )
    }

    override suspend fun executeCommand(action: String, params: Map<String, String>): CommandResult {
        return when (action) {
            "refresh" -> fetchOnce()
            else -> CommandResult.Failure("Unsupported action '$action' for P1 meter")
        }
    }
}
