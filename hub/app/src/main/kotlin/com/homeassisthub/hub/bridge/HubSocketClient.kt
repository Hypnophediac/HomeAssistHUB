package com.homeassisthub.hub.bridge

import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Socket.IO client connecting the Hub to the Koyeb/Render cloud relay.
 * Registers into the `homeId` room as role "hub", listens for
 * `command_request` events and replies with `command_response` via the
 * [CommandRouter]. Reconnection is handled by the underlying socket.io
 * engine (exponential backoff, configured below).
 */
class HubSocketClient(
    private val relayUrl: String,
    private val homeId: String,
    private val commandRouter: CommandRouter,
    private val scope: CoroutineScope
) {

    private var socket: Socket? = null

    fun connect() {
        if (socket != null) return

        val options = IO.Options().apply {
            reconnection = true
            reconnectionDelay = 2_000
            reconnectionDelayMax = 15_000
            timeout = 10_000
        }

        val sock = IO.socket(java.net.URI.create(relayUrl), options)
        socket = sock

        sock.on(Socket.EVENT_CONNECT) {
            val registration = JSONObject().put("homeId", homeId).put("role", "hub")
            sock.emit("register", registration)
        }

        sock.on("command_request") { args ->
            val payload = args.getOrNull(0) as? JSONObject ?: return@on
            scope.launch(Dispatchers.IO) {
                val response = commandRouter.handle(payload)
                sock.emit("command_response", response)
            }
        }

        sock.connect()
    }

    fun disconnect() {
        socket?.let {
            it.off()
            it.disconnect()
        }
        socket = null
    }

    fun isConnected(): Boolean = socket?.connected() ?: false
}
