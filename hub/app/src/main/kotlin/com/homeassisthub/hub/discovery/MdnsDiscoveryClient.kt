package com.homeassisthub.hub.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * mDNS discovery via Android's [NsdManager]. Finds services of
 * [serviceType] (default "_http._tcp.", which most local IoT devices'
 * web/REST APIs advertise) and resolves each to an IP + port.
 */
class MdnsDiscoveryClient(private val context: Context) {

    suspend fun discover(
        serviceType: String = "_http._tcp.",
        timeoutMs: Long = 3000L
    ): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val results = mutableListOf<DiscoveredDevice>()
        val lock = Any()

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) { /* ignore unresolved entries */ }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                synchronized(lock) {
                    results.add(
                        DiscoveredDevice(
                            name = serviceInfo.serviceName,
                            ipAddress = host,
                            port = serviceInfo.port,
                            source = DiscoverySource.MDNS
                        )
                    )
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) { /* no-op */ }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                runCatching { nsdManager.resolveService(serviceInfo, resolveListener) }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) { /* no-op */ }
            override fun onDiscoveryStopped(serviceType: String?) { /* no-op */ }
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) { /* no-op */ }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) { /* no-op */ }
        }

        runCatching { nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
        delay(timeoutMs)
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        delay(RESOLVE_DRAIN_MS) // let any in-flight resolveService callbacks land

        synchronized(lock) { results.toList() }
    }

    companion object {
        private const val RESOLVE_DRAIN_MS = 500L
    }
}
