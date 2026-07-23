package com.homeassisthub.client.network

import com.homeassisthub.client.network.model.DeviceCredentialRequestDto
import com.homeassisthub.client.network.model.DeviceCredentialSummaryDto
import com.homeassisthub.client.network.model.DiscoveredDeviceDto
import com.homeassisthub.client.network.model.EnergyDailyResponseDto
import com.homeassisthub.client.network.model.EnergyPeriodResponseDto
import com.homeassisthub.client.network.model.P1ReadingDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Retrofit contract for the Hub's local LAN Ktor API (Phase 4). */
interface HubApiService {

    @GET("api/v1/devices/discover")
    suspend fun discoverDevices(@Query("timeoutMs") timeoutMs: Long = 3000L): List<DiscoveredDeviceDto>

    @GET("api/v1/devices")
    suspend fun getDevices(): List<DeviceCredentialSummaryDto>

    @POST("api/v1/devices")
    suspend fun saveDevice(@Body credential: DeviceCredentialRequestDto): DeviceCredentialSummaryDto

    @DELETE("api/v1/devices/{deviceId}")
    suspend fun deleteDevice(@Path("deviceId") deviceId: String): Response<Unit>

    @GET("api/v1/p1/history")
    suspend fun getP1History(@Query("limit") limit: Int = 100): List<P1ReadingDto>

    @GET("api/v1/energy/daily")
    suspend fun getEnergyDaily(): EnergyDailyResponseDto

    @GET("api/v1/energy/weekly")
    suspend fun getEnergyWeekly(): EnergyPeriodResponseDto

    @GET("api/v1/energy/monthly")
    suspend fun getEnergyMonthly(): EnergyPeriodResponseDto

    @GET("api/v1/energy/yearly")
    suspend fun getEnergyYearly(): EnergyPeriodResponseDto
}
