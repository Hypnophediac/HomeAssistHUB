package com.homeassisthub.hub.data

import android.content.Context

data class HubConfig(val relayUrl: String, val homeId: String, val kioskUrl: String = "", val syncToken: String = "")

/**
 * Non-secret hub configuration (which relay to connect to, which home
 * this hub belongs to). Device credentials live separately in
 * [com.homeassisthub.hub.security.SecureCredentialStore].
 */
class HubConfigStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getConfig(): HubConfig? {
        val relayUrl = prefs.getString(KEY_RELAY_URL, null) ?: return null
        val homeId = prefs.getString(KEY_HOME_ID, null) ?: return null
        val kioskUrl = prefs.getString(KEY_KIOSK_URL, "") ?: ""
        val syncToken = prefs.getString(KEY_SYNC_TOKEN, "") ?: ""
        return HubConfig(relayUrl, homeId, kioskUrl, syncToken)
    }

    fun saveConfig(config: HubConfig) {
        prefs.edit()
            .putString(KEY_RELAY_URL, config.relayUrl)
            .putString(KEY_HOME_ID, config.homeId)
            .putString(KEY_KIOSK_URL, config.kioskUrl)
            .putString(KEY_SYNC_TOKEN, config.syncToken)
            .apply()
    }

    /** Persists the most recent (date, dailyEnergyKwh) snapshot from the Kiosk
     *  scraper, surviving service restarts. Used by the midnight rollover
     *  worker to self-heal if it missed the exact midnight tick (e.g. the
     *  Hub was restarting or briefly offline). Only one day is kept: this
     *  can only recover the day that most recently ended, not older gaps. */
    fun saveLastKnownInverterDaily(date: String, kwh: Double) {
        prefs.edit()
            .putString(KEY_LAST_INVERTER_DATE, date)
            .putFloat(KEY_LAST_INVERTER_KWH, kwh.toFloat())
            .apply()
    }

    fun getLastKnownInverterDaily(): Pair<String, Double>? {
        val date = prefs.getString(KEY_LAST_INVERTER_DATE, null) ?: return null
        val kwh = prefs.getFloat(KEY_LAST_INVERTER_KWH, 0f).toDouble()
        return date to kwh
    }

    /** Cloud sync cursor: the timestamp of the last successfully synced
     *  P1 raw reading. The CloudSyncManager reads all rows newer than this
     *  and only advances the cursor after a successful HTTP POST. */
    fun getSyncCursor(): Long {
        return prefs.getLong(KEY_SYNC_CURSOR, 0L)
    }

    fun saveSyncCursor(timestamp: Long) {
        prefs.edit().putLong(KEY_SYNC_CURSOR, timestamp).apply()
    }

    /** Timestamp of the last successful cloud sync (for UI diagnostics). */
    fun getLastSyncTime(): Long {
        return prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
    }

    fun saveLastSyncTime(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, timestamp).apply()
    }

    /** Generates and saves a new random sync token (UUID without dashes). */
    fun generateSyncToken(): String {
        val token = java.util.UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(KEY_SYNC_TOKEN, token).apply()
        return token
    }

    companion object {
        private const val PREFS_NAME = "hub_config"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_HOME_ID = "home_id"
        private const val KEY_KIOSK_URL = "kiosk_url"
        private const val KEY_LAST_INVERTER_DATE = "last_inverter_date"
        private const val KEY_LAST_INVERTER_KWH = "last_inverter_kwh"
        private const val KEY_SYNC_TOKEN = "sync_token"
        private const val KEY_SYNC_CURSOR = "sync_cursor"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
    }
}
