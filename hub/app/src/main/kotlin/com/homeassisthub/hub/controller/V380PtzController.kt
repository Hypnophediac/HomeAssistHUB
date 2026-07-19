package com.homeassisthub.hub.controller

import com.homeassisthub.hub.controller.onvif.OnvifSoapBuilder
import com.homeassisthub.hub.security.DeviceCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Pan/Tilt control for a V380 PTZ camera via ONVIF SOAP requests sent to
 * http://[IP]:8899/onvif/device_service. Movement uses ContinuousMove with
 * a fixed velocity per direction, followed by an explicit Stop.
 */
class V380PtzController(
    private val credential: DeviceCredential,
    private val httpClient: OkHttpClient,
    private val profileToken: String = "Profile_1"
) : DeviceController {

    override val deviceId: String = credential.deviceId

    override suspend fun executeCommand(action: String, params: Map<String, String>): CommandResult =
        withContext(Dispatchers.IO) {
            val speed = params["speed"]?.toDoubleOrNull() ?: DEFAULT_SPEED
            when (action) {
                "pan_left" -> move(panX = -speed, tiltY = 0.0)
                "pan_right" -> move(panX = speed, tiltY = 0.0)
                "tilt_up" -> move(panX = 0.0, tiltY = speed)
                "tilt_down" -> move(panX = 0.0, tiltY = -speed)
                "stop" -> stop()
                else -> CommandResult.Failure("Unsupported action '$action' for V380 PTZ")
            }
        }

    private fun move(panX: Double, tiltY: Double): CommandResult {
        val soapBody = OnvifSoapBuilder.continuousMove(
            profileToken, panX, tiltY, credential.username, credential.password
        )
        return sendOnvifRequest(soapBody)
    }

    private fun stop(): CommandResult {
        val soapBody = OnvifSoapBuilder.stop(profileToken, credential.username, credential.password)
        return sendOnvifRequest(soapBody)
    }

    private fun sendOnvifRequest(soapXml: String): CommandResult = runCatching {
        val url = "http://${credential.ipAddress}:${credential.port}/onvif/device_service"
        val request = Request.Builder()
            .url(url)
            .post(soapXml.toRequestBody("application/soap+xml; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} from V380 ONVIF service")
            response.body?.string().orEmpty()
        }
    }.fold(
        onSuccess = { responseXml -> CommandResult.Success(mapOf("onvif_response" to responseXml)) },
        onFailure = { throwable -> CommandResult.Failure(throwable.message ?: "Unknown V380 PTZ error") }
    )

    companion object {
        private const val DEFAULT_SPEED = 0.5
    }
}
