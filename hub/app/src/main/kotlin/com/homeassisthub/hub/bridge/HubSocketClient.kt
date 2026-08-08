package com.homeassisthub.hub.bridge

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Socket.IO client connecting the Hub to the Render cloud relay.
 * Registers into the `homeId` room as role "hub", listens for
 * `command_request` events and replies with `command_response` via the
 * [CommandRouter]. Reconnection is handled by the underlying socket.io
 * engine (exponential backoff, configured below).
 *
 * Additionally sends a keepalive ping every 10 minutes to prevent the
 * Render free tier from spinning down due to inactivity.
 */
class HubSocketClient(
    private val relayUrl: String,
    private val homeId: String,
    private val commandRouter: CommandRouter,
    private val scope: CoroutineScope
) {

    private var socket: Socket? = null
    private var keepaliveJob: Job? = null
    private var reconnectJob: Job? = null

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
            // with polling and upgrade to websocket only if it succeeds.
        }

        val sock = IO.socket(java.net.URI.create(relayUrl), options)
        socket = sock

        sock.on(Socket.EVENT_CONNECT) {
            Log.i(TAG, "Hub socket connected, registering as hub for home=$homeId")
            val registration = JSONObject().put("homeId", homeId).put("role", "hub")
            sock.emit("register", registration)
            startKeepalive()
        }

        sock.on(Socket.EVENT_CONNECT_ERROR) { args ->
            Log.e(TAG, "Hub socket connect error: ${args.getOrNull(0)}")
        }

        sock.on(Socket.EVENT_DISCONNECT) { args ->
            Log.w(TAG, "Hub socket disconnected: ${args.getOrNull(0)}")
            stopKeepalive()
            forceReconnect()
        }

        sock.on("reconnect") { args ->
            Log.i(TAG, "Hub socket reconnected: ${args.getOrNull(0)}")
        }

        sock.on("reconnect_attempt") { args ->
            Log.i(TAG, "Hub socket reconnect attempt: ${args.getOrNull(0)}")
        }

        sock.on("registered") { args ->
            Log.i(TAG, "Hub registered with relay: ${args.getOrNull(0)}")
        }

        sock.on("error_message") { args ->
            Log.e(TAG, "Hub relay error: ${args.getOrNull(0)}")
        }

        sock.on("command_request") { args ->
            val payload = args.getOrNull(0) as? JSONObject ?: return@on
            Log.d(TAG, "Received command_request: deviceId=${payload.optString("deviceId")} action=${payload.optString("action")} requestId=${payload.optString("requestId")}")
            scope.launch(Dispatchers.IO) {
                val response = commandRouter.handle(payload)
                Log.d(TAG, "Sending command_response: requestId=${response.optString("requestId")} success=${response.optBoolean("success")}")
                sock.emit("command_response", response)
            }
        }

        Log.i(TAG, "Hub connecting to relay at $relayUrl ...")

        // Wire up frame emitter for camera streaming
        commandRouter.frameEmitter = { deviceId, base64Jpeg ->
            val framePayload = JSONObject()
                .put("homeId", homeId)
                .put("deviceId", deviceId)
                .put("frame", base64Jpeg)
                .put("timestamp", System.currentTimeMillis())
            sock.emit("camera_frame", framePayload)
        }

        sock.connect()
    }

    /**
     * Sends a keepalive ping every 10 minutes to prevent Render free tier spin-down.
     */
    private fun startKeepalive() {
        stopKeepalive()
        keepaliveJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(10 * 60 * 1000L) // 10 minutes
                val sock = socket ?: break
                if (sock.connected()) {
                    val ping = JSONObject().put("homeId", homeId).put("timestamp", System.currentTimeMillis())
                    sock.emit("keepalive", ping)
                    Log.d(TAG, "Keepalive ping sent")
                }
            }
        }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    /**
     * Force reconnect after 5 seconds if the socket.io engine doesn't recover on its own.
     * This handles Render spin-down scenarios where the server goes away and comes back.
     */
    private fun forceReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(5_000L)
            val sock = socket ?: return@launch
            if (!sock.connected()) {
                Log.w(TAG, "Socket still disconnected after 5s, forcing reconnect...")
                sock.disconnect()
                delay(1_000L)
                sock.connect()
            }
        }
    }

    fun disconnect() {
        stopKeepalive()
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.let {
            it.off()
            it.disconnect()
        }
        socket = null
    }

    fun isConnected(): Boolean = socket?.connected() ?: false

    companion object {
        private const val TAG = "HubSocketClient"
    }
}
