package com.homeassisthub.hub.discovery

enum class DiscoverySource { MDNS, SSDP }

/**
 * A device found on the local network, before any credentials have been
 * associated with it. Surfaced to the Client app's Settings screen so the
 * user can pick a device and enter its username/password.
 */
data class DiscoveredDevice(
    val name: String,
    val ipAddress: String,
    val port: Int,
    val source: DiscoverySource,
    val rawInfo: String = ""
)
