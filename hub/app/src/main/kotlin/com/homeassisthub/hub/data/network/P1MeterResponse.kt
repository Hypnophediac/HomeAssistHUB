package com.homeassisthub.hub.data.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Raw JSON payload returned by the ADA P1 meter's local HTTP API
 * (e.g. GET http://[IP]/api/v1/data).
 */
@JsonClass(generateAdapter = true)
data class P1MeterResponse(
    @Json(name = "timestamp") val timestamp: Long? = null,
    @Json(name = "power_w") val powerW: Double,
    @Json(name = "voltage_v") val voltageV: Double
)
