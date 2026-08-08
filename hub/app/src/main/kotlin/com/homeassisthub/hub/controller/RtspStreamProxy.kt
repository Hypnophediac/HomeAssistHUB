package com.homeassisthub.hub.controller

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Continuous RTSP stream proxy: connects to an RTSP camera, decodes H.264 frames,
 * and calls [onFrame] for each decoded frame as a base64-encoded JPEG.
 *
 * Runs on a background thread. Call [stop] to terminate the stream.
 *
 * @param rtspUrl Full RTSP URL with credentials (e.g. rtsp://admin:pass@192.168.0.175:554/live/ch00_1)
 * @param onFrame Callback invoked for each decoded frame. Receives base64 JPEG string.
 * @param fps Target FPS (frames are throttled to this rate). Default 3.
 * @param maxWidth Max width for scaling. Default 640.
 * @param maxHeight Max height for scaling. Default 360.
 * @param jpegQuality JPEG compression quality (0-100). Default 60.
 */
class RtspStreamProxy(
    private val rtspUrl: String,
    private val onFrame: (String) -> Unit,
    private val fps: Int = 3,
    private val maxWidth: Int = 640,
    private val maxHeight: Int = 360,
    private val jpegQuality: Int = 60
) {
    private var socket: Socket? = null
    private var cSeq = 1
    private var csdConfigured = true
    private val running = AtomicBoolean(false)
    private var streamThread: Thread? = null

    private var imageReader: ImageReader? = null
    private var outputFormat: MediaFormat? = null
    private var outputFrameCount = 0

    fun start() {
        if (running.get()) return
        running.set(true)
        streamThread = Thread({ runStream() }, "RtspStreamProxy-${System.currentTimeMillis()}").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
        streamThread?.interrupt()
    }

    fun isRunning(): Boolean = running.get()

    private fun runStream() {
        val ip = DirectRtspSnapshot.extractIp(rtspUrl)
        val port = DirectRtspSnapshot.extractPort(rtspUrl)
        Log.i(TAG, "StreamProxy: connecting to $ip:$port, fps=$fps")

        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(ip, port), 5000)
            sock.soTimeout = 5000
            socket = sock
            val out = sock.outputStream
            val input = sock.inputStream

            // DESCRIBE
            val describeResp = sendRtsp(input, out, "DESCRIBE", rtspUrl, mapOf("Accept" to "application/sdp"))
            val sdp = extractBody(describeResp)
            Log.d(TAG, "SDP: ${sdp.replace("\n", "|")}")

            val spropMatch = Regex("""sprop-parameter-sets=([^;,\s]+),([^;,\s]+)""").find(sdp)
            if (spropMatch == null) {
                Log.e(TAG, "No sprop-parameter-sets in SDP")
                return
            }
            val spsB64 = spropMatch.groupValues[1]
            val ppsB64 = spropMatch.groupValues[2]
            val sps = Base64.decode(spsB64, Base64.DEFAULT)
            val pps = Base64.decode(ppsB64, Base64.DEFAULT)

            val trackControl = Regex("""a=control:(.+)""").find(sdp)?.groupValues?.get(1)?.trim() ?: "track1"
            val trackUrl = if (trackControl.startsWith("rtsp://")) trackControl else "$rtspUrl/$trackControl"

            // SETUP
            val setupResp = sendRtsp(input, out, "SETUP", trackUrl, mapOf(
                "Transport" to "RTP/AVP/TCP;interleaved=0-1"
            ))
            val sessionId = Regex("""Session:\s*(\S+)""").find(setupResp)?.groupValues?.get(1)?.trim()
                ?: run { Log.e(TAG, "No session ID"); return }
            val interleavedMatch = Regex("""interleaved=(\d+)-(\d+)""").find(setupResp)
            val videoChannel = interleavedMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            Log.d(TAG, "Session: $sessionId, videoChannel: $videoChannel")

            // PLAY
            sendRtsp(input, out, "PLAY", rtspUrl, mapOf(
                "Session" to sessionId,
                "Range" to "npt=0.000-"
            ))

            // ImageReader + MediaCodec
            val imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.YUV_420_888, 5)
            this.imageReader = imageReader
            val surface = imageReader.surface

            val codec = try {
                MediaCodec.createByCodecName("c2.android.avc.decoder")
            } catch (e: Exception) {
                MediaCodec.createDecoderByType("video/avc")
            }

            val csd0Bytes = ByteArray(sps.size + 4)
            csd0Bytes[0] = 0; csd0Bytes[1] = 0; csd0Bytes[2] = 0; csd0Bytes[3] = 1
            System.arraycopy(sps, 0, csd0Bytes, 4, sps.size)
            val csd1Bytes = ByteArray(pps.size + 4)
            csd1Bytes[0] = 0; csd1Bytes[1] = 0; csd1Bytes[2] = 0; csd1Bytes[3] = 1
            System.arraycopy(pps, 0, csd1Bytes, 4, pps.size)

            val format = MediaFormat.createVideoFormat("video/avc", 1920, 1080)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 196608)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0Bytes))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1Bytes))
            try {
                codec.configure(format, surface, null, 0)
                csdConfigured = true
            } catch (e: Exception) {
                csdConfigured = false
                val fmt2 = MediaFormat.createVideoFormat("video/avc", 1920, 1080)
                fmt2.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 196608)
                codec.configure(fmt2, surface, null, 0)
            }
            codec.start()
            Log.i(TAG, "MediaCodec started, stream running")

            val startTime = System.currentTimeMillis()
            val frameIntervalMs = 1000L / fps
            var lastFrameSentMs = 0L
            val fuBuffer = ByteArray(256 * 1024)
            var fuLen = 0
            var fuType = 0
            sock.soTimeout = 5000

            while (running.get()) {
                val header = try {
                    readBytes(input, 4)
                } catch (e: java.net.SocketTimeoutException) {
                    tryDecodeOutput(codec, startTime)?.let { bitmap ->
                        val now = System.currentTimeMillis()
                        if (now - lastFrameSentMs >= frameIntervalMs) {
                            sendFrame(bitmap)
                            lastFrameSentMs = now
                        }
                    }
                    continue
                }
                if (header == null) break

                if (header[0] != '$'.code.toByte()) continue

                val channel = header[1].toInt() and 0xFF
                val rtpLen = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
                if (rtpLen <= 0 || rtpLen > 65535) continue

                val rtpData = readBytes(input, rtpLen) ?: continue
                if (channel != 0 && channel != videoChannel) continue

                if (rtpData.size < 12) continue
                val payloadType = rtpData[1].toInt() and 0x7F
                if (payloadType != 96) continue

                val cc = (rtpData[0].toInt() and 0x0F) * 4
                val headerLen = 12 + cc
                if (rtpData.size <= headerLen) continue

                val padding = if ((rtpData[0].toInt() and 0x20) != 0) {
                    rtpData[rtpData.size - 1].toInt() and 0xFF
                } else 0

                val payloadStart = headerLen
                val payloadEnd = rtpData.size - padding
                if (payloadEnd <= payloadStart) continue

                val payload = rtpData.copyOfRange(payloadStart, payloadEnd)
                if (payload.isEmpty()) continue

                val nalIndicator = payload[0].toInt() and 0xFF
                val nalType = nalIndicator and 0x1F

                when (nalType) {
                    24 -> {
                        // STAP-A
                        var offset = 1
                        while (offset + 2 <= payload.size) {
                            val naluSize = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
                            offset += 2
                            if (offset + naluSize > payload.size) break
                            val nalu = payload.copyOfRange(offset, offset + naluSize)
                            feedNalUnit(codec, nalu)?.let { bitmap ->
                                val now = System.currentTimeMillis()
                                if (now - lastFrameSentMs >= frameIntervalMs) {
                                    sendFrame(bitmap)
                                    lastFrameSentMs = now
                                }
                            }
                            offset += naluSize
                        }
                    }
                    28 -> {
                        // FU-A
                        val fuHeader = payload[1].toInt() and 0xFF
                        val isStart = (fuHeader and 0x80) != 0
                        val isEnd = (fuHeader and 0x40) != 0
                        val actualType = fuHeader and 0x1F

                        if (isStart) {
                            fuType = actualType
                            fuLen = 0
                            fuBuffer[fuLen++] = ((nalIndicator and 0xE0) or actualType).toByte()
                        }

                        if (fuLen + (payload.size - 2) <= fuBuffer.size) {
                            System.arraycopy(payload, 2, fuBuffer, fuLen, payload.size - 2)
                            fuLen += payload.size - 2
                        }

                        if (isEnd && fuLen > 0) {
                            val nalu = ByteArray(fuLen)
                            System.arraycopy(fuBuffer, 0, nalu, 0, fuLen)
                            feedNalUnit(codec, nalu)?.let { bitmap ->
                                val now = System.currentTimeMillis()
                                if (now - lastFrameSentMs >= frameIntervalMs) {
                                    sendFrame(bitmap)
                                    lastFrameSentMs = now
                                }
                            }
                            fuLen = 0
                        }
                    }
                    else -> {
                        // Single NAL
                        feedNalUnit(codec, payload)?.let { bitmap ->
                            val now = System.currentTimeMillis()
                            if (now - lastFrameSentMs >= frameIntervalMs) {
                                sendFrame(bitmap)
                                lastFrameSentMs = now
                            }
                        }
                    }
                }
            }

            // Cleanup
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { imageReader.close() } catch (_: Exception) {}
            this.imageReader = null
            try { sendRtsp(input, out, "TEARDOWN", rtspUrl, mapOf("Session" to sessionId)) } catch (_: Exception) {}
            Log.i(TAG, "StreamProxy stopped")

        } catch (e: Exception) {
            Log.e(TAG, "StreamProxy error: ${e.message}", e)
        } finally {
            running.set(false)
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun sendFrame(bitmap: Bitmap) {
        try {
            val scaled = if (bitmap.width > maxWidth || bitmap.height > maxHeight) {
                val ratio = minOf(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height)
                val w = (bitmap.width * ratio).toInt()
                val h = (bitmap.height * ratio).toInt()
                Bitmap.createScaledBitmap(bitmap, w, h, true)
            } else bitmap

            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
            val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            onFrame(base64)
        } catch (e: Exception) {
            Log.e(TAG, "sendFrame error: ${e.message}")
        }
    }

    private fun feedNalUnit(codec: MediaCodec, nalUnit: ByteArray): Bitmap? {
        val nalType = nalUnit[0].toInt() and 0x1F
        val annexB = ByteArray(nalUnit.size + 4)
        annexB[0] = 0; annexB[1] = 0; annexB[2] = 0; annexB[3] = 1
        System.arraycopy(nalUnit, 0, annexB, 4, nalUnit.size)

        val inputIndex = codec.dequeueInputBuffer(2000)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex)
            inputBuffer?.let {
                it.clear()
                if (annexB.size <= it.capacity()) {
                    it.put(annexB)
                    codec.queueInputBuffer(inputIndex, 0, annexB.size, System.currentTimeMillis() * 1000, 0)
                } else {
                    codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                }
            }
        }
        return tryDecodeOutput(codec, System.currentTimeMillis())
    }

    private fun tryDecodeOutput(codec: MediaCodec, startTime: Long): Bitmap? {
        val info = MediaCodec.BufferInfo()
        val outputIndex = codec.dequeueOutputBuffer(info, 0)
        if (outputIndex >= 0) {
            if (info.size > 0) {
                outputFrameCount++
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    codec.releaseOutputBuffer(outputIndex, false)
                    return null
                }
                codec.releaseOutputBuffer(outputIndex, true)

                if (outputFrameCount < 6) {
                    val reader = imageReader
                    if (reader != null) {
                        while (true) {
                            val img = reader.acquireNextImage() ?: break
                            img.close()
                        }
                    }
                    return null
                }

                Thread.sleep(10)
                val reader = imageReader ?: return null
                val image = reader.acquireNextImage()
                if (image != null) {
                    val bitmap = yuvImageToBitmap(image)
                    image.close()
                    return bitmap
                }
            } else {
                codec.releaseOutputBuffer(outputIndex, false)
            }
        } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            outputFormat = codec.outputFormat
            Log.i(TAG, "Output format changed: $outputFormat")
        }
        return null
    }

    private fun yuvImageToBitmap(image: Image): Bitmap? {
        return try {
            val width = image.width
            val height = image.height
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yBuf = yPlane.buffer
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer
            yBuf.rewind()
            uBuf.rewind()
            vBuf.rewind()

            val yPixelStride = yPlane.pixelStride
            val yRowStride = yPlane.rowStride
            val uPixelStride = uPlane.pixelStride
            val uRowStride = uPlane.rowStride
            val vPixelStride = vPlane.pixelStride
            val vRowStride = vPlane.rowStride

            val argb = IntArray(width * height)
            for (j in 0 until height) {
                for (i in 0 until width) {
                    val yIdx = j * yRowStride + i * yPixelStride
                    val yVal = yBuf.get(yIdx).toInt() and 0xFF
                    val uvCol = i / 2
                    val uvRow = j / 2
                    val uIdx = uvRow * uRowStride + uvCol * uPixelStride
                    val vIdx = uvRow * vRowStride + uvCol * vPixelStride
                    val uVal = uBuf.get(uIdx).toInt() and 0xFF
                    val vVal = vBuf.get(vIdx).toInt() and 0xFF
                    argb[j * width + i] = yuvToArgb(yVal, uVal, vVal)
                }
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(argb, 0, width, 0, 0, width, height)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "yuvImageToBitmap error: ${e.message}")
            null
        }
    }

    private fun yuvToArgb(y: Int, u: Int, v: Int): Int {
        val yNorm = (y - 16).coerceIn(0, 255)
        val uNorm = u - 128
        val vNorm = v - 128
        val r = (1.164 * yNorm + 1.596 * vNorm).toInt().coerceIn(0, 255)
        val g = (1.164 * yNorm - 0.392 * uNorm - 0.813 * vNorm).toInt().coerceIn(0, 255)
        val b = (1.164 * yNorm + 2.017 * uNorm).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun sendRtsp(
        input: InputStream,
        out: java.io.OutputStream,
        method: String,
        url: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val sb = StringBuilder()
        sb.append("$method $url RTSP/1.0\r\n")
        sb.append("CSeq: ${cSeq++}\r\n")
        val auth = DirectRtspSnapshot.extractAuth(rtspUrl)
        if (auth != null) sb.append("Authorization: Basic $auth\r\n")
        for ((key, value) in extraHeaders) sb.append("$key: $value\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
        out.flush()
        return readRtspResponse(input)
    }

    private fun readRtspResponse(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) break
            sb.append(b.toChar())
            if (sb.length >= 4 && sb.substring(sb.length - 4) == "\r\n\r\n") {
                val contentLength = Regex("""Content-Length:\s*(\d+)""").find(sb.toString())?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (contentLength > 0) {
                    val body = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(body, read, contentLength - read)
                        if (n == -1) break
                        read += n
                    }
                    sb.append(String(body, 0, read))
                }
                break
            }
            if (sb.length > 65536) break
        }
        return sb.toString()
    }

    private fun extractBody(response: String): String {
        val idx = response.indexOf("\r\n\r\n")
        return if (idx >= 0) response.substring(idx + 4) else ""
    }

    private fun readBytes(input: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buf, read, count - read)
            if (n == -1) return null
            read += n
        }
        return buf
    }

    companion object {
        private const val TAG = "RtspStreamProxy"
    }
}
