package com.homeassisthub.client.network

import android.util.Log
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
    private val fallbackResponses = ConcurrentHashMap<String, JSONObject>()
    private var connectLatch = CompletableDeferred<Boolean>()
    private var registerLatch = CompletableDeferred<Boolean>()
    private var onPeerJoined: ((String) -> Unit)? = null
    private var onCameraFrame: ((deviceId: String, base64: String) -> Unit)? = null

    fun setOnPeerJoined(callback: (String) -> Unit) {
        onPeerJoined = callback
    }

    fun setOnCameraFrame(callback: (deviceId: String, base64: String) -> Unit) {
        onCameraFrame = callback
    }

    fun connect() {
        if (socket != null) return

        val options = IO.Options().apply {
            reconnection = true
            reconnectionDelay = 2_000
            reconnectionDelayMax = 15_000
            timeout = 10_000
            // Do NOT restrict to "websocket" only — many mobile carrier
            // networks/NAT proxies block or break the WebSocket upgrade
            // handshake over cellular data, while HTTP long-polling works
            // everywhere. Leaving transports unset lets Socket.IO start
            // with polling and upgrade to websocket only if it succeeds,
            // so the app also works on mobile data, not just WiFi.
        }

        val sock = IO.socket(URI.create(relayUrl), options)
        socket = sock

        sock.on(Socket.EVENT_CONNECT) {
            Log.i(TAG, "Socket connected, registering as client for home=$homeId")
            val registration = JSONObject().put("homeId", homeId).put("role", "client")
            sock.emit("register", registration)
            connectLatch.complete(true)
        }

        sock.on(Socket.EVENT_CONNECT_ERROR) { args ->
            Log.e(TAG, "Socket connect error: ${args.getOrNull(0)}")
            // Don't completeExceptionally — let auto-reconnection retry.
            // Reset latches so sendCommand waits for the next successful connect.
            connectLatch = CompletableDeferred()
            registerLatch = CompletableDeferred()
        }

        sock.on("reconnect") {
            Log.i(TAG, "Socket reconnected, re-registering as client for home=$homeId")
            val registration = JSONObject().put("homeId", homeId).put("role", "client")
            sock.emit("register", registration)
            connectLatch.complete(true)
        }

        sock.on("reconnect_attempt") { args ->
            Log.i(TAG, "Socket reconnect attempt: ${args.getOrNull(0)}")
        }

        sock.on(Socket.EVENT_DISCONNECT) { args ->
            Log.w(TAG, "Socket disconnected: ${args.getOrNull(0)}")
            connectLatch = CompletableDeferred()
            registerLatch = CompletableDeferred()
        }

        sock.on("registered") { args ->
            Log.i(TAG, "Registered with relay: ${args.getOrNull(0)}")
            registerLatch.complete(true)
        }

        sock.on("peer_joined") { args ->
            val payload = args.getOrNull(0) as? JSONObject ?: return@on
            val role = payload.optString("role")
            Log.i(TAG, "Peer joined: role=$role")
            onPeerJoined?.invoke(role)
        }

        sock.on("error_message") { args ->
            Log.e(TAG, "Relay error: ${args.getOrNull(0)}")
        }

        sock.on("command_response") { args ->
            val payload = args.getOrNull(0) as? JSONObject ?: return@on
            val requestId = payload.optString("requestId")
            val success = payload.optBoolean("success")
            Log.d(TAG, "Received command_response for requestId=$requestId success=$success")
            if (success) {
                pendingRequests.remove(requestId)?.complete(payload)
            } else if (pendingRequests.containsKey(requestId)) {
                fallbackResponses[requestId] = payload
            }
        }

        sock.on("camera_frame") { args ->
            val payload = args.getOrNull(0) as? JSONObject ?: return@on
            val deviceId = payload.optString("deviceId")
            val frame = payload.optString("frame")
            if (deviceId.isNotEmpty() && frame.isNotEmpty()) {
                onCameraFrame?.invoke(deviceId, frame)
            }
        }

        Log.i(TAG, "Connecting to relay at $relayUrl ...")
        sock.connect()
    }

    suspend fun sendCommand(
        deviceId: String,
        action: String,
        params: Map<String, String> = emptyMap(),
        timeoutMs: Long = 15_000L
    ): JSONObject {
        val sock = socket ?: return errorResponse("Socket not initialised")

        // Wait for the socket to connect AND register with the relay before emitting.
        // Use withTimeoutOrNull so reconnection failures don't throw — just return error.
        val connected = withTimeoutOrNull(15_000L) {
            try { connectLatch.await() } catch (_: Exception) { null }
        }
        if (connected == null) {
            Log.e(TAG, "Timed out waiting for socket connection before sendCommand")
            return errorResponse("Socket connection timeout")
        }
        val registered = withTimeoutOrNull(15_000L) {
            try { registerLatch.await() } catch (_: Exception) { null }
        }
        if (registered == null) {
            Log.e(TAG, "Timed out waiting for relay registration before sendCommand")
            return errorResponse("Relay registration timeout")
        }

        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[requestId] = deferred

        val payload = JSONObject()
            .put("homeId", homeId)
            .put("requestId", requestId)
            .put("deviceId", deviceId)
            .put("action", action)
            .put("params", JSONObject(params))

        Log.d(TAG, "Sending command_request: deviceId=$deviceId action=$action requestId=$requestId")
        sock.emit("command_request", payload)

        val response = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (response == null) {
            pendingRequests.remove(requestId)
            val fallback = fallbackResponses.remove(requestId)
            if (fallback != null) {
                Log.w(TAG, "Timed out waiting for success response, using fallback for requestId=$requestId")
                return fallback
            }
            Log.e(TAG, "Timed out waiting for command_response requestId=$requestId")
            return errorResponse("Timeout waiting for command_response")
        }
        return response
    }

    fun disconnect() {
        socket?.off()
        socket?.disconnect()
        socket = null
        pendingRequests.clear()
        fallbackResponses.clear()
        connectLatch = CompletableDeferred()
        registerLatch = CompletableDeferred()
    }

    fun isConnected(): Boolean = socket?.connected() ?: false

    private fun errorResponse(message: String) = JSONObject().put("success", false).put("error", message)

    companion object {
        private const val TAG = "SocketIoManager"
    }
}
