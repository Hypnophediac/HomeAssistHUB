package com.homeassisthub.hub.controller

import android.content.Context
import com.homeassisthub.hub.data.db.P1Dao
import com.homeassisthub.hub.data.db.P1RawDao
import com.homeassisthub.hub.di.NetworkModule
import com.homeassisthub.hub.security.DeviceCredential
import kotlinx.coroutines.CoroutineScope

class DeviceControllerFactory(
    private val p1Dao: P1Dao,
    private val p1RawDao: P1RawDao,
    private val scope: CoroutineScope,
    private val context: Context
) {

    fun create(credential: DeviceCredential): DeviceController? = when (credential.deviceType) {
        DEVICE_TYPE_P1_METER -> P1MeterController(
            credential = credential,
            p1Dao = p1Dao,
            httpClient = NetworkModule.okHttpClient,
            moshi = NetworkModule.moshi,
            scope = scope,
            p1RawDao = p1RawDao
        )
        DEVICE_TYPE_SMART_PLUG -> SmartPlugController(
            credential = credential,
            httpClient = NetworkModule.okHttpClient
        )
        DEVICE_TYPE_V380_PTZ -> V380PtzController(
            credential = credential,
            httpClient = NetworkModule.okHttpClient
        )
        DEVICE_TYPE_RTSP_CAMERA -> RtspCameraController(
            credential = credential,
            context = context
        )
        DEVICE_TYPE_HUAWEI_INVERTER -> HuaweiInverterController(
            credential = credential,
            scope = scope
        )
        else -> null
    }

    companion object {
        const val DEVICE_TYPE_P1_METER = "p1_meter"
        const val DEVICE_TYPE_SMART_PLUG = "smart_plug"
        const val DEVICE_TYPE_V380_PTZ = "v380_ptz"
        const val DEVICE_TYPE_RTSP_CAMERA = "rtsp_camera"
        const val DEVICE_TYPE_HUAWEI_INVERTER = "huawei_inverter"
    }
}
