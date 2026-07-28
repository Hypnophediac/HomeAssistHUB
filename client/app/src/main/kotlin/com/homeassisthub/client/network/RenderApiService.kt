package com.homeassisthub.client.network

import com.homeassisthub.client.network.model.EnergyDailyResponseDto
import com.homeassisthub.client.network.model.EnergyPeriodResponseDto
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the Render relay's energy REST API.
 * All historical energy data (daily/weekly/monthly/yearly/range) is
 * served from MongoDB via the relay — the Socket.IO channel is only
 * used for live control and real-time dashboard data.
 */
interface RenderApiService {

    @GET("api/energy/{homeId}/daily")
    suspend fun getEnergyDaily(
        @Path("homeId") homeId: String,
        @Header("Authorization") authHeader: String
    ): EnergyDailyResponseDto

    @GET("api/energy/{homeId}/weekly")
    suspend fun getEnergyWeekly(
        @Path("homeId") homeId: String,
        @Header("Authorization") authHeader: String
    ): EnergyPeriodResponseDto

    @GET("api/energy/{homeId}/monthly")
    suspend fun getEnergyMonthly(
        @Path("homeId") homeId: String,
        @Header("Authorization") authHeader: String
    ): EnergyPeriodResponseDto

    @GET("api/energy/{homeId}/yearly")
    suspend fun getEnergyYearly(
        @Path("homeId") homeId: String,
        @Header("Authorization") authHeader: String
    ): EnergyPeriodResponseDto

    @GET("api/energy/{homeId}/range")
    suspend fun getEnergyRange(
        @Path("homeId") homeId: String,
        @Header("Authorization") authHeader: String,
        @Query("startDate") startDate: String,
        @Query("endDate") endDate: String
    ): EnergyPeriodResponseDto
}
