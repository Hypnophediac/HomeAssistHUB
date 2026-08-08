package com.homeassisthub.client.network

import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Resolves a location name (e.g. "Budapest") to geographic coordinates
 * using the free Open-Meteo Geocoding API (no API key required).
 *
 * https://geocoding-api.open-meteo.com/v1/search?name=Budapest&count=5&language=hu
 */
object GeocodingService {

    data class GeocodingResult(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val country: String? = null,
        val admin1: String? = null
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val moshi by lazy { Moshi.Builder().build() }

    suspend fun search(query: String, language: String = "hu"): List<GeocodingResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        runCatching {
            val url = "https://geocoding-api.open-meteo.com/v1/search" +
                "?name=${java.net.URLEncoder.encode(query.trim(), "UTF-8")}" +
                "&count=5&language=$language&format=json"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string().orEmpty()
                val json = org.json.JSONObject(body)
                val results = json.optJSONArray("results") ?: return@withContext emptyList()
                (0 until results.length()).map { i ->
                    val obj = results.getJSONObject(i)
                    GeocodingResult(
                        name = obj.optString("name", ""),
                        latitude = obj.optDouble("latitude", 0.0),
                        longitude = obj.optDouble("longitude", 0.0),
                        country = obj.optString("country", null),
                        admin1 = obj.optString("admin1", null)
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
