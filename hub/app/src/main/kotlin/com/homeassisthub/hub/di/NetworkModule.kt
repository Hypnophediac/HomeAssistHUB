package com.homeassisthub.hub.di

import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Process-wide singletons for local-network HTTP calls to devices
 * (P1 meter, smart plug, V380 ONVIF). Kept deliberately simple (no DI
 * framework) since the hub app has a small, fixed set of dependencies.
 */
object NetworkModule {

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    val moshi: Moshi by lazy {
        Moshi.Builder().build()
    }
}
