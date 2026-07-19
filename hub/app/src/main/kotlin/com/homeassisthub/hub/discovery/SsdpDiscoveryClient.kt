package com.homeassisthub.hub.discovery

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Custom UDP Multicast SSDP client: sends an M-SEARCH probe to
 * 239.255.255.250:1900 and collects any HTTP-over-UDP responses for the
 * given [timeoutMs] window.
 */
class SsdpDiscoveryClient(private val context: Context) {

    suspend fun discover(timeoutMs: Long = 3000L): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val results = mutableListOf<DiscoveredDevice>()
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val multicastLock = wifiManager.createMulticastLock("homeassisthub-ssdp").apply {
            setReferenceCounted(true)
            acquire()
        }

        try {
            val socket = DatagramSocket().apply {
                soTimeout = SOCKET_POLL_TIMEOUT_MS
                reuseAddress = true
            }
            socket.use {
                val group = InetAddress.getByName(SSDP_ADDRESS)
                val searchBytes = SEARCH_REQUEST.toByteArray(Charsets.UTF_8)
                it.send(DatagramPacket(searchBytes, searchBytes.size, group, SSDP_PORT))

                val buffer = ByteArray(2048)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        it.receive(packet)
                        val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        parseSsdpResponse(response, packet.address?.hostAddress ?: "")?.let(results::add)
                    } catch (_: SocketTimeoutException) {
                        // No packet within this poll window; keep looping until the deadline.
                    }
                }
            }
        } finally {
            if (multicastLock.isHeld) multicastLock.release()
        }

        results.distinctBy { "${it.ipAddress}:${it.name}" }
    }

    private fun parseSsdpResponse(raw: String, sourceIp: String): DiscoveredDevice? {
        if (sourceIp.isBlank()) return null

        val headers = raw.lineSequence().mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null else line.substring(0, idx).trim().uppercase() to line.substring(idx + 1).trim()
        }.toMap()

        val location = headers["LOCATION"]
        val name = headers["SERVER"] ?: headers["ST"] ?: "SSDP Device"
        val port = location?.let { runCatching { Uri.parse(it).port }.getOrDefault(-1) } ?: -1

        return DiscoveredDevice(
            name = name,
            ipAddress = sourceIp,
            port = if (port > 0) port else 80,
            source = DiscoverySource.SSDP,
            rawInfo = raw
        )
    }

    companion object {
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SOCKET_POLL_TIMEOUT_MS = 500
        private const val SEARCH_REQUEST =
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: ssdp:all\r\n\r\n"
    }
}
