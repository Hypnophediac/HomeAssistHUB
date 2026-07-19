package com.homeassisthub.client.data

import android.content.Context

data class ClientConfig(
    val relayUrl: String,
    val homeId: String,
    val hubLocalBaseUrl: String
)

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
        return ClientConfig(relayUrl, homeId, hubLocalBaseUrl)
    }

    fun saveConfig(config: ClientConfig) {
        prefs.edit()
            .putString(KEY_RELAY_URL, config.relayUrl)
            .putString(KEY_HOME_ID, config.homeId)
            .putString(KEY_HUB_LOCAL_URL, config.hubLocalBaseUrl)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "client_config"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_HOME_ID = "home_id"
        private const val KEY_HUB_LOCAL_URL = "hub_local_base_url"
    }
}
