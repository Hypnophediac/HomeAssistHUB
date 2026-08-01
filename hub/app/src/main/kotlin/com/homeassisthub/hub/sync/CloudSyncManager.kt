package com.homeassisthub.hub.sync

import android.util.Log
import com.homeassisthub.hub.data.HubConfigStore
import com.homeassisthub.hub.data.db.InverterDailySummaryDao
import com.homeassisthub.hub.data.db.InverterHistoryDao
import com.homeassisthub.hub.data.db.P1DailySummaryDao
import com.homeassisthub.hub.data.db.P1RawDao
import com.homeassisthub.hub.controller.InverterLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Periodically uploads P1 raw readings and inverter history from the local
 * Room database to the Render relay's /api/energy/ingest endpoint.
 *
 * Uses a persisted sync cursor (last successfully synced timestamp) so that
 * if the network is down, data accumulates locally (Room retains 7 days of
 * raw data) and the full backlog is uploaded in one batch when connectivity
 * returns.
 *
 * Also pushes finalized daily summaries (P1 + inverter) to the relay after
 * the midnight rollover worker computes them — these are the most valuable
 * aggregated data and get extra retry attempts.
 */
class CloudSyncManager(
    private val configStore: HubConfigStore,
    private val p1RawDao: P1RawDao,
    private val inverterHistoryDao: InverterHistoryDao,
    private val p1DailySummaryDao: P1DailySummaryDao,
    private val inverterDailySummaryDao: InverterDailySummaryDao,
    private val scope: CoroutineScope
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun start() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    syncBatch()
                    pushLiveInverterDaily()
                } catch (e: Exception) {
                    Log.w(TAG, "Sync cycle failed: ${e.message}")
                }
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    /** Pushes ALL locally stored daily summaries (P1 + inverter) to the cloud.
     *  Used at startup to backfill summaries computed before cloud sync was
     *  configured or while it was unreachable. The relay upserts, so
     *  re-pushing is idempotent. */
    /** Pushes today's live inverter daily yield (from Kiosk API) to the relay
     *  so the client can display producedKwh for the current day, not just
     *  finalized days. Called every sync cycle. */
    private suspend fun pushLiveInverterDaily() {
        try {
            val config = configStore.getConfig() ?: return
            if (config.syncToken.isBlank()) return
            if (!InverterLiveData.isFresh()) return
            val today = todayDateString()
            val dailyKwh = InverterLiveData.dailyEnergyKwh
            if (dailyKwh <= 0.0) return
            pushInverterDailySummary(today, dailyKwh)
        } catch (e: Exception) {
            Log.w(TAG, "Live inverter daily push failed: ${e.message}")
        }
    }

    fun pushAllDailySummaries() {
        scope.launch(Dispatchers.IO) {
            try {
                val config = configStore.getConfig() ?: return@launch
                if (config.syncToken.isBlank()) return@launch
                val dates = (
                    p1DailySummaryDao.getAll().map { it.date } +
                        inverterDailySummaryDao.getRange("1970-01-01", "2999-12-31").map { it.date }
                    ).distinct().sorted()
                for (d in dates) {
                    pushP1DailySummary(d)
                    pushInverterDailySummary(d)
                }
                Log.i(TAG, "Backfilled ${dates.size} daily summaries to cloud")
            } catch (e: Exception) {
                Log.w(TAG, "Daily summary backfill failed: ${e.message}")
            }
        }
    }

    /** Pushes a finalized daily summary to the cloud immediately after
     *  the midnight rollover computes it. Retries within the regular sync
     *  loop if this initial push fails. */
    fun pushDailySummary(dateStr: String) {
        scope.launch(Dispatchers.IO) {
            try {
                pushP1DailySummary(dateStr)
                pushInverterDailySummary(dateStr)
            } catch (e: Exception) {
                Log.w(TAG, "Daily summary push failed for $dateStr: ${e.message}")
            }
        }
    }

    private suspend fun syncBatch() {
        val config = configStore.getConfig() ?: run {
            Log.w(TAG, "syncBatch: no config, skipping")
            return
        }
        if (config.syncToken.isBlank()) {
            Log.d(TAG, "No sync token configured, skipping cloud sync")
            return
        }

        val cursor = configStore.getSyncCursor()
        // Self-heal: if cursor is in the future (e.g. poisoned by a past bug),
        // reset to 0 so all raw data can be re-synced from scratch
        val effectiveCursor = if (cursor > System.currentTimeMillis()) {
            Log.w(TAG, "syncBatch: cursor $cursor is in the future, resetting to 0")
            configStore.saveSyncCursor(0L)
            0L
        } else cursor
        val p1Readings = p1RawDao.getRangeSince(effectiveCursor, BATCH_LIMIT)
        val invCursor = effectiveCursor // same cursor for inverter history
        val inverterReadings = inverterHistoryDao.getRangeSince(invCursor, BATCH_LIMIT)

        Log.d(TAG, "syncBatch: cursor=$cursor, p1=${p1Readings.size}, inv=${inverterReadings.size}")

        if (p1Readings.isEmpty() && inverterReadings.isEmpty()) return

        val p1JsonArray = JSONArray()
        for (r in p1Readings) {
            p1JsonArray.put(JSONObject().apply {
                put("timestamp", r.timestamp)
                put("powerImportW", r.powerImportW)
                put("powerExportW", r.powerExportW)
                put("importT1Kwh", r.importT1Kwh)
                put("importT2Kwh", r.importT2Kwh)
                put("exportT1Kwh", r.exportT1Kwh)
                put("exportT2Kwh", r.exportT2Kwh)
                put("currentPowerW", r.currentPowerW)
                put("l1V", r.l1V)
                put("l2V", r.l2V)
                put("l3V", r.l3V)
                put("l1A", r.l1A)
                put("l2A", r.l2A)
                put("l3A", r.l3A)
                put("powerImportL1W", r.powerImportL1W)
                put("powerImportL2W", r.powerImportL2W)
                put("powerImportL3W", r.powerImportL3W)
                put("powerExportL1W", r.powerExportL1W)
                put("powerExportL2W", r.powerExportL2W)
                put("powerExportL3W", r.powerExportL3W)
                put("powerFactor", r.powerFactor)
                put("frequencyHz", r.frequencyHz)
                put("currentTariff", r.currentTariff)
            })
        }

        val invJsonArray = JSONArray()
        for (r in inverterReadings) {
            invJsonArray.put(JSONObject().apply {
                put("timestamp", r.timestamp)
                put("activePowerW", r.activePowerW)
                put("dailyEnergyKwh", 0) // not available in history entity
            })
        }

        val body = JSONObject().apply {
            put("homeId", config.homeId)
            put("p1Readings", p1JsonArray)
            put("inverterReadings", invJsonArray)
        }

        val ingestUrl = "${config.relayUrl.trimEnd('/')}/api/energy/${config.homeId}/ingest"
        val request = Request.Builder()
            .url(ingestUrl)
            .post(body.toString().toRequestBody(jsonMediaType))
            .addHeader("Authorization", "Bearer ${config.syncToken}")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val newCursor = minOf(
                        maxOf(
                            p1Readings.lastOrNull()?.timestamp ?: cursor,
                            inverterReadings.lastOrNull()?.timestamp ?: cursor
                        ),
                        System.currentTimeMillis() // never advance cursor into the future
                    )
                    configStore.saveSyncCursor(newCursor)
                    configStore.saveLastSyncTime(System.currentTimeMillis())
                    Log.i(TAG, "Synced ${p1Readings.size} P1 + ${inverterReadings.size} inverter readings, cursor=$newCursor")
                } else {
                    val respBody = response.body?.string().orEmpty()
                    Log.w(TAG, "Ingest failed: HTTP ${response.code} — $respBody")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ingest network error: ${e.message}")
        }
    }

    private suspend fun pushP1DailySummary(dateStr: String) {
        val config = configStore.getConfig() ?: return
        if (config.syncToken.isBlank()) return
        val summary = p1DailySummaryDao.getByDate(dateStr) ?: return

        val summaryJson = JSONObject().apply {
            put("date", summary.date)
            put("totalConsumedKwh", summary.totalConsumedKwh)
            put("totalExportedKwh", summary.totalExportedKwh)
            put("importT1Kwh", summary.importT1Kwh)
            put("importT2Kwh", summary.importT2Kwh)
            put("exportT1Kwh", summary.exportT1Kwh)
            put("exportT2Kwh", summary.exportT2Kwh)
            put("minPowerW", summary.minPowerW)
            put("maxPowerW", summary.maxPowerW)
            put("avgPowerW", summary.avgPowerW)
            put("maxImportW", summary.maxImportW)
            put("maxExportW", summary.maxExportW)
            put("peakConsumptionHour", summary.peakConsumptionHour)
            put("peakExportHour", summary.peakExportHour)
            put("peakConsumptionKwh", summary.peakConsumptionKwh)
            put("peakExportKwh", summary.peakExportKwh)
            put("selfConsumptionRatio", summary.selfConsumptionRatio)
            put("netEnergyKwh", summary.netEnergyKwh)
            put("avgL1V", summary.avgL1V)
            put("avgL2V", summary.avgL2V)
            put("avgL3V", summary.avgL3V)
            put("avgL1A", summary.avgL1A)
            put("avgL2A", summary.avgL2A)
            put("avgL3A", summary.avgL3A)
            put("avgPowerFactor", summary.avgPowerFactor)
            put("avgFrequencyHz", summary.avgFrequencyHz)
        }

        val body = JSONObject().apply {
            put("homeId", config.homeId)
            put("p1Summary", summaryJson)
        }

        val url = "${config.relayUrl.trimEnd('/')}/api/energy/${config.homeId}/daily-summary"
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMediaType))
            .addHeader("Authorization", "Bearer ${config.syncToken}")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                Log.i(TAG, "P1 daily summary pushed for $dateStr")
            } else {
                Log.w(TAG, "P1 daily summary push failed: HTTP ${response.code}")
            }
        }
    }

    private suspend fun pushInverterDailySummary(dateStr: String) {
        val config = configStore.getConfig() ?: return
        if (config.syncToken.isBlank()) return
        val summary = inverterDailySummaryDao.getByDate(dateStr) ?: return
        pushInverterDailySummary(dateStr, summary.producedKwh)
    }

    private suspend fun pushInverterDailySummary(dateStr: String, producedKwh: Double) {
        val config = configStore.getConfig() ?: return
        if (config.syncToken.isBlank()) return

        val invSummaryJson = JSONObject().apply {
            put("date", dateStr)
            put("producedKwh", producedKwh)
        }

        val body = JSONObject().apply {
            put("homeId", config.homeId)
            put("inverterSummary", invSummaryJson)
        }

        val url = "${config.relayUrl.trimEnd('/')}/api/energy/${config.homeId}/daily-summary"
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMediaType))
            .addHeader("Authorization", "Bearer ${config.syncToken}")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                Log.i(TAG, "Inverter daily summary pushed for $dateStr")
            } else {
                Log.w(TAG, "Inverter daily summary push failed: HTTP ${response.code}")
            }
        }
    }

    companion object {
        private const val TAG = "CloudSyncManager"
        private const val SYNC_INTERVAL_MS = 2 * 60 * 1000L // 2 minutes
        private const val BATCH_LIMIT = 500

        private fun todayDateString(): String {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getDefault()
            return sdf.format(java.util.Date())
        }
    }
}
