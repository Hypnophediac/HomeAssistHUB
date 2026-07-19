package com.homeassisthub.hub.discovery

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Runs mDNS and SSDP discovery in parallel and merges the results.
 * Exposed to the Client app (Phase 5) via the Command Router so the
 * Settings screen can list devices found on the Hub's local network.
 */
class DiscoveryManager(context: Context) {

    private val ssdpClient = SsdpDiscoveryClient(context)
    private val mdnsClient = MdnsDiscoveryClient(context)

    suspend fun discoverAll(timeoutMs: Long = 3000L): List<DiscoveredDevice> = coroutineScope {
        val ssdpDeferred = async { runCatching { ssdpClient.discover(timeoutMs) }.getOrDefault(emptyList()) }
        val mdnsDeferred = async { runCatching { mdnsClient.discover(timeoutMs = timeoutMs) }.getOrDefault(emptyList()) }
        ssdpDeferred.await() + mdnsDeferred.await()
    }
}
