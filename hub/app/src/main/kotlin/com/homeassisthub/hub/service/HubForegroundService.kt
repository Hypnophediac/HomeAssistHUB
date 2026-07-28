package com.homeassisthub.hub.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.homeassisthub.hub.HubApplication
import com.homeassisthub.hub.MainActivity
import com.homeassisthub.hub.R
import com.homeassisthub.hub.api.HubApiServer
import com.homeassisthub.hub.bridge.CommandRouter
import com.homeassisthub.hub.bridge.HubSocketClient
import com.homeassisthub.hub.controller.DeviceControllerFactory
import com.homeassisthub.hub.controller.HuaweiCloudScraper
import com.homeassisthub.hub.controller.HuaweiInverterController
import com.homeassisthub.hub.controller.InverterHistoryDaoHolder
import com.homeassisthub.hub.controller.P1MeterController
import com.homeassisthub.hub.data.HubConfigStore
import com.homeassisthub.hub.data.db.AppDatabase
import com.homeassisthub.hub.data.db.P1DataEntity
import com.homeassisthub.hub.data.db.P1DailySummary
import com.homeassisthub.hub.data.db.InverterDailySummary
import com.homeassisthub.hub.controller.InverterLiveData
import com.homeassisthub.hub.sync.CloudSyncManager
import com.homeassisthub.hub.discovery.DiscoveryManager
import com.homeassisthub.hub.security.DeviceCredential
import com.homeassisthub.hub.security.SecureCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Long-running foreground service that keeps the hub alive through Doze
 * mode via a partial WakeLock. This service hosts the device controller
 * coroutines added in Phase 3 and the Socket.IO client added in Phase 4.
 *
 * The service's own [serviceScope] MUST be used for any coroutine work
 * started here so that everything is cancelled together in [onDestroy],
 * preventing leaks.
 */
class HubForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    private val credentialStore by lazy { SecureCredentialStore(applicationContext) }
    private val hubConfigStore by lazy { HubConfigStore(applicationContext) }
    private val database by lazy { AppDatabase.getInstance(applicationContext) }
    private val p1Dao by lazy { database.p1Dao() }
    private val p1RawDao by lazy { database.p1RawDao() }
    private val p1DailySummaryDao by lazy { database.p1DailySummaryDao() }
    private val inverterHistoryDao by lazy { database.inverterHistoryDao() }
    private val inverterDailySummaryDao by lazy { database.inverterDailySummaryDao() }
    private val controllerFactory by lazy { DeviceControllerFactory(p1Dao, p1RawDao, serviceScope, applicationContext) }
    private val discoveryManager by lazy { DiscoveryManager(applicationContext) }
    private val kioskScraper by lazy { HuaweiCloudScraper(serviceScope) }
    private val commandRouter by lazy { CommandRouter(credentialStore, controllerFactory, discoveryManager, p1Dao, p1RawDao, p1DailySummaryDao, inverterHistoryDao, hubConfigStore, kioskScraper, inverterDailySummaryDao) }
    private val apiServer by lazy { HubApiServer(discoveryManager, credentialStore, p1Dao, p1RawDao, p1DailySummaryDao) }
    private val cloudSyncManager by lazy { CloudSyncManager(hubConfigStore, p1RawDao, inverterHistoryDao, p1DailySummaryDao, inverterDailySummaryDao, serviceScope) }

    private var hubSocketClient: HubSocketClient? = null
    private val p1Pollers = mutableListOf<P1MeterController>()
    private val inverterPollers = mutableListOf<HuaweiInverterController>()
    private var mockP1Job: Job? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                apiServer.start()
                startP1MeterPollers()
                startInverterPollers()
                startKioskScraper()
                startDailySummaryWorker()
                startInverterDailyCacheWorker()
                startCloudSync()
                connectToRelay()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        apiServer.stop()
        hubSocketClient?.disconnect()
        p1Pollers.forEach { it.stopPolling() }
        p1Pollers.clear()
        inverterPollers.forEach { it.stopPolling() }
        inverterPollers.clear()
        kioskScraper.stopPolling()
        mockP1Job?.cancel()
        mockP1Job = null
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    /** Starts a periodic (10s) poller for every stored Huawei SUN2000 inverter
     *  credential. Unlike the P1 meter, there is no auto-provisioning — the
     *  user must add a `huawei_inverter` device via Settings with the
     *  inverter's Modbus TCP IP/port (default 502 or 6607). */
    private fun startInverterPollers() {
        if (inverterPollers.isNotEmpty()) return
        val inverterCredentials = credentialStore.getAllCredentials()
            .filter { it.deviceType == DeviceControllerFactory.DEVICE_TYPE_HUAWEI_INVERTER }
        inverterCredentials.forEach { credential ->
            val controller = controllerFactory.create(credential) as? HuaweiInverterController ?: return@forEach
            controller.startPolling()
            inverterPollers.add(controller)
        }
    }

    /** Starts the FusionSolar Kiosk scraper if a kiosk URL is configured. */
    private fun startKioskScraper() {
        InverterHistoryDaoHolder.dao = inverterHistoryDao
        val kioskUrl = hubConfigStore.getConfig()?.kioskUrl
        if (!kioskUrl.isNullOrBlank()) {
            Log.i(TAG, "Starting Kiosk scraper with configured URL")
            kioskScraper.startPolling(kioskUrl)
        } else {
            Log.i(TAG, "No kiosk URL configured, scraper not started")
        }
    }

    /** Starts a periodic (60s) poller for every stored P1 meter credential.
     *  If no P1 meter is configured, starts a mock data generator so the
     *  Client dashboard chart has something to display. */
    private fun startP1MeterPollers() {
        if (p1Pollers.isNotEmpty()) return // already started
        val p1Credentials = credentialStore.getAllCredentials()
            .filter { it.deviceType == DeviceControllerFactory.DEVICE_TYPE_P1_METER }

        if (p1Credentials.isEmpty()) {
            Log.i(TAG, "No P1 meter configured, auto-provisioning default 192.168.0.148:8989")
            credentialStore.saveCredential(
                DeviceCredential(
                    deviceId = "p1_meter",
                    deviceType = DeviceControllerFactory.DEVICE_TYPE_P1_METER,
                    ipAddress = "192.168.0.148",
                    port = 8989,
                    username = "",
                    password = ""
                )
            )
            val autoProvisioned = credentialStore.getAllCredentials()
                .filter { it.deviceType == DeviceControllerFactory.DEVICE_TYPE_P1_METER }
            if (autoProvisioned.isEmpty()) {
                Log.e(TAG, "Auto-provisioning failed, starting mock data generator")
                startMockP1Generator()
                return
            }
            autoProvisioned.forEach { credential ->
                val controller = controllerFactory.create(credential) as? P1MeterController ?: return@forEach
                controller.startPolling()
                p1Pollers.add(controller)
            }
            return
        }

        p1Credentials.forEach { credential ->
            val controller = controllerFactory.create(credential) as? P1MeterController ?: return@forEach
            controller.startPolling()
            p1Pollers.add(controller)
        }
    }

    /** Generates mock P1 readings every 60s with realistic-looking values,
     *  including import/export tariff counters and 3-phase voltages. */
    private fun startMockP1Generator() {
        mockP1Job?.cancel()
        mockP1Job = serviceScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val twoHoursAgo = now - (2 * 60 * 60 * 1000L)
            var seedTime = twoHoursAgo
            var seedPower = 1500.0
            var seedImportT1 = 5000.0
            var seedImportT2 = 3000.0
            var seedExportT1 = 2000.0
            var seedExportT2 = 1000.0
            while (seedTime < now && isActive) {
                seedPower = (seedPower + (Math.random() - 0.5) * 300).coerceIn(200.0, 3500.0)
                val voltage = 230.0 + (Math.random() - 0.5) * 5
                p1Dao.insert(P1DataEntity(
                    timestamp = seedTime,
                    powerW = seedPower,
                    voltageV = voltage,
                    powerImportW = if (seedPower > 0) seedPower else 0.0,
                    powerExportW = if (seedPower < 0) -seedPower else 0.0,
                    l1V = voltage,
                    l2V = voltage + (Math.random() - 0.5) * 2,
                    l3V = voltage + (Math.random() - 0.5) * 2,
                    l1A = (seedPower / 230.0),
                    l2A = (seedPower / 230.0) * 0.5,
                    l3A = (seedPower / 230.0) * 0.3,
                    powerImportL1W = if (seedPower > 0) seedPower * 0.5 else 0.0,
                    powerImportL2W = if (seedPower > 0) seedPower * 0.3 else 0.0,
                    powerImportL3W = if (seedPower > 0) seedPower * 0.2 else 0.0,
                    powerExportL1W = if (seedPower < 0) -seedPower * 0.5 else 0.0,
                    powerExportL2W = if (seedPower < 0) -seedPower * 0.3 else 0.0,
                    powerExportL3W = if (seedPower < 0) -seedPower * 0.2 else 0.0,
                    powerFactor = 0.95 + Math.random() * 0.05,
                    frequencyHz = 50.0 + (Math.random() - 0.5) * 0.1,
                    importT1Kwh = seedImportT1,
                    importT2Kwh = seedImportT2,
                    exportT1Kwh = seedExportT1,
                    exportT2Kwh = seedExportT2,
                    currentTariff = 1
                ))
                p1RawDao.insert(com.homeassisthub.hub.data.db.P1RawData(
                    timestamp = seedTime,
                    importT1Kwh = seedImportT1,
                    importT2Kwh = seedImportT2,
                    exportT1Kwh = seedExportT1,
                    exportT2Kwh = seedExportT2,
                    currentPowerW = seedPower,
                    powerImportW = if (seedPower > 0) seedPower else 0.0,
                    powerExportW = if (seedPower < 0) -seedPower else 0.0,
                    l1V = voltage,
                    l2V = voltage + (Math.random() - 0.5) * 2,
                    l3V = voltage + (Math.random() - 0.5) * 2,
                    l1A = (seedPower / 230.0),
                    l2A = (seedPower / 230.0) * 0.5,
                    l3A = (seedPower / 230.0) * 0.3,
                    powerImportL1W = if (seedPower > 0) seedPower * 0.5 else 0.0,
                    powerImportL2W = if (seedPower > 0) seedPower * 0.3 else 0.0,
                    powerImportL3W = if (seedPower > 0) seedPower * 0.2 else 0.0,
                    powerExportL1W = if (seedPower < 0) -seedPower * 0.5 else 0.0,
                    powerExportL2W = if (seedPower < 0) -seedPower * 0.3 else 0.0,
                    powerExportL3W = if (seedPower < 0) -seedPower * 0.2 else 0.0,
                    powerFactor = 0.95 + Math.random() * 0.05,
                    frequencyHz = 50.0 + (Math.random() - 0.5) * 0.1
                ))
                val powerDelta = seedPower / 60.0 / 1000.0
                if (seedPower > 0) {
                    seedImportT1 += powerDelta
                } else {
                    seedExportT1 += (-seedPower) / 60.0 / 1000.0
                }
                seedTime += 2 * 60 * 1000L
            }
            Log.i(TAG, "Mock P1: seeded ${((now - twoHoursAgo) / (2 * 60 * 1000)).toInt()} historical readings")

            var power = seedPower
            var importT1 = seedImportT1
            var importT2 = seedImportT2
            var exportT1 = seedExportT1
            var exportT2 = seedExportT2
            while (isActive) {
                delay(60_000L)
                power = (power + (Math.random() - 0.5) * 500).coerceIn(-2000.0, 4000.0)
                val voltage = 230.0 + (Math.random() - 0.5) * 5
                p1Dao.insert(P1DataEntity(
                    timestamp = System.currentTimeMillis(),
                    powerW = power,
                    voltageV = voltage,
                    powerImportW = if (power > 0) power else 0.0,
                    powerExportW = if (power < 0) -power else 0.0,
                    l1V = voltage,
                    l2V = voltage + (Math.random() - 0.5) * 2,
                    l3V = voltage + (Math.random() - 0.5) * 2,
                    l1A = (power / 230.0),
                    l2A = (power / 230.0) * 0.5,
                    l3A = (power / 230.0) * 0.3,
                    powerImportL1W = if (power > 0) power * 0.5 else 0.0,
                    powerImportL2W = if (power > 0) power * 0.3 else 0.0,
                    powerImportL3W = if (power > 0) power * 0.2 else 0.0,
                    powerExportL1W = if (power < 0) -power * 0.5 else 0.0,
                    powerExportL2W = if (power < 0) -power * 0.3 else 0.0,
                    powerExportL3W = if (power < 0) -power * 0.2 else 0.0,
                    powerFactor = 0.95 + Math.random() * 0.05,
                    frequencyHz = 50.0 + (Math.random() - 0.5) * 0.1,
                    importT1Kwh = importT1,
                    importT2Kwh = importT2,
                    exportT1Kwh = exportT1,
                    exportT2Kwh = exportT2,
                    currentTariff = 1
                ))
                val powerDelta = power / 60.0 / 1000.0
                if (power > 0) {
                    importT1 += powerDelta
                } else {
                    exportT1 += (-power) / 60.0 / 1000.0
                }
                p1RawDao.insert(com.homeassisthub.hub.data.db.P1RawData(
                    timestamp = System.currentTimeMillis(),
                    importT1Kwh = importT1,
                    importT2Kwh = importT2,
                    exportT1Kwh = exportT1,
                    exportT2Kwh = exportT2,
                    currentPowerW = power,
                    powerImportW = if (power > 0) power else 0.0,
                    powerExportW = if (power < 0) -power else 0.0,
                    l1V = voltage,
                    l2V = voltage + (Math.random() - 0.5) * 2,
                    l3V = voltage + (Math.random() - 0.5) * 2,
                    l1A = (power / 230.0),
                    l2A = (power / 230.0) * 0.5,
                    l3A = (power / 230.0) * 0.3,
                    powerImportL1W = if (power > 0) power * 0.5 else 0.0,
                    powerImportL2W = if (power > 0) power * 0.3 else 0.0,
                    powerImportL3W = if (power > 0) power * 0.2 else 0.0,
                    powerExportL1W = if (power < 0) -power * 0.5 else 0.0,
                    powerExportL2W = if (power < 0) -power * 0.3 else 0.0,
                    powerExportL3W = if (power < 0) -power * 0.2 else 0.0,
                    powerFactor = 0.95 + Math.random() * 0.05,
                    frequencyHz = 50.0 + (Math.random() - 0.5) * 0.1
                ))
                Log.d(TAG, "Mock P1: inserted reading powerW=$power voltageV=$voltage importT1=$importT1 exportT1=$exportT1")
            }
        }
    }

    /** Nightly worker: computes yesterday's daily summary at midnight and
     *  cleans up raw data older than 7 days. Also runs immediately on start
     *  to backfill any missing summaries. */
    private fun startDailySummaryWorker() {
        serviceScope.launch(Dispatchers.IO) {
            // Run immediately to backfill any missing summaries
            computeAndStoreDailySummary(yesterdayDate())
            computeAndStoreInverterDailySummary(yesterdayDate())
            cloudSyncManager.pushAllDailySummaries()
            cleanupOldRawData()

            while (isActive) {
                val now = System.currentTimeMillis()
                val nextMidnight = nextMidnightMillis(now)
                val waitMs = (nextMidnight - now).coerceAtLeast(1000L)
                delay(waitMs)
                // Compute yesterday's summary and clean up
                computeAndStoreDailySummary(yesterdayDate())
                computeAndStoreInverterDailySummary(yesterdayDate())
                cloudSyncManager.pushDailySummary(yesterdayDate())
                cleanupOldRawData()
            }
        }
    }

    /** Every 5 minutes, snapshots InverterLiveData.dailyEnergyKwh (if fresh)
     *  into HubConfigStore, tagged with today's date. This is the "last
     *  known value" the midnight rollover uses to self-heal if it couldn't
     *  run exactly at midnight (e.g. the Hub was restarting or briefly
     *  offline right around midnight). */
    private fun startInverterDailyCacheWorker() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (InverterLiveData.isFresh()) {
                    hubConfigStore.saveLastKnownInverterDaily(todayDate(), InverterLiveData.dailyEnergyKwh)
                }
                delay(5 * 60 * 1000L)
            }
        }
    }

    /** Finalizes yesterday's solar production total from the last cached
     *  Kiosk snapshot taken on that date. If the Hub was fully offline for
     *  the entire day, there's no cached snapshot to recover from and the
     *  day is left without a summary row (surfaced as "no data" to the UI,
     *  rather than a misleading 0 kWh). */
    private suspend fun computeAndStoreInverterDailySummary(dateStr: String) {
        try {
            if (inverterDailySummaryDao.getByDate(dateStr) != null) return // already finalized
            val (lastDate, lastKwh) = hubConfigStore.getLastKnownInverterDaily() ?: return
            if (lastDate != dateStr) {
                Log.w(TAG, "No cached inverter snapshot for $dateStr (last cached date was $lastDate) — leaving as no-data")
                return
            }
            inverterDailySummaryDao.upsert(InverterDailySummary(date = dateStr, producedKwh = lastKwh))
            Log.i(TAG, "Inverter daily summary for $dateStr: produced=${"%.3f".format(lastKwh)} kWh")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute inverter daily summary for $dateStr", e)
        }
    }

    private suspend fun computeAndStoreDailySummary(dateStr: String) {
        try {
            val (startMs, endMs) = dayRangeMillis(dateStr)
            val rawReadings = p1RawDao.getRange(startMs, endMs)
            if (rawReadings.isEmpty()) return
            val stats = com.homeassisthub.hub.data.db.DailyStatsCalculator.compute(rawReadings)

            p1DailySummaryDao.upsert(P1DailySummary(
                date = dateStr,
                totalConsumedKwh = stats.totalConsumedKwh,
                totalExportedKwh = stats.totalExportedKwh,
                importT1Kwh = stats.importT1Kwh,
                importT2Kwh = stats.importT2Kwh,
                exportT1Kwh = stats.exportT1Kwh,
                exportT2Kwh = stats.exportT2Kwh,
                minPowerW = stats.minPowerW,
                maxPowerW = stats.maxPowerW,
                avgPowerW = stats.avgPowerW,
                maxImportW = stats.maxImportW,
                maxExportW = stats.maxExportW,
                peakConsumptionHour = stats.peakConsumptionHour,
                peakExportHour = stats.peakExportHour,
                peakConsumptionKwh = stats.peakConsumptionKwh,
                peakExportKwh = stats.peakExportKwh,
                selfConsumptionRatio = stats.selfConsumptionRatio,
                netEnergyKwh = stats.netEnergyKwh,
                avgL1V = stats.avgL1V,
                avgL2V = stats.avgL2V,
                avgL3V = stats.avgL3V,
                avgL1A = stats.avgL1A,
                avgL2A = stats.avgL2A,
                avgL3A = stats.avgL3A,
                avgPowerFactor = stats.avgPowerFactor,
                avgFrequencyHz = stats.avgFrequencyHz
            ))
            Log.i(TAG, "Daily summary for $dateStr: consumed=${"%.3f".format(stats.totalConsumedKwh)} kWh, exported=${"%.3f".format(stats.totalExportedKwh)} kWh, avgPower=${"%.0f".format(stats.avgPowerW)} W, peakCons=${stats.peakConsumptionHour}h, peakExp=${stats.peakExportHour}h, selfCons=${"%.1f".format(stats.selfConsumptionRatio * 100)}%")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute daily summary for $dateStr", e)
        }
    }

    private suspend fun cleanupOldRawData() {
        val cutoff = System.currentTimeMillis() - (7L * 24L * 60L * 60L * 1000L)
        try {
            p1RawDao.deleteOlderThan(cutoff)
            p1Dao.deleteOlderThan(cutoff)
            Log.d(TAG, "Cleaned up raw data older than 7 days (cutoff=$cutoff)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean up old raw data", e)
        }
    }

    private fun yesterdayDate(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        return sdf.format(cal.time)
    }

    private fun todayDate(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return sdf.format(java.util.Date())
    }

    private fun dateToString(year: Int, month: Int, day: Int): String {
        return String.format("%04d-%02d-%02d", year, month, day)
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

    private fun nextMidnightMillis(now: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Starts the CloudSyncManager which periodically uploads raw readings
     *  and daily summaries to the Render relay's MongoDB backend. */
    private fun startCloudSync() {
        cloudSyncManager.start()
    }

    /** Connects the Socket.IO client to the cloud relay, if configured. */
    private fun connectToRelay() {
        if (hubSocketClient != null) return // already connected
        val config = hubConfigStore.getConfig() ?: return
        hubSocketClient = HubSocketClient(
            relayUrl = config.relayUrl,
            homeId = config.homeId,
            commandRouter = commandRouter,
            scope = serviceScope
        ).also { it.connect() }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:HubForegroundServiceWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, HubForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, HubApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.stop_service), stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "HubForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.homeassisthub.hub.action.STOP"
        private const val WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 60L * 1000L // 10 hours, refreshed on restart

        fun startIntent(context: Context): Intent =
            Intent(context, HubForegroundService::class.java)

        fun stopIntent(context: Context): Intent =
            Intent(context, HubForegroundService::class.java).setAction(ACTION_STOP)
    }
}
