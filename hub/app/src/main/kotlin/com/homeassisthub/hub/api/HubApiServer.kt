package com.homeassisthub.hub.api

import com.homeassisthub.hub.api.dto.ApiErrorDto
import com.homeassisthub.hub.api.dto.ApiHealthDto
import com.homeassisthub.hub.api.dto.DeviceCredentialDto
import com.homeassisthub.hub.api.dto.EnergyDailyResponseDto
import com.homeassisthub.hub.api.dto.EnergyHourlyDto
import com.homeassisthub.hub.api.dto.EnergyPeriodEntryDto
import com.homeassisthub.hub.api.dto.EnergyPeriodResponseDto
import com.homeassisthub.hub.api.dto.toDomain
import com.homeassisthub.hub.api.dto.toDto
import com.homeassisthub.hub.api.dto.toSummaryDto
import com.homeassisthub.hub.data.db.InverterDailySummaryDao
import com.homeassisthub.hub.data.db.P1DailySummaryDao
import com.homeassisthub.hub.data.db.P1Dao
import com.homeassisthub.hub.data.db.P1RawDao
import com.homeassisthub.hub.discovery.DiscoveryManager
import com.homeassisthub.hub.security.SecureCredentialStore
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * Local-network REST API (Ktor/Netty) used by the Android Client app's
 * Settings screen when it is on the same LAN as the Hub: device
 * discovery, credential management and P1 meter history for charts.
 *
 * This is intentionally separate from the Socket.IO cloud bridge
 * ([com.homeassisthub.hub.bridge.HubSocketClient]), which handles
 * remote command/response + WebRTC signaling.
 */
class HubApiServer(
    private val discoveryManager: DiscoveryManager,
    private val credentialStore: SecureCredentialStore,
    private val p1Dao: P1Dao,
    private val p1RawDao: P1RawDao,
    private val p1DailySummaryDao: P1DailySummaryDao,
    private val inverterDailySummaryDao: InverterDailySummaryDao?,
    private val port: Int = DEFAULT_PORT
) {

    private var engine: ApplicationEngine? = null
    private val startTimeMs = System.currentTimeMillis()

    fun start() {
        if (engine != null) return
        engine = embeddedServer(Netty, port = port) {
            install(ContentNegotiation) { json() }

            routing {
                get("/api/v1/health") {
                    call.respond(ApiHealthDto("ok", System.currentTimeMillis() - startTimeMs))
                }

                get("/api/v1/devices/discover") {
                    val timeoutMs = call.request.queryParameters["timeoutMs"]?.toLongOrNull() ?: 3000L
                    val devices = discoveryManager.discoverAll(timeoutMs)
                    call.respond(devices.map { it.toDto() })
                }

                get("/api/v1/devices") {
                    call.respond(credentialStore.getAllCredentials().map { it.toSummaryDto() })
                }

                post("/api/v1/devices") {
                    val body = runCatching { call.receive<DeviceCredentialDto>() }.getOrNull()
                    if (body == null || body.deviceId.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ApiErrorDto("Invalid device payload"))
                        return@post
                    }
                    credentialStore.saveCredential(body.toDomain())
                    call.respond(HttpStatusCode.Created, body.toDomain().toSummaryDto())
                }

                delete("/api/v1/devices/{deviceId}") {
                    val deviceId = call.parameters["deviceId"]
                    if (deviceId.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ApiErrorDto("Missing deviceId"))
                        return@delete
                    }
                    credentialStore.removeCredential(deviceId)
                    call.respond(HttpStatusCode.NoContent)
                }

                get("/api/v1/p1/history") {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                    call.respond(p1Dao.getRecent(limit).map { it.toDto() })
                }

                get("/api/v1/energy/daily") {
                    val today = todayDateString()
                    val (startMs, endMs) = dayRangeMillis(today)
                    val rawReadings = p1RawDao.getRange(startMs, endMs)

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
                            EnergyHourlyDto(
                                hour = h,
                                consumedKwh = (last[0] - first[0]).coerceAtLeast(0.0),
                                exportedKwh = (last[1] - first[1]).coerceAtLeast(0.0)
                            )
                        } else {
                            EnergyHourlyDto(hour = h, consumedKwh = 0.0, exportedKwh = 0.0)
                        }
                    }

                    val latest = p1RawDao.getLatest()
                    val totalConsumed = hourly.sumOf { it.consumedKwh }
                    val totalExported = hourly.sumOf { it.exportedKwh }

                    // ── Daily statistics computed live from today's raw readings ──
                    // (not from P1DailySummary, which is only persisted at midnight
                    // for the *previous* completed day and would always be empty for "today").
                    val invDaily = inverterDailySummaryDao?.getByDate(today)
                    val producedKwh = invDaily?.producedKwh
                        ?: if (com.homeassisthub.hub.controller.InverterLiveData.isFresh()) com.homeassisthub.hub.controller.InverterLiveData.dailyEnergyKwh else 0.0
                    val stats = com.homeassisthub.hub.data.db.DailyStatsCalculator.compute(rawReadings, producedKwh)

                    call.respond(EnergyDailyResponseDto(
                        hourly = hourly,
                        latestPowerW = latest?.currentPowerW ?: 0.0,
                        latestL1V = latest?.l1V ?: 0.0,
                        latestL2V = latest?.l2V ?: 0.0,
                        latestL3V = latest?.l3V ?: 0.0,
                        totalConsumedKwh = totalConsumed,
                        totalExportedKwh = totalExported,
                        latestPowerImportW = latest?.powerImportW ?: 0.0,
                        latestPowerExportW = latest?.powerExportW ?: 0.0,
                        latestL1A = latest?.l1A ?: 0.0,
                        latestL2A = latest?.l2A ?: 0.0,
                        latestL3A = latest?.l3A ?: 0.0,
                        latestPowerImportL1W = latest?.powerImportL1W ?: 0.0,
                        latestPowerImportL2W = latest?.powerImportL2W ?: 0.0,
                        latestPowerImportL3W = latest?.powerImportL3W ?: 0.0,
                        latestPowerExportL1W = latest?.powerExportL1W ?: 0.0,
                        latestPowerExportL2W = latest?.powerExportL2W ?: 0.0,
                        latestPowerExportL3W = latest?.powerExportL3W ?: 0.0,
                        latestPowerFactor = latest?.powerFactor ?: 0.0,
                        latestFrequencyHz = latest?.frequencyHz ?: 50.0,
                        latestCurrentTariff = latest?.currentTariff ?: 1,
                        minPowerW = stats?.minPowerW ?: 0.0,
                        maxPowerW = stats?.maxPowerW ?: 0.0,
                        avgPowerW = stats?.avgPowerW ?: 0.0,
                        maxImportW = stats?.maxImportW ?: 0.0,
                        maxExportW = stats?.maxExportW ?: 0.0,
                        peakConsumptionHour = stats?.peakConsumptionHour ?: -1,
                        peakExportHour = stats?.peakExportHour ?: -1,
                        peakConsumptionKwh = stats?.peakConsumptionKwh ?: 0.0,
                        peakExportKwh = stats?.peakExportKwh ?: 0.0,
                        selfConsumptionRatio = stats?.selfConsumptionRatio ?: 0.0,
                        netEnergyKwh = stats?.netEnergyKwh ?: 0.0,
                        importT1Kwh = stats?.importT1Kwh ?: 0.0,
                        importT2Kwh = stats?.importT2Kwh ?: 0.0,
                        exportT1Kwh = stats?.exportT1Kwh ?: 0.0,
                        exportT2Kwh = stats?.exportT2Kwh ?: 0.0,
                        avgL1V = stats?.avgL1V ?: 0.0,
                        avgL2V = stats?.avgL2V ?: 0.0,
                        avgL3V = stats?.avgL3V ?: 0.0,
                        avgL1A = stats?.avgL1A ?: 0.0,
                        avgL2A = stats?.avgL2A ?: 0.0,
                        avgL3A = stats?.avgL3A ?: 0.0,
                        avgPowerFactor = stats?.avgPowerFactor ?: 0.0,
                        avgFrequencyHz = stats?.avgFrequencyHz ?: 50.0
                    ))
                }

                get("/api/v1/energy/weekly") {
                    val summaries = mutableListOf<EnergyPeriodEntryDto>()
                    val cal = java.util.Calendar.getInstance()
                    var totalConsumed = 0.0
                    var totalExported = 0.0
                    for (i in 6 downTo 0) {
                        cal.timeInMillis = System.currentTimeMillis()
                        cal.add(java.util.Calendar.DAY_OF_YEAR, -i)
                        val dateStr = dateStringFromCal(cal)
                        val (sMs, eMs) = dayRangeMillis(dateStr)
                        val first = p1RawDao.getFirstInRange(sMs, eMs)
                        val last = p1RawDao.getLastInRange(sMs, eMs)
                        val consumed = if (first != null && last != null) {
                            ((last.importT1Kwh + last.importT2Kwh) - (first.importT1Kwh + first.importT2Kwh)).coerceAtLeast(0.0)
                        } else 0.0
                        val exported = if (first != null && last != null) {
                            ((last.exportT1Kwh + last.exportT2Kwh) - (first.exportT1Kwh + first.exportT2Kwh)).coerceAtLeast(0.0)
                        } else 0.0
                        summaries.add(EnergyPeriodEntryDto(
                            label = dateStr.substring(5),
                            consumedKwh = consumed,
                            exportedKwh = exported
                        ))
                        totalConsumed += consumed
                        totalExported += exported
                    }
                    call.respond(EnergyPeriodResponseDto(summaries, totalConsumed, totalExported))
                }

                get("/api/v1/energy/monthly") {
                    val cal = java.util.Calendar.getInstance()
                    val currentMonth = cal.get(java.util.Calendar.MONTH)
                    val currentYear = cal.get(java.util.Calendar.YEAR)
                    val entries = mutableListOf<EnergyPeriodEntryDto>()
                    var totalConsumed = 0.0
                    var totalExported = 0.0
                    val daysInMonth = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
                    for (day in 1..daysInMonth) {
                        val dateStr = String.format("%04d-%02d-%02d", currentYear, currentMonth + 1, day)
                        val (sMs, eMs) = dayRangeMillis(dateStr)
                        val first = p1RawDao.getFirstInRange(sMs, eMs)
                        val last = p1RawDao.getLastInRange(sMs, eMs)
                        val consumed = if (first != null && last != null) {
                            ((last.importT1Kwh + last.importT2Kwh) - (first.importT1Kwh + first.importT2Kwh)).coerceAtLeast(0.0)
                        } else 0.0
                        val exported = if (first != null && last != null) {
                            ((last.exportT1Kwh + last.exportT2Kwh) - (first.exportT1Kwh + first.exportT2Kwh)).coerceAtLeast(0.0)
                        } else 0.0
                        entries.add(EnergyPeriodEntryDto(
                            label = day.toString(),
                            consumedKwh = consumed,
                            exportedKwh = exported
                        ))
                        totalConsumed += consumed
                        totalExported += exported
                    }
                    call.respond(EnergyPeriodResponseDto(entries, totalConsumed, totalExported))
                }

                get("/api/v1/energy/yearly") {
                    val cal = java.util.Calendar.getInstance()
                    val currentYear = cal.get(java.util.Calendar.YEAR)
                    val entries = mutableListOf<EnergyPeriodEntryDto>()
                    var totalConsumed = 0.0
                    var totalExported = 0.0
                    for (month in 1..12) {
                        val startDate = String.format("%04d-%02d-01", currentYear, month)
                        val cal2 = java.util.Calendar.getInstance()
                        cal2.set(currentYear, month - 1, 1, 0, 0, 0)
                        cal2.set(java.util.Calendar.MILLISECOND, 0)
                        val startMs = cal2.timeInMillis
                        cal2.add(java.util.Calendar.MONTH, 1)
                        val endMs = cal2.timeInMillis
                        val first = p1RawDao.getFirstInRange(startMs, endMs)
                        val last = p1RawDao.getLastInRange(startMs, endMs)
                        val consumed = if (first != null && last != null) {
                            ((last.importT1Kwh + last.importT2Kwh) - (first.importT1Kwh + first.importT2Kwh)).coerceAtLeast(0.0)
                        } else 0.0
                        val exported = if (first != null && last != null) {
                            ((last.exportT1Kwh + last.exportT2Kwh) - (first.exportT1Kwh + first.exportT2Kwh)).coerceAtLeast(0.0)
                        } else 0.0
                        val monthLabel = java.text.SimpleDateFormat("MMM", java.util.Locale.US).format(
                            java.util.Date(currentYear - 1900, month - 1, 1)
                        )
                        entries.add(EnergyPeriodEntryDto(
                            label = monthLabel,
                            consumedKwh = consumed,
                            exportedKwh = exported
                        ))
                        totalConsumed += consumed
                        totalExported += exported
                    }
                    call.respond(EnergyPeriodResponseDto(entries, totalConsumed, totalExported))
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(GRACE_PERIOD_MS, TIMEOUT_MS)
        engine = null
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
        const val DEFAULT_PORT = 8080
        private const val GRACE_PERIOD_MS = 1_000L
        private const val TIMEOUT_MS = 2_000L
    }
}
