package com.homeassisthub.hub.bridge

import com.homeassisthub.hub.controller.CommandResult
import com.homeassisthub.hub.controller.DeviceController
import com.homeassisthub.hub.controller.DeviceControllerFactory
import com.homeassisthub.hub.data.db.InverterHistoryDao
import com.homeassisthub.hub.data.db.InverterHistoryEntity
import com.homeassisthub.hub.data.db.P1DailySummaryDao
import com.homeassisthub.hub.data.db.P1Dao
import com.homeassisthub.hub.data.db.P1RawDao
import com.homeassisthub.hub.discovery.DiscoveryManager
import com.homeassisthub.hub.security.DeviceCredential
import com.homeassisthub.hub.security.SecureCredentialStore
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class CommandRouter(
    private val credentialStore: SecureCredentialStore,
    private val controllerFactory: DeviceControllerFactory,
    private val discoveryManager: DiscoveryManager,
    private val p1Dao: P1Dao,
    private val p1RawDao: P1RawDao? = null,
    private val p1DailySummaryDao: P1DailySummaryDao? = null,
    private val inverterHistoryDao: InverterHistoryDao? = null,
    private val hubConfigStore: com.homeassisthub.hub.data.HubConfigStore? = null,
    private val kioskScraper: com.homeassisthub.hub.controller.HuaweiCloudScraper? = null
) {

    private val controllerCache = ConcurrentHashMap<String, DeviceController>()

    suspend fun handle(request: JSONObject): JSONObject {
        val homeId = request.optString("homeId")
        val requestId = request.optString("requestId")
        val deviceId = request.optString("deviceId")
        val action = request.optString("action")
        val params = request.optJSONObject("params").toStringMap()

        val outcome = runCatching {
            if (deviceId == HUB_PSEUDO_DEVICE_ID) {
                handleHubAction(action, params)
            } else {
                val controller = controllerCache.getOrPut(deviceId) {
                    val credential = credentialStore.getCredential(deviceId)
                        ?: error("No stored credential for device '$deviceId'")
                    controllerFactory.create(credential)
                        ?: error("Unsupported device type for '$deviceId'")
                }
                controller.executeCommand(action, params)
            }
        }

        return buildResponse(homeId, requestId, deviceId, action, outcome)
    }

    /**
     * Administrative actions targeting the Hub itself (not a physical
     * device), routed through the relay so the Client app can manage
     * devices and read P1 history remotely (mobile data), not just on LAN.
     */
    private suspend fun handleHubAction(action: String, params: Map<String, String>): CommandResult = when (action) {
        "list_devices" -> CommandResult.Success(
            mapOf("devices" to credentialStore.getAllCredentials().map { it.toSummaryMap() })
        )
        "save_kiosk_url" -> {
            val kioskUrl = params["kioskUrl"] ?: error("Missing kioskUrl")
            val store = hubConfigStore ?: error("Hub config store not available")
            val config = store.getConfig()
                ?: com.homeassisthub.hub.data.HubConfig("", "", kioskUrl)
            store.saveConfig(config.copy(kioskUrl = kioskUrl))
            Log.i("CommandRouter", "Kiosk URL saved, restarting scraper")
            kioskScraper?.stopPolling()
            if (kioskUrl.isNotBlank()) {
                kioskScraper?.startPolling(kioskUrl)
            }
            CommandResult.Success(mapOf("saved" to kioskUrl))
        }
        "get_kiosk_url" -> {
            val config = hubConfigStore?.getConfig()
            CommandResult.Success(mapOf("kioskUrl" to (config?.kioskUrl ?: "")))
        }
        "discover_devices" -> {
            val timeoutMs = params["timeoutMs"]?.toLongOrNull() ?: 3000L
            val devices = discoveryManager.discoverAll(timeoutMs)
            CommandResult.Success(
                mapOf(
                    "devices" to devices.map {
                        mapOf("name" to it.name, "ipAddress" to it.ipAddress, "port" to it.port, "source" to it.source.name)
                    }
                )
            )
        }
        "save_credential" -> {
            val credential = DeviceCredential(
                deviceId = params["deviceId"] ?: error("Missing deviceId"),
                deviceType = params["deviceType"] ?: error("Missing deviceType"),
                ipAddress = params["ipAddress"] ?: error("Missing ipAddress"),
                port = params["port"]?.toIntOrNull() ?: 80,
                username = params["username"] ?: "",
                password = params["password"] ?: ""
            )
            credentialStore.saveCredential(credential)
            controllerCache.remove(credential.deviceId)
            CommandResult.Success(mapOf("saved" to credential.toSummaryMap()))
        }
        "delete_credential" -> {
            val targetDeviceId = params["deviceId"] ?: error("Missing deviceId")
            credentialStore.removeCredential(targetDeviceId)
            controllerCache.remove(targetDeviceId)
            CommandResult.Success(mapOf("deleted" to targetDeviceId))
        }
        "get_p1_history" -> {
            val limit = params["limit"]?.toIntOrNull() ?: 100
            val readings = p1Dao.getRecent(limit)

            // The HuaweiCloudScraper computes a synchronized realConsumptionW
            // using T-5min P1 data (matching the Kiosk API delay) and caches it
            // in InverterLiveData. We use that cached value for the latest reading.
            val inverterFresh = com.homeassisthub.hub.controller.InverterLiveData.isFresh()
            val inverterPowerW = if (inverterFresh) {
                com.homeassisthub.hub.controller.InverterLiveData.activePowerW
            } else 0.0
            val cachedRealConsumptionW = if (inverterFresh) {
                com.homeassisthub.hub.controller.InverterLiveData.realConsumptionW
            } else 0.0
            val inverterDailyKwh = if (inverterFresh) {
                com.homeassisthub.hub.controller.InverterLiveData.dailyEnergyKwh
            } else 0.0

            // Fetch historical inverter data to merge into older readings
            val inverterHistory = inverterHistoryDao?.let { dao ->
                if (readings.isNotEmpty()) {
                    val oldestTs = readings.first().timestamp
                    val newestTs = readings.last().timestamp
                    dao.getRange(oldestTs, newestTs)
                } else emptyList()
            } ?: emptyList()
            val inverterByTime = inverterHistory.associate { it.timestamp to it.activePowerW }
            fun findInverterPower(ts: Long): Double {
                inverterByTime[ts]?.let { return it }
                val tolerance = 300_000L
                var bestPower = 0.0
                var bestDiff = Long.MAX_VALUE
                for (entry in inverterByTime) {
                    val diff = kotlin.math.abs(entry.key - ts)
                    if (diff < bestDiff && diff <= tolerance) {
                        bestDiff = diff
                        bestPower = entry.value
                    }
                }
                return bestPower
            }

            // Compute daily summary from hardware kWh counters:
            //   HouseDailyKwh = InverterDailyYield + P1DailyImport - P1DailyExport
            // P1 kWh counters are cumulative — use midnight baseline deltas from P1HistoryBuffer
            val (p1DailyImportKwh, p1DailyExportKwh) = com.homeassisthub.hub.controller.P1HistoryBuffer.getDailyKwhDeltas()
            val houseDailyKwh = maxOf(0.0, inverterDailyKwh + p1DailyImportKwh - p1DailyExportKwh)

            CommandResult.Success(
                mapOf(
                    "readings" to readings.mapIndexed { index, it ->
                        val isLatest = index == readings.lastIndex
                        // For latest reading: use the cached synchronized value from InverterLiveData
                        // (computed by HuaweiCloudScraper using T-5min P1 data).
                        // For historical readings: use backfilled inverter history if available.
                        val histInverterPower = if (isLatest) inverterPowerW else findInverterPower(it.timestamp)
                        val hasInverterData = if (isLatest) inverterFresh else (histInverterPower > 0.0)
                        val realConsumptionW = if (isLatest) {
                            // Use the synchronized cached value for the latest reading
                            if (inverterFresh) cachedRealConsumptionW else it.powerImportW
                        } else if (hasInverterData) {
                            maxOf(0.0, histInverterPower - it.powerExportW + it.powerImportW)
                        } else {
                            it.powerImportW
                        }
                        mapOf(
                            "timestamp" to it.timestamp,
                            "powerW" to it.powerW,
                            "voltageV" to it.voltageV,
                            "powerImportW" to it.powerImportW,
                            "powerExportW" to it.powerExportW,
                            "l1V" to it.l1V,
                            "l2V" to it.l2V,
                            "l3V" to it.l3V,
                            "l1A" to it.l1A,
                            "l2A" to it.l2A,
                            "l3A" to it.l3A,
                            "powerImportL1W" to it.powerImportL1W,
                            "powerImportL2W" to it.powerImportL2W,
                            "powerImportL3W" to it.powerImportL3W,
                            "powerExportL1W" to it.powerExportL1W,
                            "powerExportL2W" to it.powerExportL2W,
                            "powerExportL3W" to it.powerExportL3W,
                            "powerFactor" to it.powerFactor,
                            "frequencyHz" to it.frequencyHz,
                            "importT1Kwh" to it.importT1Kwh,
                            "importT2Kwh" to it.importT2Kwh,
                            "exportT1Kwh" to it.exportT1Kwh,
                            "exportT2Kwh" to it.exportT2Kwh,
                            "currentTariff" to it.currentTariff,
                            "inverterPowerW" to histInverterPower,
                            "realConsumptionW" to realConsumptionW
                        )
                    },
                    "dailySummary" to mapOf(
                        "inverterDailyKwh" to inverterDailyKwh,
                        "p1DailyImportKwh" to p1DailyImportKwh,
                        "p1DailyExportKwh" to p1DailyExportKwh,
                        "houseDailyKwh" to houseDailyKwh
                    )
                )
            )
        }
        "inverter_backfill" -> {
            // Receives historical inverter data points from the relay's FusionSolar API backfill.
            // params: points = JSON array of { timestamp, activePowerW }
            val dao = inverterHistoryDao ?: error("Inverter history DAO not available")
            val pointsJson = params["points"] ?: error("Missing 'points' parameter")
            val pointsArr = org.json.JSONArray(pointsJson)
            val entities = mutableListOf<InverterHistoryEntity>()
            for (i in 0 until pointsArr.length()) {
                val point = pointsArr.getJSONObject(i)
                entities.add(InverterHistoryEntity(
                    timestamp = point.getLong("timestamp"),
                    activePowerW = point.getDouble("activePowerW")
                ))
            }
            if (entities.isNotEmpty()) {
                dao.insertAll(entities)
            }
            Log.i("CommandRouter", "Inverter backfill: inserted ${entities.size} points")
            CommandResult.Success(mapOf(
                "inserted" to entities.size,
                "totalInDb" to dao.count()
            ))
        }
        "get_inverter_history" -> {
            val dao = inverterHistoryDao ?: error("Inverter history DAO not available")
            val limit = params["limit"]?.toIntOrNull() ?: 100
            val history = dao.getRecent(limit)
            CommandResult.Success(mapOf(
                "readings" to history.map {
                    mapOf(
                        "timestamp" to it.timestamp,
                        "activePowerW" to it.activePowerW
                    )
                },
                "count" to history.size,
                "totalInDb" to dao.count()
            ))
        }
        "get_energy_daily" -> {
            val rawDao = p1RawDao ?: error("P1 raw DAO not available")
            val today = todayDateString()
            val (startMs, endMs) = dayRangeMillis(today)
            val rawReadings = rawDao.getRange(startMs, endMs)
            val hourlyBuckets = Array(24) { mutableListOf<DoubleArray>() }
            for (reading in rawReadings) {
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = reading.timestamp
                val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                hourlyBuckets[hour].add(doubleArrayOf(
                    reading.importT1Kwh + reading.importT2Kwh,
                    reading.exportT1Kwh + reading.exportT2Kwh
                ))
            }
            val hourly = (0..23).map { h ->
                val bucket = hourlyBuckets[h]
                if (bucket.size >= 2) {
                    val first = bucket.first()
                    val last = bucket.last()
                    mapOf(
                        "hour" to h,
                        "consumedKwh" to ((last[0] - first[0]).coerceAtLeast(0.0)),
                        "exportedKwh" to ((last[1] - first[1]).coerceAtLeast(0.0))
                    )
                } else {
                    mapOf("hour" to h, "consumedKwh" to 0.0, "exportedKwh" to 0.0)
                }
            }
            val latest = rawDao.getLatest()
            // Compute live from today's raw readings — the persisted P1DailySummary
            // is only written at midnight for the *previous* completed day.
            val stats = com.homeassisthub.hub.data.db.DailyStatsCalculator.compute(rawReadings)
            CommandResult.Success(mapOf(
                "hourly" to hourly,
                "latestPowerW" to (latest?.currentPowerW ?: 0.0),
                "latestL1V" to (latest?.l1V ?: 0.0),
                "latestL2V" to (latest?.l2V ?: 0.0),
                "latestL3V" to (latest?.l3V ?: 0.0),
                "totalConsumedKwh" to hourly.sumOf { (it["consumedKwh"] as Double) },
                "totalExportedKwh" to hourly.sumOf { (it["exportedKwh"] as Double) },
                "latestPowerImportW" to (latest?.powerImportW ?: 0.0),
                "latestPowerExportW" to (latest?.powerExportW ?: 0.0),
                "latestL1A" to (latest?.l1A ?: 0.0),
                "latestL2A" to (latest?.l2A ?: 0.0),
                "latestL3A" to (latest?.l3A ?: 0.0),
                "latestPowerImportL1W" to (latest?.powerImportL1W ?: 0.0),
                "latestPowerImportL2W" to (latest?.powerImportL2W ?: 0.0),
                "latestPowerImportL3W" to (latest?.powerImportL3W ?: 0.0),
                "latestPowerExportL1W" to (latest?.powerExportL1W ?: 0.0),
                "latestPowerExportL2W" to (latest?.powerExportL2W ?: 0.0),
                "latestPowerExportL3W" to (latest?.powerExportL3W ?: 0.0),
                "latestPowerFactor" to (latest?.powerFactor ?: 0.0),
                "latestFrequencyHz" to (latest?.frequencyHz ?: 50.0),
                "latestCurrentTariff" to (latest?.currentTariff ?: 1),
                "minPowerW" to (stats?.minPowerW ?: 0.0),
                "maxPowerW" to (stats?.maxPowerW ?: 0.0),
                "avgPowerW" to (stats?.avgPowerW ?: 0.0),
                "maxImportW" to (stats?.maxImportW ?: 0.0),
                "maxExportW" to (stats?.maxExportW ?: 0.0),
                "peakConsumptionHour" to (stats?.peakConsumptionHour ?: -1),
                "peakExportHour" to (stats?.peakExportHour ?: -1),
                "peakConsumptionKwh" to (stats?.peakConsumptionKwh ?: 0.0),
                "peakExportKwh" to (stats?.peakExportKwh ?: 0.0),
                "selfConsumptionRatio" to (stats?.selfConsumptionRatio ?: 0.0),
                "netEnergyKwh" to (stats?.netEnergyKwh ?: 0.0),
                "importT1Kwh" to (stats?.importT1Kwh ?: 0.0),
                "importT2Kwh" to (stats?.importT2Kwh ?: 0.0),
                "exportT1Kwh" to (stats?.exportT1Kwh ?: 0.0),
                "exportT2Kwh" to (stats?.exportT2Kwh ?: 0.0),
                "avgL1V" to (stats?.avgL1V ?: 0.0),
                "avgL2V" to (stats?.avgL2V ?: 0.0),
                "avgL3V" to (stats?.avgL3V ?: 0.0),
                "avgL1A" to (stats?.avgL1A ?: 0.0),
                "avgL2A" to (stats?.avgL2A ?: 0.0),
                "avgL3A" to (stats?.avgL3A ?: 0.0),
                "avgPowerFactor" to (stats?.avgPowerFactor ?: 0.0),
                "avgFrequencyHz" to (stats?.avgFrequencyHz ?: 50.0)
            ))
        }
        "get_energy_weekly" -> {
            val rawDao = p1RawDao ?: error("P1 raw DAO not available")
            val entries = mutableListOf<Map<String, Any?>>()
            var totalConsumed = 0.0
            var totalExported = 0.0
            for (i in 6 downTo 0) {
                val cal = java.util.Calendar.getInstance()
                cal.add(java.util.Calendar.DAY_OF_YEAR, -i)
                val dateStr = dateStringFromCal(cal)
                val (sMs, eMs) = dayRangeMillis(dateStr)
                val first = rawDao.getFirstInRange(sMs, eMs)
                val last = rawDao.getLastInRange(sMs, eMs)
                val consumed = if (first != null && last != null) {
                    ((last.importT1Kwh + last.importT2Kwh) - (first.importT1Kwh + first.importT2Kwh)).coerceAtLeast(0.0)
                } else 0.0
                val exported = if (first != null && last != null) {
                    ((last.exportT1Kwh + last.exportT2Kwh) - (first.exportT1Kwh + first.exportT2Kwh)).coerceAtLeast(0.0)
                } else 0.0
                entries.add(mapOf("label" to dateStr.substring(5), "consumedKwh" to consumed, "exportedKwh" to exported))
                totalConsumed += consumed
                totalExported += exported
            }
            CommandResult.Success(mapOf("entries" to entries, "totalConsumedKwh" to totalConsumed, "totalExportedKwh" to totalExported))
        }
        "get_energy_monthly" -> {
            val rawDao = p1RawDao ?: error("P1 raw DAO not available")
            val cal = java.util.Calendar.getInstance()
            val currentMonth = cal.get(java.util.Calendar.MONTH)
            val currentYear = cal.get(java.util.Calendar.YEAR)
            val daysInMonth = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
            val entries = mutableListOf<Map<String, Any?>>()
            var totalConsumed = 0.0
            var totalExported = 0.0
            for (day in 1..daysInMonth) {
                val dateStr = String.format("%04d-%02d-%02d", currentYear, currentMonth + 1, day)
                val (sMs, eMs) = dayRangeMillis(dateStr)
                val first = rawDao.getFirstInRange(sMs, eMs)
                val last = rawDao.getLastInRange(sMs, eMs)
                val consumed = if (first != null && last != null) {
                    ((last.importT1Kwh + last.importT2Kwh) - (first.importT1Kwh + first.importT2Kwh)).coerceAtLeast(0.0)
                } else 0.0
                val exported = if (first != null && last != null) {
                    ((last.exportT1Kwh + last.exportT2Kwh) - (first.exportT1Kwh + first.exportT2Kwh)).coerceAtLeast(0.0)
                } else 0.0
                entries.add(mapOf("label" to day.toString(), "consumedKwh" to consumed, "exportedKwh" to exported))
                totalConsumed += consumed
                totalExported += exported
            }
            CommandResult.Success(mapOf("entries" to entries, "totalConsumedKwh" to totalConsumed, "totalExportedKwh" to totalExported))
        }
        "get_energy_yearly" -> {
            val rawDao = p1RawDao ?: error("P1 raw DAO not available")
            val cal = java.util.Calendar.getInstance()
            val currentYear = cal.get(java.util.Calendar.YEAR)
            val entries = mutableListOf<Map<String, Any?>>()
            var totalConsumed = 0.0
            var totalExported = 0.0
            for (month in 1..12) {
                val cal2 = java.util.Calendar.getInstance()
                cal2.set(currentYear, month - 1, 1, 0, 0, 0)
                cal2.set(java.util.Calendar.MILLISECOND, 0)
                val startMs = cal2.timeInMillis
                cal2.add(java.util.Calendar.MONTH, 1)
                val endMs = cal2.timeInMillis
                val first = rawDao.getFirstInRange(startMs, endMs)
                val last = rawDao.getLastInRange(startMs, endMs)
                val consumed = if (first != null && last != null) {
                    ((last.importT1Kwh + last.importT2Kwh) - (first.importT1Kwh + first.importT2Kwh)).coerceAtLeast(0.0)
                } else 0.0
                val exported = if (first != null && last != null) {
                    ((last.exportT1Kwh + last.exportT2Kwh) - (first.exportT1Kwh + first.exportT2Kwh)).coerceAtLeast(0.0)
                } else 0.0
                val monthLabel = java.text.SimpleDateFormat("MMM", java.util.Locale.US).format(
                    java.util.Date(currentYear - 1900, month - 1, 1)
                )
                entries.add(mapOf("label" to monthLabel, "consumedKwh" to consumed, "exportedKwh" to exported))
                totalConsumed += consumed
                totalExported += exported
            }
            CommandResult.Success(mapOf("entries" to entries, "totalConsumedKwh" to totalConsumed, "totalExportedKwh" to totalExported))
        }
        else -> CommandResult.Failure("Unsupported hub action '$action'")
    }

    private fun DeviceCredential.toSummaryMap(): Map<String, Any?> = mutableMapOf(
        "deviceId" to deviceId,
        "deviceType" to deviceType,
        "ipAddress" to ipAddress,
        "port" to port,
        "username" to username
    ).apply {
        if (deviceType == DeviceControllerFactory.DEVICE_TYPE_RTSP_CAMERA) {
            this["streamUrl"] = ipAddress
        }
    }

    private fun buildResponse(homeId: String, requestId: String, deviceId: String, action: String, outcome: Result<CommandResult>): JSONObject {
        val response = JSONObject()
            .put("homeId", homeId)
            .put("requestId", requestId)

        outcome.fold(
            onSuccess = { result ->
                when (result) {
                    is CommandResult.Success -> {
                        Log.d(TAG, "Command success: deviceId=$deviceId action=$action")
                        response.put("success", true).put("data", mapToJson(result.data))
                    }
                    is CommandResult.Failure -> {
                        Log.w(TAG, "Command failure: deviceId=$deviceId action=$action error=${result.error}")
                        response.put("success", false).put("error", result.error)
                    }
                }
            },
            onFailure = { throwable ->
                Log.e(TAG, "Command exception: deviceId=$deviceId action=$action", throwable)
                response.put("success", false).put("error", throwable.message ?: "Unknown error")
            }
        )

        return response
    }

    /** Recursively converts a Map<String, Any?> into a JSONObject, handling nested Maps and Lists. */
    private fun mapToJson(data: Map<String, Any?>): JSONObject {
        val json = JSONObject()
        for ((key, value) in data) {
            when (value) {
                is Map<*, *> -> json.put(key, mapToJson(value.mapKeys { it.key.toString() }.mapValues { it.value }))
                is List<*> -> {
                    val arr = org.json.JSONArray()
                    for (item in value) {
                        when (item) {
                            is Map<*, *> -> arr.put(mapToJson(item.mapKeys { it.key.toString() }.mapValues { it.value }))
                            else -> arr.put(item)
                        }
                    }
                    json.put(key, arr)
                }
                else -> json.put(key, value)
            }
        }
        return json
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return keys().asSequence().associateWith { key -> optString(key) }
    }

    private fun todayDateString(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return sdf.format(java.util.Date())
    }

    private fun dateStringFromCal(cal: java.util.Calendar): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return sdf.format(cal.time)
    }

    private fun dayRangeMillis(dateStr: String): Pair<Long, Long> {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getDefault()
        val date = sdf.parse(dateStr) ?: return 0L to 0L
        val cal = java.util.Calendar.getInstance()
        cal.time = date
        val startMs = cal.timeInMillis
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        val endMs = cal.timeInMillis
        return startMs to endMs
    }

    companion object {
        private const val TAG = "CommandRouter"
        private const val HUB_PSEUDO_DEVICE_ID = "hub"
    }
}
