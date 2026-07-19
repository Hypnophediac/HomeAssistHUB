package com.homeassisthub.hub.bridge

import com.homeassisthub.hub.controller.CommandResult
import com.homeassisthub.hub.controller.DeviceController
import com.homeassisthub.hub.controller.DeviceControllerFactory
import com.homeassisthub.hub.security.SecureCredentialStore
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Translates incoming `command_request` Socket.IO payloads into calls on
 * the appropriate [DeviceController], and builds the `command_response`
 * payload to send back through the relay.
 *
 * Expected request payload: {homeId, requestId, deviceId, action, params?}
 * Response payload: {homeId, requestId, success, data?, error?}
 */
class CommandRouter(
    private val credentialStore: SecureCredentialStore,
    private val controllerFactory: DeviceControllerFactory
) {

    private val controllerCache = ConcurrentHashMap<String, DeviceController>()

    suspend fun handle(request: JSONObject): JSONObject {
        val homeId = request.optString("homeId")
        val requestId = request.optString("requestId")
        val deviceId = request.optString("deviceId")
        val action = request.optString("action")
        val params = request.optJSONObject("params").toStringMap()

        val outcome = runCatching {
            val controller = controllerCache.getOrPut(deviceId) {
                val credential = credentialStore.getCredential(deviceId)
                    ?: error("No stored credential for device '$deviceId'")
                controllerFactory.create(credential)
                    ?: error("Unsupported device type for '$deviceId'")
            }
            controller.executeCommand(action, params)
        }

        return buildResponse(homeId, requestId, outcome)
    }

    private fun buildResponse(homeId: String, requestId: String, outcome: Result<CommandResult>): JSONObject {
        val response = JSONObject()
            .put("homeId", homeId)
            .put("requestId", requestId)

        outcome.fold(
            onSuccess = { result ->
                when (result) {
                    is CommandResult.Success -> response
                        .put("success", true)
                        .put("data", JSONObject(result.data))
                    is CommandResult.Failure -> response
                        .put("success", false)
                        .put("error", result.error)
                }
            },
            onFailure = { throwable -> response.put("success", false).put("error", throwable.message ?: "Unknown error") }
        )

        return response
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return keys().asSequence().associateWith { key -> optString(key) }
    }
}
