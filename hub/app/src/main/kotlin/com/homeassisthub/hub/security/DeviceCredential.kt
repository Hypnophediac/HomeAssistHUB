package com.homeassisthub.hub.security

/**
 * Credentials + connection info for a single local device (P1 meter,
 * smart plug, V380 PTZ camera, ...). Never persisted in plaintext:
 * always stored/read through [SecureCredentialStore].
 */
data class DeviceCredential(
    val deviceId: String,
    val deviceType: String,
    val ipAddress: String,
    val port: Int,
    val username: String,
    val password: String
)
