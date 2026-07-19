package com.homeassisthub.hub.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * Stores device IP addresses, usernames and passwords using
 * EncryptedSharedPreferences (AES-256-GCM), backed by a Keystore-derived
 * MasterKey. Never hardcode credentials elsewhere in the codebase; all
 * device secrets must flow through this store.
 */
class SecureCredentialStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveCredential(credential: DeviceCredential) {
        val registry = readRegistry()
        registry.put(credential.deviceId, credential.toJson())
        prefs.edit().putString(KEY_REGISTRY, registry.toString()).apply()
    }

    fun getCredential(deviceId: String): DeviceCredential? {
        val registry = readRegistry()
        if (!registry.has(deviceId)) return null
        return runCatching { registry.getJSONObject(deviceId).toCredential(deviceId) }.getOrNull()
    }

    fun getAllCredentials(): List<DeviceCredential> {
        val registry = readRegistry()
        return registry.keys().asSequence().mapNotNull { deviceId ->
            runCatching { registry.getJSONObject(deviceId).toCredential(deviceId) }.getOrNull()
        }.toList()
    }

    fun removeCredential(deviceId: String) {
        val registry = readRegistry()
        registry.remove(deviceId)
        prefs.edit().putString(KEY_REGISTRY, registry.toString()).apply()
    }

    private fun readRegistry(): JSONObject {
        val raw = prefs.getString(KEY_REGISTRY, null) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun DeviceCredential.toJson(): JSONObject = JSONObject().apply {
        put("deviceType", deviceType)
        put("ipAddress", ipAddress)
        put("port", port)
        put("username", username)
        put("password", password)
    }

    private fun JSONObject.toCredential(deviceId: String): DeviceCredential = DeviceCredential(
        deviceId = deviceId,
        deviceType = getString("deviceType"),
        ipAddress = getString("ipAddress"),
        port = optInt("port", 80),
        username = optString("username", ""),
        password = optString("password", "")
    )

    companion object {
        private const val PREFS_FILE_NAME = "homeassist_hub_secure_prefs"
        private const val KEY_REGISTRY = "device_credential_registry"
    }
}
