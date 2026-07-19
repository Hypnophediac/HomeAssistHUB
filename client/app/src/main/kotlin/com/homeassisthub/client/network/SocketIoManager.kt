package com.homeassisthub.client.network

import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Socket.IO client connecting the Client app to the Koyeb/Render cloud
 * relay. Registers into the `homeId` room as role "client", and turns
 * fire-and-forget `command_request`/`command_response` events into a
 * simple suspend request/response API keyed by `requestId`.
 */
class SocketIoManager(
    private val relayUrl: String,
    private val homeId: String
) {

    private var socket: Socket? = null
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    fun connect() {
        if (socket != null) return

        val options = IO.Options().apply {
            reconnection = true
            reconnectionDelay = 2_000
            reconnectionDelayMax = 15_000
            timeout = 10_000
        }

        val sock = IO.socket(URI.create(relayUrl), options)
        socket = sock

        sock.on(Socket.EVENT_CONNECT) {
            val registration = JSONObject().put("homeId", homeId).put("role", "client")
            sock.emit("register", registration)
        }

        sock.on("command_response") { args ->
            val payload = args.getOrNull(0) as? JSONObject ?: return@on
            val requestId = payload.optString("requestId")
            pendingRequests.remove(requestId)?.complete(payload)
        }

        sock.connect()
    }

    suspend fun sendCommand(
        deviceId: String,
        action: String,
        params: Map<String, String> = emptyMap(),
        timeoutMs: Long = 8_000L
    ): JSONObject {
        val sock = socket ?: return errorResponse("Socket not connected")
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[requestId] = deferred

        val payload = JSONObject()
            .put("homeId", homeId)
            .put("requestId", requestId)
            .put("deviceId", deviceId)
            .put("action", action)
            .put("params", JSONObject(params))

        sock.emit("command_request", payload)

        val response = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (response == null) {
            pendingRequests.remove(requestId)
            return errorResponse("Timeout waiting for command_response")
        }
        return response
    }

    fun disconnect() {
        socket?.off()
        socket?.disconnect()
        socket = null
        pendingRequests.clear()
    }

    fun isConnected(): Boolean = socket?.connected() ?: false

    private fun errorResponse(message: String) = JSONObject().put("success", false).put("error", message)
}
