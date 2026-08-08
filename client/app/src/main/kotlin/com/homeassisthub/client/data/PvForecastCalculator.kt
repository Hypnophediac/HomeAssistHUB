package com.homeassisthub.client.data

import com.homeassisthub.client.network.model.OpenMeteoResponseDto

data class PvForecastResult(
    val estimatedTodayKwh: Double,
    val currentTemperatureC: Double?,
    val currentCloudCoverPercent: Double?,
    val hourlyEstimates: List<HourlyEstimate> = emptyList()
)

data class HourlyEstimate(
    val hour: Int,
    val kwh: Double,
    val isPast: Boolean
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
        val times = forecast.hourly.time
        val nowHourIndex = currentHourIndex(times)

        val hourlyEstimates = radiations.mapIndexed { i, radiation ->
            val hour = parseHourFromTime(times.getOrNull(i))
            val kwh = pvCapacityKwp * (radiation / 1000.0) * performanceRatio
            HourlyEstimate(
                hour = hour,
                kwh = kwh,
                isPast = i < nowHourIndex
            )
        }

        val estimatedKwh = hourlyEstimates.sumOf { it.kwh }
        val currentTemp = forecast.hourly.temperature_2m.getOrNull(nowHourIndex)
        val currentCloud = forecast.hourly.cloudcover.getOrNull(nowHourIndex)

        return PvForecastResult(
            estimatedTodayKwh = estimatedKwh,
            currentTemperatureC = currentTemp,
            currentCloudCoverPercent = currentCloud,
            hourlyEstimates = hourlyEstimates
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

    /** Parses hour (0-23) from "2025-01-15T14:00" format. */
    private fun parseHourFromTime(time: String?): Int {
        if (time == null) return -1
        return runCatching {
            time.substringAfter("T").substringBefore(":").toInt()
        }.getOrDefault(-1)
    }
}
