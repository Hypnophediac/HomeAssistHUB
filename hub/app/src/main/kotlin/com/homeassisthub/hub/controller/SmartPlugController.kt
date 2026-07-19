package com.homeassisthub.hub.controller

import com.homeassisthub.hub.security.DeviceCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * REST controller for the smart plug's local HTTP relay API
 * (e.g. GET http://[IP]/relay/0?turn=on|off).
 */
class SmartPlugController(
    private val credential: DeviceCredential,
    private val httpClient: OkHttpClient
) : DeviceController {

    override val deviceId: String = credential.deviceId

    override suspend fun executeCommand(action: String, params: Map<String, String>): CommandResult =
        withContext(Dispatchers.IO) {
            when (action) {
                "turn_on" -> setRelay(turnOn = true)
                "turn_off" -> setRelay(turnOn = false)
                "toggle" -> toggle()
                "status" -> getStatus()
                else -> CommandResult.Failure("Unsupported action '$action' for smart plug")
            }
        }

    private fun setRelay(turnOn: Boolean): CommandResult = runCatching {
        val url = "http://${credential.ipAddress}:${credential.port}/relay/0?turn=${if (turnOn) "on" else "off"}"
        val request = Request.Builder().url(url).get().build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} from smart plug")
        }
        turnOn
    }.fold(
        onSuccess = { isOn -> CommandResult.Success(mapOf("is_on" to isOn)) },
        onFailure = { throwable -> CommandResult.Failure(throwable.message ?: "Unknown smart plug error") }
    )

    private fun toggle(): CommandResult {
        val current = getStatus()
        val currentlyOn = (current as? CommandResult.Success)?.data?.get("is_on") as? Boolean
            ?: return CommandResult.Failure("Could not read current plug status before toggling")
        return setRelay(turnOn = !currentlyOn)
    }

    private fun getStatus(): CommandResult = runCatching {
        val url = "http://${credential.ipAddress}:${credential.port}/relay/0"
        val request = Request.Builder().url(url).get().build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} from smart plug")
            val body = response.body?.string().orEmpty()
            JSONObject(body).optBoolean("ison", false)
        }
    }.fold(
        onSuccess = { isOn -> CommandResult.Success(mapOf("is_on" to isOn)) },
        onFailure = { throwable -> CommandResult.Failure(throwable.message ?: "Unknown smart plug error") }
    )
}
