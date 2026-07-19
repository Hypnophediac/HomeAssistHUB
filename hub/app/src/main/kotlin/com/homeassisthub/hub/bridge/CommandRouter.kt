package com.homeassisthub.hub.bridge

import com.homeassisthub.hub.controller.CommandResult
import com.homeassisthub.hub.controller.DeviceController
import com.homeassisthub.hub.controller.DeviceControllerFactory
import com.homeassisthub.hub.data.db.P1Dao
import com.homeassisthub.hub.discovery.DiscoveryManager
import com.homeassisthub.hub.security.DeviceCredential
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
    private val controllerFactory: DeviceControllerFactory,
    private val discoveryManager: DiscoveryManager,
    private val p1Dao: P1Dao
) {

    private val controllerCache = ConcurrentHashMap<String, DeviceController>()

    suspend fun handle(request: JSONObject): JSONObject {
        val homeId = request.optString("homeId")
        val requestId = request.optString("requestId")
        val deviceId = request.optString("deviceId")
        val action = request.optString("action")
        val params = request.optJSONObject("params").toStringMap()

        val outcome = runCatching {
            if (deviceId == HUB_PSEUDO_DEVICE_ID) {
                handleHubAction(action, params)
            } else {
                val controller = controllerCache.getOrPut(deviceId) {
                    val credential = credentialStore.getCredential(deviceId)
                        ?: error("No stored credential for device '$deviceId'")
                    controllerFactory.create(credential)
                        ?: error("Unsupported device type for '$deviceId'")
                }
                controller.executeCommand(action, params)
            }
        }

        return buildResponse(homeId, requestId, outcome)
    }

    /**
     * Administrative actions targeting the Hub itself (not a physical
     * device), routed through the relay so the Client app can manage
     * devices and read P1 history remotely (mobile data), not just on LAN.
     */
    private suspend fun handleHubAction(action: String, params: Map<String, String>): CommandResult = when (action) {
        "list_devices" -> CommandResult.Success(
            mapOf("devices" to credentialStore.getAllCredentials().map { it.toSummaryMap() })
        )
        "discover_devices" -> {
            val timeoutMs = params["timeoutMs"]?.toLongOrNull() ?: 3000L
            val devices = discoveryManager.discoverAll(timeoutMs)
            CommandResult.Success(
                mapOf(
                    "devices" to devices.map {
                        mapOf("name" to it.name, "ipAddress" to it.ipAddress, "port" to it.port, "source" to it.source.name)
                    }
                )
            )
        }
        "save_credential" -> {
            val credential = DeviceCredential(
                deviceId = params["deviceId"] ?: error("Missing deviceId"),
                deviceType = params["deviceType"] ?: error("Missing deviceType"),
                ipAddress = params["ipAddress"] ?: error("Missing ipAddress"),
                port = params["port"]?.toIntOrNull() ?: 80,
                username = params["username"] ?: "",
                password = params["password"] ?: ""
            )
            credentialStore.saveCredential(credential)
            controllerCache.remove(credential.deviceId)
            CommandResult.Success(mapOf("saved" to credential.toSummaryMap()))
        }
        "delete_credential" -> {
            val targetDeviceId = params["deviceId"] ?: error("Missing deviceId")
            credentialStore.removeCredential(targetDeviceId)
            controllerCache.remove(targetDeviceId)
            CommandResult.Success(mapOf("deleted" to targetDeviceId))
        }
        "get_p1_history" -> {
            val limit = params["limit"]?.toIntOrNull() ?: 100
            val readings = p1Dao.getRecent(limit)
            CommandResult.Success(
                mapOf(
                    "readings" to readings.map {
                        mapOf("timestamp" to it.timestamp, "power_w" to it.powerW, "voltage_v" to it.voltageV)
                    }
                )
            )
        }
        else -> CommandResult.Failure("Unsupported hub action '$action'")
    }

    private fun DeviceCredential.toSummaryMap(): Map<String, Any?> = mapOf(
        "deviceId" to deviceId,
        "deviceType" to deviceType,
        "ipAddress" to ipAddress,
        "port" to port,
        "username" to username
    )

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

    companion object {
        private const val HUB_PSEUDO_DEVICE_ID = "hub"
    }
}
