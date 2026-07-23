package com.homeassisthub.hub.controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.homeassisthub.hub.controller.onvif.OnvifSoapBuilder
import com.homeassisthub.hub.security.DeviceCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit

class RtspCameraController(
    private val credential: DeviceCredential,
    @Suppress("unused") private val context: Context
) : DeviceController {

    override val deviceId: String = credential.deviceId
    private val streamUrl: String = credential.ipAddress

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun extractIp(): String {
        return try {
            URI(streamUrl).host ?: streamUrl
        } catch (_: Exception) {
            streamUrl
        }
    }

    override suspend fun executeCommand(action: String, params: Map<String, String>): CommandResult =
        withContext(Dispatchers.IO) {
            when (action) {
                "get_stream_url" -> CommandResult.Success(
                    mapOf("streamUrl" to streamUrl, "deviceId" to deviceId)
                )
                "get_snapshot" -> captureSnapshot()
                else -> CommandResult.Failure("Unsupported action '$action' for RTSP camera")
            }
        }

    private suspend fun captureSnapshot(): CommandResult {
        val ip = extractIp()
        val onvifPort = 8899
        Log.i(TAG, "Starting snapshot capture for $deviceId, ip=$ip")

        // Try ONVIF GetSnapshot first (fastest if supported)
        val onvifResult = tryOnvifSnapshot(ip, onvifPort)
        if (onvifResult is CommandResult.Success) return onvifResult

        // Fall back to direct RTSP + MediaCodec
        Log.i(TAG, "$deviceId ONVIF snapshot failed, falling back to DirectRtspSnapshot")
        return tryDirectRtspSnapshot()
    }

    private fun tryOnvifSnapshot(ip: String, onvifPort: Int): CommandResult {
        val soapXml = OnvifSoapBuilder.getSnapshot(
            "Profile_1", credential.username, credential.password
        )
        val endpoints = listOf(
            "http://$ip:$onvifPort/onvif/device_service",
            "http://$ip:$onvifPort/onvif/media_service",
            "http://$ip:$onvifPort/onvif/Media"
        )

        for (endpointUrl in endpoints) {
            Log.d(TAG, "$deviceId trying GetSnapshot at $endpointUrl")
            val request = Request.Builder()
                .url(endpointUrl)
                .post(soapXml.toRequestBody("application/soap+xml; charset=utf-8; action=\"http://www.onvif.org/ver10/media/wsdl/GetSnapshot\"".toMediaType()))
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    val contentType = response.header("Content-Type", "").orEmpty()
                    val body = response.body?.bytes()
                    Log.d(TAG, "$deviceId response: code=${response.code} contentType=$contentType bodySize=${body?.size ?: 0}")

                    if (body != null && body.isNotEmpty() && contentType.contains("image", ignoreCase = true)) {
                        Log.i(TAG, "$deviceId got snapshot image from ONVIF")
                        return decodeAndEncode(body)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "$deviceId ONVIF $endpointUrl failed: ${e.message}")
            }
        }
        return CommandResult.Failure("ONVIF snapshot not supported")
    }

    private fun decodeAndEncode(jpegBytes: ByteArray): CommandResult {
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        if (bitmap == null) {
            return CommandResult.Failure("Failed to decode JPEG")
        }
        Log.d(TAG, "$deviceId decoded bitmap: ${bitmap.width}x${bitmap.height}")
        val scaled = Bitmap.createScaledBitmap(bitmap, 640, 360, true)
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos)
        val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        Log.i(TAG, "$deviceId snapshot encoded, base64 size=${base64.length}")
        return CommandResult.Success(mapOf("snapshot" to base64))
    }

    private suspend fun tryExoPlayerSnapshot(): CommandResult {
        Log.i(TAG, "$deviceId starting ExoPlayerSnapshot, url=$streamUrl")
        return try {
            val snapshot = ExoPlayerSnapshot(context, streamUrl, timeoutMs = 25000)
            val base64 = snapshot.capture()
            if (base64 != null) {
                Log.i(TAG, "$deviceId ExoPlayerSnapshot success, base64 size=${base64.length}")
                CommandResult.Success(mapOf("snapshot" to base64))
            } else {
                Log.e(TAG, "$deviceId ExoPlayerSnapshot returned null")
                CommandResult.Failure("ExoPlayer snapshot failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "$deviceId ExoPlayerSnapshot error: ${e.message}", e)
            CommandResult.Failure("ExoPlayer snapshot error: ${e.message}")
        }
    }

    private fun tryDirectRtspSnapshot(): CommandResult {
        Log.i(TAG, "$deviceId starting DirectRtspSnapshot, url=$streamUrl")
        return try {
            val snapshot = DirectRtspSnapshot(streamUrl, timeoutMs = 30000)
            val base64 = snapshot.capture()
            if (base64 != null) {
                Log.i(TAG, "$deviceId DirectRtspSnapshot success, base64 size=${base64.length}")
                CommandResult.Success(mapOf("snapshot" to base64))
            } else {
                Log.e(TAG, "$deviceId DirectRtspSnapshot returned null")
                CommandResult.Failure("Failed to capture snapshot from RTSP stream")
            }
        } catch (e: Exception) {
            Log.e(TAG, "$deviceId DirectRtspSnapshot error: ${e.message}", e)
            CommandResult.Failure("Snapshot error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "RtspCameraController"
    }
}
