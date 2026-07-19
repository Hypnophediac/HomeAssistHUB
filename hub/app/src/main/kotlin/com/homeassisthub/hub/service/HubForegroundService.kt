package com.homeassisthub.hub.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.homeassisthub.hub.HubApplication
import com.homeassisthub.hub.MainActivity
import com.homeassisthub.hub.R
import com.homeassisthub.hub.bridge.CommandRouter
import com.homeassisthub.hub.bridge.HubSocketClient
import com.homeassisthub.hub.controller.DeviceControllerFactory
import com.homeassisthub.hub.controller.P1MeterController
import com.homeassisthub.hub.data.HubConfigStore
import com.homeassisthub.hub.data.db.AppDatabase
import com.homeassisthub.hub.security.SecureCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
    private val p1Dao by lazy { AppDatabase.getInstance(applicationContext).p1Dao() }
    private val controllerFactory by lazy { DeviceControllerFactory(p1Dao, serviceScope) }
    private val commandRouter by lazy { CommandRouter(credentialStore, controllerFactory) }

    private var hubSocketClient: HubSocketClient? = null
    private val p1Pollers = mutableListOf<P1MeterController>()

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
                startP1MeterPollers()
                connectToRelay()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hubSocketClient?.disconnect()
        p1Pollers.forEach { it.stopPolling() }
        p1Pollers.clear()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    /** Starts a periodic (60s) poller for every stored P1 meter credential. */
    private fun startP1MeterPollers() {
        if (p1Pollers.isNotEmpty()) return // already started
        credentialStore.getAllCredentials()
            .filter { it.deviceType == DeviceControllerFactory.DEVICE_TYPE_P1_METER }
            .forEach { credential ->
                val controller = controllerFactory.create(credential) as? P1MeterController ?: return@forEach
                controller.startPolling()
                p1Pollers.add(controller)
            }
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
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.homeassisthub.hub.action.STOP"
        private const val WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 60L * 1000L // 10 hours, refreshed on restart

        fun startIntent(context: Context): Intent =
            Intent(context, HubForegroundService::class.java)

        fun stopIntent(context: Context): Intent =
            Intent(context, HubForegroundService::class.java).setAction(ACTION_STOP)
    }
}
