package com.homeassisthub.hub.controller

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.graphics.Rect
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume

class ExoPlayerSnapshot(
    private val context: android.content.Context,
    private val rtspUrl: String,
    private val timeoutMs: Long = 25000
) {
    companion object {
        private const val TAG = "ExoPlayerSnapshot"
    }

    suspend fun capture(): String? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->

            val handlerThread = HandlerThread("exoplayer-snapshot").apply { start() }
            val handler = Handler(handlerThread.looper)

            // Create ImageReader for frame capture
            val imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.YUV_420_888, 5)
            val surface = imageReader.surface

            var frameCount = 0
            var captured = false

            imageReader.setOnImageAvailableListener({ reader ->
                if (captured || !cont.isActive) return@setOnImageAvailableListener

                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                frameCount++

                Log.d(TAG, "Image available #$frameCount format=${image.format} ${image.width}x${image.height}")

                // Skip first 30 frames (~1.5s at 20fps) to let decoder get IDR
                if (frameCount < 30) {
                    image.close()
                    return@setOnImageAvailableListener
                }

                try {
                    val bitmap = yuvToBitmap(image)
                    image.close()

                    if (bitmap != null) {
                        // Check if frame has actual content (not all black)
                        var hasContent = false
                        val pixels = IntArray(100)
                        bitmap.getPixels(pixels, 0, 10, 0, 0, 10, 10)
                        for (px in pixels) {
                            if ((px and 0xFFFFFF) != 0) {
                                hasContent = true
                                break
                            }
                        }

                        if (hasContent) {
                            captured = true
                            Log.i(TAG, "Got frame with content at #$frameCount")
                            val scaled = Bitmap.createScaledBitmap(bitmap, 640, 360, true)
                            val baos = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                            val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                            Log.i(TAG, "Snapshot encoded, base64 size=${base64.length}")
                            cont.resume(base64)
                        } else {
                            Log.d(TAG, "Frame #$frameCount is black, skipping")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing image", e)
                    image.close()
                }
            }, handler)

            // Create ExoPlayer on main thread
            val mainHandler = Handler(android.os.Looper.getMainLooper())
            mainHandler.post {
                try {
                    val player = ExoPlayer.Builder(context).build()
                    val mediaItem = MediaItem.fromUri(rtspUrl)
                    val rtspSource = RtspMediaSource.Factory().createMediaSource(mediaItem)

                    player.setVideoSurface(surface)
                    player.setMediaSource(rtspSource)
                    player.prepare()
                    player.playWhenReady = true
                    Log.d(TAG, "ExoPlayer prepared with RTSP: $rtspUrl")

                    player.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            val stateName = when (state) {
                                Player.STATE_IDLE -> "IDLE"
                                Player.STATE_BUFFERING -> "BUFFERING"
                                Player.STATE_READY -> "READY"
                                Player.STATE_ENDED -> "ENDED"
                                else -> "UNKNOWN($state)"
                            }
                            Log.d(TAG, "Player state: $stateName")
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            Log.e(TAG, "Player error: ${error.message}")
                            if (cont.isActive) {
                                cont.resume(null)
                            }
                        }
                    })

                    cont.invokeOnCancellation {
                        mainHandler.post {
                            try { player.release() } catch (_: Exception) {}
                        }
                        try { imageReader.close() } catch (_: Exception) {}
                        handlerThread.quitSafely()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ExoPlayer creation failed", e)
                    if (cont.isActive) {
                        cont.resume(null)
                    }
                }
            }
        }
    }

    private fun yuvToBitmap(image: android.media.Image): Bitmap? {
        return try {
            val width = image.width
            val height = image.height

            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yBuf = yPlane.buffer
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer

            val yRowStride = yPlane.rowStride
            val uRowStride = uPlane.rowStride
            val vRowStride = vPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            val uPixelStride = uPlane.pixelStride
            val vPixelStride = vPlane.pixelStride

            // Convert to NV21 format for YuvImage
            val ySize = width * height
            val nv21 = ByteArray(ySize + ySize / 2)

            // Copy Y plane
            yBuf.rewind()
            for (j in 0 until height) {
                yBuf.position(j * yRowStride)
                for (i in 0 until width) {
                    nv21[j * width + i] = yBuf.get()
                    if (yPixelStride == 2) yBuf.get() // skip
                }
            }

            // Copy UV planes to NV21 (VU interleaved)
            uBuf.rewind()
            vBuf.rewind()
            val uvHeight = height / 2
            val uvWidth = width / 2
            for (j in 0 until uvHeight) {
                for (i in 0 until uvWidth) {
                    val uIdx = j * uRowStride + i * uPixelStride
                    val vIdx = j * vRowStride + i * vPixelStride
                    nv21[ySize + j * uvWidth * 2 + i * 2] = vBuf.get(vIdx)
                    nv21[ySize + j * uvWidth * 2 + i * 2 + 1] = uBuf.get(uIdx)
                }
            }

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val baos = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, baos)
            val bytes = baos.toByteArray()
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "yuvToBitmap error: ${e.message}", e)
            null
        }
    }
}
