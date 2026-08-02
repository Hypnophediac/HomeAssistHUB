package com.homeassisthub.hub.controller

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Scrapes the Huawei FusionSolar Kiosk API for real-time inverter production data.
 *
 * The Kiosk URL looks like:
 *   https://uni002eu5.fusionsolar.huawei.com/pvmswebsite/nologin/assets/build/cloud.html#/kiosk?kk=n0uvBccyuPlyodtk9c46sHolzdwJDjrJ
 *
 * From this we extract:
 *   - server domain (e.g. "uni002eu5.fusionsolar.huawei.com")
 *   - kk token (e.g. "n0uvBccyuPlyodtk9c46sHolzdwJDjrJ")
 *
 * The actual REST API endpoint is:
 *   GET https://{domain}/rest/pvms/web/kiosk/v1/station-kiosk-file?kk={kk}
 *
 * Response structure (HTML-escaped JSON in "data" field):
 *   {
 *     realKpi: { realTimePower: 1.27, cumulativeEnergy, dailyEnergy, monthEnergy, yearEnergy },  // kW / kWh
 *     powerCurve: { xAxis: ["00:00", ...], activePower: ["-", "0.01", ...], currentPower: "1.25" }
 *   }
 */
class HuaweiCloudScraper(
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 60_000L // 1 minute
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var pollJob: Job? = null

    fun startPolling(kioskUrl: String) {
        stopPolling()
        val parsed = parseKioskUrl(kioskUrl) ?: run {
            Log.e(TAG, "Failed to parse kiosk URL: $kioskUrl")
            return
        }
        pollJob = scope.launch {
            Log.i(TAG, "Starting Kiosk scraper for ${parsed.domain}, kk=${parsed.kk.take(8)}...")
            while (isActive) {
                var success = false
                for (attempt in 1..3) {
                    if (!isActive) break
                    val result = runCatching { scrapeOnce(parsed) }
                    if (result.isSuccess) {
                        success = true
                        break
                    }
                    Log.w(TAG, "Scrape attempt $attempt/3 failed: ${result.exceptionOrNull()?.message}")
                    if (attempt < 3) delay(15_000L)
                }
                if (!success) {
                    Log.w(TAG, "All 3 scrape attempts failed, waiting for next poll cycle")
                }
                delay(pollIntervalMs)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun scrapeOnce(parsed: KioskConfig) {
        val url = "https://${parsed.domain}/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=${parsed.kk}"
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "Kiosk API returned HTTP ${response.code}")
            response.close()
            return
        }
        val body = response.body?.string() ?: run {
            response.close()
            return
        }
        response.close()

        val outer = JSONObject(body)
        if (outer.optInt("failCode", 0) != 0) {
            Log.w(TAG, "Kiosk API failCode=${outer.opt("failCode")}")
            return
        }

        val dataStr = unescapeHtml(outer.getString("data"))
        val data = JSONObject(dataStr)

        // Real-time power (kW → W)
        val realKpi = data.optJSONObject("realKpi")
        val realTimePowerKw = realKpi?.optDouble("realTimePower", 0.0) ?: 0.0
        val activePowerW = (realTimePowerKw * 1000.0)

        // Daily yield (kWh) from Kiosk API — hardware counter, precise
        val dailyEnergyKwh = realKpi?.optDouble("dailyEnergy", 0.0) ?: 0.0

        InverterLiveData.update(activePowerW)
        Log.i(TAG, "Kiosk scrape OK: ${activePowerW}W (realTimePower=${realTimePowerKw}kW), daily=${dailyEnergyKwh}kWh")

        // Compute synchronized house consumption using T-5min P1 data.
        // The Kiosk API is ~5 min delayed vs the real-time P1 meter, so we
        // must use the P1 reading from 5 minutes ago to get a physically
        // correct calculation:
        //   netGridW = P1ImportW - P1ExportW  (positive = importing, negative = exporting)
        //   RealConsumptionW = InverterProductionW + netGridW(T-5)
        val p1Sync = P1HistoryBuffer.findMinutesAgo(5)
        val realConsumptionW = if (p1Sync != null) {
            val netGridW = p1Sync.netGridW
            val computed = activePowerW + netGridW
            val floored = maxOf(0.0, computed)
            Log.i(TAG, "Synced house consumption: ${floored}W (inverter=${activePowerW}W, netGrid@T-5=${netGridW}W)")
            floored
        } else {
            Log.i(TAG, "No P1 data from T-5min available, house consumption = 0")
            0.0
        }

        // Cache the synchronized consumption + daily yield for CommandRouter
        InverterLiveData.updateRealConsumption(realConsumptionW, dailyEnergyKwh)

        // Also store today's power curve as history points
        val powerCurve = data.optJSONObject("powerCurve")
        if (powerCurve != null) {
            storePowerCurveAsHistory(powerCurve)
        }
    }

    private fun storePowerCurveAsHistory(powerCurve: JSONObject) {
        val xAxis = powerCurve.optJSONArray("xAxis") ?: return
        val activePower = powerCurve.optJSONArray("activePower") ?: return
        if (xAxis.length() != activePower.length()) return

        val today = Calendar.getInstance()
        val points = mutableListOf<com.homeassisthub.hub.data.db.InverterHistoryEntity>()
        for (i in 0 until xAxis.length()) {
            val timeStr = xAxis.getString(i)
            val powerStr = activePower.getString(i)
            if (powerStr == "-" || powerStr.isBlank()) continue
            val powerKw = powerStr.toDoubleOrNull() ?: continue
            val (h, m) = timeStr.split(":").let { parts ->
                (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
            }
            today.set(Calendar.HOUR_OF_DAY, h)
            today.set(Calendar.MINUTE, m)
            today.set(Calendar.SECOND, 0)
            today.set(Calendar.MILLISECOND, 0)
            // Skip future timestamps — Kiosk API may return 0 for hours
            // that haven't happened yet, which would poison the sync cursor
            if (today.timeInMillis > System.currentTimeMillis() + 60_000L) continue
            points.add(
                com.homeassisthub.hub.data.db.InverterHistoryEntity(
                    timestamp = today.timeInMillis,
                    activePowerW = powerKw * 1000.0
                )
            )
        }
        if (points.isNotEmpty()) {
            // Store asynchronously via the DAO
            scope.launch {
                runCatching {
                    InverterHistoryDaoHolder.dao?.insertAll(points)
                    Log.i(TAG, "Stored ${points.size} power curve points to history")
                }.onFailure { Log.w(TAG, "Failed to store power curve: ${it.message}") }
            }
        }
    }

    companion object {
        private const val TAG = "HuaweiCloudScraper"

        data class KioskConfig(val domain: String, val kk: String)

        /**
         * Parse a Kiosk URL and extract the server domain and kk token.
         * Accepts both full portal URLs and bare kk tokens.
         */
        fun parseKioskUrl(url: String): KioskConfig? {
            // Bare kk token (no URL, no slashes, no encoded chars)
            if (!url.contains("/") && !url.contains("=") && !url.contains("%") && url.length > 10) {
                return KioskConfig("uni002eu5.fusionsolar.huawei.com", url)
            }
            // First, URL-decode the input to handle %23 (#), %3F (?), %3D (=)
            val decoded = java.net.URLDecoder.decode(url, "UTF-8")
            // Extract kk from decoded URL
            val kkMatch = Regex("kk=([^&\\s#]+)").find(decoded)
            val kk = kkMatch?.groupValues?.get(1) ?: return null
            // Extract domain from decoded URL
            val domainMatch = Regex("https?://([^/]+)").find(decoded)
            val domain = domainMatch?.groupValues?.get(1) ?: "uni002eu5.fusionsolar.huawei.com"
            return KioskConfig(domain, kk)
        }

        private fun unescapeHtml(s: String): String {
            return s
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
        }
    }
}

/**
 * Holds a reference to the InverterHistoryDao so HuaweiCloudScraper can store
 * power curve points without needing the DAO in its constructor.
 */
object InverterHistoryDaoHolder {
    @Volatile
    var dao: com.homeassisthub.hub.data.db.InverterHistoryDao? = null
}
