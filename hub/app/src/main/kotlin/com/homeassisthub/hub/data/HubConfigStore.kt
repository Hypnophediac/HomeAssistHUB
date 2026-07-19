package com.homeassisthub.hub.data

import android.content.Context

data class HubConfig(val relayUrl: String, val homeId: String)

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
        return HubConfig(relayUrl, homeId)
    }

    fun saveConfig(config: HubConfig) {
        prefs.edit()
            .putString(KEY_RELAY_URL, config.relayUrl)
            .putString(KEY_HOME_ID, config.homeId)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "hub_config"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_HOME_ID = "home_id"
    }
}
