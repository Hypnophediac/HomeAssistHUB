package com.homeassisthub.client.network

import com.homeassisthub.client.network.model.OpenMeteoResponseDto
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/** Free, no-API-key weather forecast used for the PV production estimate (Energy tab). */
interface WeatherForecastService {

    @GET("v1/forecast")
    suspend fun getForecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("hourly") hourly: String = "shortwave_radiation,temperature_2m,cloudcover",
        @Query("daily") daily: String = "sunrise,sunset",
        @Query("timezone") timezone: String = "auto",
        @Query("forecast_days") forecastDays: Int = 1
    ): OpenMeteoResponseDto

    companion object {
        private val moshi: Moshi by lazy { Moshi.Builder().build() }

        private val okHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        fun create(): WeatherForecastService {
            return Retrofit.Builder()
                .baseUrl("https://api.open-meteo.com/")
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(WeatherForecastService::class.java)
        }
    }
}
