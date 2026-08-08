package com.homeassisthub.client.data

import android.content.Context

data class ClientConfig(
    val relayUrl: String,
    val homeId: String,
    val hubLocalBaseUrl: String,
    val syncToken: String = ""
)

data class PvForecastConfig(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val pvCapacityKwp: Double? = null,
    val performanceRatio: Double = 0.80,
    val locationName: String = ""
) {
    val isConfigured: Boolean get() = latitude != null && longitude != null && pvCapacityKwp != null && pvCapacityKwp > 0.0
}

/**
 * Persists the relay connection details (used by the Socket.IO bridge)
 * and the Hub's local LAN API base URL (used by Retrofit for discovery
 * and credential management while on the same network).
 */
class ClientConfigStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getConfig(): ClientConfig? {
        val relayUrl = prefs.getString(KEY_RELAY_URL, null) ?: return null
        val homeId = prefs.getString(KEY_HOME_ID, null) ?: return null
        val hubLocalBaseUrl = prefs.getString(KEY_HUB_LOCAL_URL, null) ?: return null
        val syncToken = prefs.getString(KEY_SYNC_TOKEN, "") ?: ""
        return ClientConfig(relayUrl, homeId, hubLocalBaseUrl, syncToken)
    }

    fun saveConfig(config: ClientConfig) {
        prefs.edit()
            .putString(KEY_RELAY_URL, config.relayUrl)
            .putString(KEY_HOME_ID, config.homeId)
            .putString(KEY_HUB_LOCAL_URL, config.hubLocalBaseUrl)
            .putString(KEY_SYNC_TOKEN, config.syncToken)
            .apply()
    }

    fun getPvForecastConfig(): PvForecastConfig {
        val lat = if (prefs.contains(KEY_LATITUDE)) prefs.getFloat(KEY_LATITUDE, 0f).toDouble() else null
        val lon = if (prefs.contains(KEY_LONGITUDE)) prefs.getFloat(KEY_LONGITUDE, 0f).toDouble() else null
        val kwp = if (prefs.contains(KEY_PV_CAPACITY)) prefs.getFloat(KEY_PV_CAPACITY, 0f).toDouble() else null
        val ratio = prefs.getFloat(KEY_PERFORMANCE_RATIO, 0.80f).toDouble()
        val locName = prefs.getString(KEY_LOCATION_NAME, "") ?: ""
        return PvForecastConfig(lat, lon, kwp, ratio, locName)
    }

    fun savePvForecastConfig(config: PvForecastConfig) {
        prefs.edit().apply {
            if (config.latitude != null) putFloat(KEY_LATITUDE, config.latitude.toFloat()) else remove(KEY_LATITUDE)
            if (config.longitude != null) putFloat(KEY_LONGITUDE, config.longitude.toFloat()) else remove(KEY_LONGITUDE)
            if (config.pvCapacityKwp != null) putFloat(KEY_PV_CAPACITY, config.pvCapacityKwp.toFloat()) else remove(KEY_PV_CAPACITY)
            putFloat(KEY_PERFORMANCE_RATIO, config.performanceRatio.toFloat())
            putString(KEY_LOCATION_NAME, config.locationName)
        }.apply()
    }

    companion object {
        private const val PREFS_NAME = "client_config"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_HOME_ID = "home_id"
        private const val KEY_HUB_LOCAL_URL = "hub_local_base_url"
        private const val KEY_SYNC_TOKEN = "sync_token"
        private const val KEY_LATITUDE = "pv_latitude"
        private const val KEY_LONGITUDE = "pv_longitude"
        private const val KEY_PV_CAPACITY = "pv_capacity_kwp"
        private const val KEY_PERFORMANCE_RATIO = "pv_performance_ratio"
        private const val KEY_LOCATION_NAME = "pv_location_name"
    }
}
