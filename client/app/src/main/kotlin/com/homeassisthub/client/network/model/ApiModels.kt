package com.homeassisthub.client.network.model

import com.squareup.moshi.JsonClass

/** Mirrors the Hub's Ktor API DTOs (com.homeassisthub.hub.api.dto.*). */

@JsonClass(generateAdapter = true)
data class DiscoveredDeviceDto(
    val name: String,
    val ipAddress: String,
    val port: Int,
    val source: String
)

/** Request body for POST /api/v1/devices (includes credentials). */
@JsonClass(generateAdapter = true)
data class DeviceCredentialRequestDto(
    val deviceId: String,
    val deviceType: String,
    val ipAddress: String,
    val port: Int,
    val username: String = "",
    val password: String = ""
)

/** Response representation; the Hub never returns passwords. */
@JsonClass(generateAdapter = true)
data class DeviceCredentialSummaryDto(
    val deviceId: String,
    val deviceType: String,
    val ipAddress: String,
    val port: Int,
    val username: String
)

@JsonClass(generateAdapter = true)
data class P1ReadingDto(
    val timestamp: Long,
    val powerW: Double,
    val voltageV: Double
)
