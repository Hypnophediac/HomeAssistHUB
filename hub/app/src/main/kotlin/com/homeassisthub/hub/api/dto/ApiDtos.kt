package com.homeassisthub.hub.api.dto

import com.homeassisthub.hub.data.db.P1DataEntity
import com.homeassisthub.hub.discovery.DiscoveredDevice
import com.homeassisthub.hub.security.DeviceCredential
import kotlinx.serialization.Serializable

@Serializable
data class DiscoveredDeviceDto(
    val name: String,
    val ipAddress: String,
    val port: Int,
    val source: String
)

fun DiscoveredDevice.toDto() = DiscoveredDeviceDto(
    name = name,
    ipAddress = ipAddress,
    port = port,
    source = source.name
)

/** Request/full representation, used only for the POST body (includes credentials). */
@Serializable
data class DeviceCredentialDto(
    val deviceId: String,
    val deviceType: String,
    val ipAddress: String,
    val port: Int,
    val username: String = "",
    val password: String = ""
)

fun DeviceCredentialDto.toDomain() = DeviceCredential(
    deviceId = deviceId,
    deviceType = deviceType,
    ipAddress = ipAddress,
    port = port,
    username = username,
    password = password
)

/** Response representation for listing devices; deliberately omits the password. */
@Serializable
data class DeviceCredentialSummaryDto(
    val deviceId: String,
    val deviceType: String,
    val ipAddress: String,
    val port: Int,
    val username: String
)

fun DeviceCredential.toSummaryDto() = DeviceCredentialSummaryDto(
    deviceId = deviceId,
    deviceType = deviceType,
    ipAddress = ipAddress,
    port = port,
    username = username
)

@Serializable
data class P1ReadingDto(
    val timestamp: Long,
    val powerW: Double,
    val voltageV: Double
)

fun P1DataEntity.toDto() = P1ReadingDto(
    timestamp = timestamp,
    powerW = powerW,
    voltageV = voltageV
)

@Serializable
data class ApiErrorDto(val error: String)

@Serializable
data class ApiHealthDto(val status: String, val uptimeMs: Long)
