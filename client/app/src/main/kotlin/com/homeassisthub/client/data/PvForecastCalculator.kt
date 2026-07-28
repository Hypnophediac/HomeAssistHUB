package com.homeassisthub.client.data

import com.homeassisthub.client.network.model.OpenMeteoResponseDto

data class PvForecastResult(
    val estimatedTodayKwh: Double,
    val currentTemperatureC: Double?,
    val currentCloudCoverPercent: Double?
)

/**
 * Physics-based PV production estimate using Open-Meteo's shortwave_radiation
 * (W/m², global horizontal irradiance) rather than a crude cloudcover-only
 * heuristic:
 *
 *   estimatedHourKwh = pvCapacityKwp * (shortwave_radiation / 1000) * performanceRatio
 *
 * 1000 W/m² is the STC (Standard Test Conditions) reference irradiance at
 * which a panel's kWp rating is measured, so this scales linearly with the
 * actual irradiance for each forecasted hour.
 */
object PvForecastCalculator {

    fun estimateToday(
        forecast: OpenMeteoResponseDto,
        pvCapacityKwp: Double,
        performanceRatio: Double
    ): PvForecastResult {
        val radiations = forecast.hourly.shortwave_radiation
        val estimatedKwh = radiations.sumOf { radiation ->
            pvCapacityKwp * (radiation / 1000.0) * performanceRatio
        }

        val nowHourIndex = currentHourIndex(forecast.hourly.time)
        val currentTemp = forecast.hourly.temperature_2m.getOrNull(nowHourIndex)
        val currentCloud = forecast.hourly.cloudcover.getOrNull(nowHourIndex)

        return PvForecastResult(
            estimatedTodayKwh = estimatedKwh,
            currentTemperatureC = currentTemp,
            currentCloudCoverPercent = currentCloud
        )
    }

    /** Open-Meteo hourly.time entries look like "2025-01-15T14:00". Finds the closest to now. */
    private fun currentHourIndex(times: List<String>): Int {
        if (times.isEmpty()) return -1
        val nowHourPrefix = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH", java.util.Locale.US)
            .format(java.util.Date())
        val exactMatch = times.indexOfFirst { it.startsWith(nowHourPrefix) }
        return if (exactMatch >= 0) exactMatch else times.size - 1
    }
}
