package com.homeassisthub.hub.controller

import com.homeassisthub.hub.data.db.P1Dao
import com.homeassisthub.hub.di.NetworkModule
import com.homeassisthub.hub.security.DeviceCredential
import kotlinx.coroutines.CoroutineScope

/**
 * Maps a [DeviceCredential.deviceType] to the concrete [DeviceController]
 * implementation that knows how to talk to it.
 *
 * Supported types: "p1_meter", "smart_plug", "v380_ptz".
 */
class DeviceControllerFactory(
    private val p1Dao: P1Dao,
    private val scope: CoroutineScope
) {

    fun create(credential: DeviceCredential): DeviceController? = when (credential.deviceType) {
        DEVICE_TYPE_P1_METER -> P1MeterController(
            credential = credential,
            p1Dao = p1Dao,
            httpClient = NetworkModule.okHttpClient,
            moshi = NetworkModule.moshi,
            scope = scope
        )
        DEVICE_TYPE_SMART_PLUG -> SmartPlugController(
            credential = credential,
            httpClient = NetworkModule.okHttpClient
        )
        DEVICE_TYPE_V380_PTZ -> V380PtzController(
            credential = credential,
            httpClient = NetworkModule.okHttpClient
        )
        else -> null
    }

    companion object {
        const val DEVICE_TYPE_P1_METER = "p1_meter"
        const val DEVICE_TYPE_SMART_PLUG = "smart_plug"
        const val DEVICE_TYPE_V380_PTZ = "v380_ptz"
    }
}
