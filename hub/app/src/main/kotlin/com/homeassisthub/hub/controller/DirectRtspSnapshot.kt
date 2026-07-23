package com.homeassisthub.hub.controller

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Base64
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class DirectRtspSnapshot(
    private val rtspUrl: String,
    private val timeoutMs: Long = 20000
) {
    private var socket: Socket? = null
    private var cSeq = 1
    private var csdConfigured = true

    fun capture(): String? {
        val ip = extractIp(rtspUrl)
        val port = extractPort(rtspUrl)
        Log.i(TAG, "DirectRtspSnapshot: connecting to $ip:$port")

        try {
            // 1. Connect
            val sock = Socket()
            sock.connect(InetSocketAddress(ip, port), 5000)
            sock.soTimeout = 5000
            socket = sock
            val out = sock.outputStream
            val input = sock.inputStream

            // 2. DESCRIBE
            val describeResp = sendRtsp(input, out, "DESCRIBE", rtspUrl, mapOf("Accept" to "application/sdp"))
            val sdp = extractBody(describeResp)
            Log.d(TAG, "SDP: ${sdp.replace("\n", "|")}")

            val spropMatch = Regex("""sprop-parameter-sets=([^;,\s]+),([^;,\s]+)""").find(sdp)
            if (spropMatch == null) {
                Log.e(TAG, "No sprop-parameter-sets in SDP")
                return null
            }
            val spsB64 = spropMatch.groupValues[1]
            val ppsB64 = spropMatch.groupValues[2]
            val sps = Base64.decode(spsB64, Base64.DEFAULT)
            val pps = Base64.decode(ppsB64, Base64.DEFAULT)
            Log.d(TAG, "SPS: ${sps.size} bytes, PPS: ${pps.size} bytes")

            // Parse track control
            val trackControl = Regex("""a=control:(.+)""").find(sdp)?.groupValues?.get(1)?.trim() ?: "track1"
            val trackUrl = if (trackControl.startsWith("rtsp://")) trackControl else "$rtspUrl/$trackControl"
            Log.d(TAG, "Track URL: $trackUrl")

            // 3. SETUP (TCP interleaved)
            val setupResp = sendRtsp(input, out, "SETUP", trackUrl, mapOf(
                "Transport" to "RTP/AVP/TCP;interleaved=0-1"
            ))
            Log.d(TAG, "SETUP response: ${setupResp.take(300).replace("\n", "|")}")
            val sessionId = Regex("""Session:\s*(\S+)""").find(setupResp)?.groupValues?.get(1)?.trim()
                ?: run { Log.e(TAG, "No session ID in SETUP response"); return null }
            Log.d(TAG, "Session ID: $sessionId")

            // Parse actual interleaved channel from SETUP response
            val interleavedMatch = Regex("""interleaved=(\d+)-(\d+)""").find(setupResp)
            val videoChannel = interleavedMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            Log.d(TAG, "Video channel: $videoChannel")

            // 4. PLAY
            val playResp = sendRtsp(input, out, "PLAY", rtspUrl, mapOf(
                "Session" to sessionId,
                "Range" to "npt=0.000-"
            ))
            Log.d(TAG, "PLAY response: ${playResp.take(200).replace("\n", "|")}")

            // 5. Setup ImageReader + Surface for decoded frames
            val imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.YUV_420_888, 5)
            this.imageReader = imageReader
            val surface = imageReader.surface

            // 6. Setup MediaCodec - use software decoder to avoid MIUI wrapper issues
            val codec = try {
                MediaCodec.createByCodecName("c2.android.avc.decoder")
            } catch (e: Exception) {
                Log.w(TAG, "Software decoder not available, trying default: ${e.message}")
                MediaCodec.createDecoderByType("video/avc")
            }

            // Set CSD - Annex-B format: start code + NAL unit
            val csd0Bytes = ByteArray(sps.size + 4)
            csd0Bytes[0] = 0; csd0Bytes[1] = 0; csd0Bytes[2] = 0; csd0Bytes[3] = 1
            System.arraycopy(sps, 0, csd0Bytes, 4, sps.size)
            val csd1Bytes = ByteArray(pps.size + 4)
            csd1Bytes[0] = 0; csd1Bytes[1] = 0; csd1Bytes[2] = 0; csd1Bytes[3] = 1
            System.arraycopy(pps, 0, csd1Bytes, 4, pps.size)

            Log.d(TAG, "CSD-0: ${csd0Bytes.size} bytes, CSD-1: ${csd1Bytes.size} bytes")
            val format = MediaFormat.createVideoFormat("video/avc", 1920, 1080)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 196608)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0Bytes))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1Bytes))
            try {
                codec.configure(format, surface, null, 0)
                csdConfigured = true
            } catch (e: Exception) {
                Log.e(TAG, "configure failed with CSD, retrying without: ${e.message}")
                csdConfigured = false
                val fmt2 = MediaFormat.createVideoFormat("video/avc", 1920, 1080)
                fmt2.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 196608)
                codec.configure(fmt2, surface, null, 0)
            }
            codec.start()
            Log.d(TAG, "MediaCodec started with Surface")

            // 6. Receive RTP packets and feed to decoder
            val startTime = System.currentTimeMillis()
            var frameDecoded = false
            var decodedBitmap: Bitmap? = null
            val fuBuffer = ByteArray(256 * 1024) // FU-A reassembly buffer
            var fuLen = 0
            var fuType = 0

            sock.soTimeout = 5000 // 5s timeout for reading RTP data

            while (!frameDecoded && System.currentTimeMillis() - startTime < timeoutMs) {
                // Read interleaved data: $<channel><len_hi><len_lo><data>
                val header = try {
                    readBytes(input, 4)
                } catch (e: java.net.SocketTimeoutException) {
                    // Timeout reading RTP, try to dequeue output
                    tryDecodeOutput(codec, startTime, timeoutMs)?.let {
                        decodedBitmap = it
                        frameDecoded = true
                    }
                    continue
                }
                if (header == null) {
                    // EOF, try to dequeue output
                    tryDecodeOutput(codec, startTime, timeoutMs)?.let {
                        decodedBitmap = it
                        frameDecoded = true
                    }
                    continue
                }

                if (header[0] != '$'.code.toByte()) {
                    // Might be an RTSP response, skip line
                    Log.w(TAG, "Unexpected byte: ${header[0]}")
                    continue
                }

                val channel = header[1].toInt() and 0xFF
                val rtpLen = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
                if (rtpLen <= 0 || rtpLen > 65535) continue

                val rtpData = readBytes(input, rtpLen) ?: continue
                // Accept both channel 0 and the negotiated video channel
                if (channel != 0 && channel != videoChannel) {
                    if (channel == videoChannel + 1 || channel == 1) {
                        Log.d(TAG, "RTCP packet on channel $channel, len=$rtpLen")
                    }
                    continue
                }

                // Parse RTP header
                if (rtpData.size < 12) continue
                val payloadType = rtpData[1].toInt() and 0x7F
                if (payloadType != 96) continue

                // Log RTP sequence number for first few packets
                val rtpSeq = ((rtpData[2].toInt() and 0xFF) shl 8) or (rtpData[3].toInt() and 0xFF)
                val rtpTimestamp = ((rtpData[4].toInt() and 0xFF).toLong() shl 24) or
                    ((rtpData[5].toInt() and 0xFF).toLong() shl 16) or
                    ((rtpData[6].toInt() and 0xFF).toLong() shl 8) or
                    (rtpData[7].toInt() and 0xFF).toLong()

                // Skip RTP header (12 bytes + extensions)
                val cc = (rtpData[0].toInt() and 0x0F) * 4
                val headerLen = 12 + cc
                if (rtpData.size <= headerLen) continue

                // Check for padding
                val padding = if ((rtpData[0].toInt() and 0x20) != 0) {
                    rtpData[rtpData.size - 1].toInt() and 0xFF
                } else 0

                val payloadStart = headerLen
                val payloadEnd = rtpData.size - padding
                if (payloadEnd <= payloadStart) continue

                val payload = rtpData.copyOfRange(payloadStart, payloadEnd)
                if (payload.isEmpty()) continue

                // Parse H.264 NAL unit from RTP payload
                val nalIndicator = payload[0].toInt() and 0xFF
                val nalType = nalIndicator and 0x1F

                // Log raw bytes for first few packets
                if (rtpSeq < 3) {
                    val hex = payload.take(4).joinToString(" ") { String.format("%02X", it) }
                    Log.d(TAG, "RTP seq=$rtpSeq nalType=$nalType hex=$hex payloadSize=${payload.size}")
                }

                if (nalType == 5) {
                    Log.i(TAG, "Got IDR frame (NAL type 5)")
                    gotIDR = true
                }

                when {
                    nalType in 1..23 -> {
                        // Single NAL Unit Packet
                        feedNalUnit(codec, payload, startTime, timeoutMs)?.let {
                            decodedBitmap = it
                            frameDecoded = true
                        }
                    }
                    nalType == 24 -> {
                        // STAP-A: Multiple NAL units
                        var offset = 1
                        while (offset + 2 <= payload.size) {
                            val nalSize = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
                            offset += 2
                            if (offset + nalSize > payload.size) break
                            val nalUnit = payload.copyOfRange(offset, offset + nalSize)
                            if (!frameDecoded) {
                                feedNalUnit(codec, nalUnit, startTime, timeoutMs)?.let {
                                    decodedBitmap = it
                                    frameDecoded = true
                                }
                            }
                            offset += nalSize
                        }
                    }
                    nalType == 28 -> {
                        // FU-A: Fragmented NAL unit
                        if (payload.size < 2) continue
                        val fuHeader = payload[1].toInt() and 0xFF
                        val isStart = (fuHeader and 0x80) != 0
                        val isEnd = (fuHeader and 0x40) != 0
                        val fuNalType = fuHeader and 0x1F
                        val nalRefIdc = (nalIndicator and 0x60)

                        if (isStart) {
                            fuLen = 0
                            fuType = fuNalType
                            if (fuNalType == 5) {
                                Log.i(TAG, "Got IDR frame via FU-A (NAL type 5)")
                                gotIDR = true
                            }
                            // Reconstruct NAL header
                            fuBuffer[fuLen++] = (nalRefIdc or fuNalType).toByte()
                        }

                        if (fuLen + payload.size - 2 < fuBuffer.size) {
                            System.arraycopy(payload, 2, fuBuffer, fuLen, payload.size - 2)
                            fuLen += payload.size - 2
                        }

                        if (isEnd && fuLen > 0) {
                            val nalUnit = fuBuffer.copyOfRange(0, fuLen)
                            if (!frameDecoded) {
                                feedNalUnit(codec, nalUnit, startTime, timeoutMs)?.let {
                                    decodedBitmap = it
                                    frameDecoded = true
                                }
                            }
                            fuLen = 0
                        }
                    }
                }
            }

            // Final attempt to dequeue output
            if (!frameDecoded) {
                tryDecodeOutput(codec, startTime, timeoutMs, extendedWait = true)?.let {
                    decodedBitmap = it
                    frameDecoded = true
                }
            }

            // Cleanup
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { imageReader.close() } catch (_: Exception) {}
            this.imageReader = null

            // TEARDOWN
            try {
                sendRtsp(input, out, "TEARDOWN", rtspUrl, mapOf("Session" to sessionId))
            } catch (_: Exception) {}

            val bitmap = decodedBitmap
            if (bitmap != null) {
                Log.i(TAG, "Snapshot captured: ${bitmap.width}x${bitmap.height}")
                val scaled = Bitmap.createScaledBitmap(bitmap, 640, 360, true)
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                Log.i(TAG, "Snapshot encoded, base64 size=${base64.length}")
                return base64
            }

            Log.e(TAG, "No frame decoded within timeout")
            return null

        } catch (e: Exception) {
            Log.e(TAG, "capture error: ${e.message}", e)
            return null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private var pendingBuffers = mutableListOf<Pair<Int, ByteArray>>()
    private var firstFrameFed = false

    private fun feedNalUnit(
        codec: MediaCodec,
        nalUnit: ByteArray,
        startTime: Long,
        timeoutMs: Long
    ): Bitmap? {
        val nalType = nalUnit[0].toInt() and 0x1F

        // Feed all NAL units to decoder - it handles inline SPS/PPS fine
        // Note: camera may send IDR with wrong fuNalType in FU-A header
        if (nalType == 7 || nalType == 8) {
            Log.d(TAG, "NAL type $nalType (SPS/PPS) size=${nalUnit.size}")
        }

        // Feed everything (including SPS/PPS) to the decoder
        return feedNalUnitInternal(codec, nalUnit, nalType, startTime, timeoutMs)
    }

    private fun feedNalUnitInternal(
        codec: MediaCodec,
        nalUnit: ByteArray,
        nalType: Int,
        startTime: Long,
        timeoutMs: Long
    ): Bitmap? {
        firstFrameFed = true
        // Convert to Annex-B format (add start code)
        val annexB = ByteArray(nalUnit.size + 4)
        annexB[0] = 0; annexB[1] = 0; annexB[2] = 0; annexB[3] = 1
        System.arraycopy(nalUnit, 0, annexB, 4, nalUnit.size)

        // Queue input buffer
        val inputIndex = codec.dequeueInputBuffer(2000)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex)
            inputBuffer?.let {
                it.clear()
                if (annexB.size <= it.capacity()) {
                    it.put(annexB)
                    codec.queueInputBuffer(inputIndex, 0, annexB.size, System.currentTimeMillis() * 1000, 0)
                    Log.d(TAG, "Fed NAL type=$nalType size=${annexB.size}")
                } else {
                    Log.w(TAG, "NAL too large for input buffer: ${annexB.size} > ${it.capacity()}")
                    codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                }
            }
        }

        return tryDecodeOutput(codec, startTime, timeoutMs)
    }

    private var outputFormat: MediaFormat? = null
    private var outputFrameCount = 0
    private var gotIDR = false
    private var imageReader: ImageReader? = null

    private fun tryDecodeOutput(
        codec: MediaCodec,
        startTime: Long,
        timeoutMs: Long,
        extendedWait: Boolean = false
    ): Bitmap? {
        val waitMs = if (extendedWait) 5000 else 0

        val info = MediaCodec.BufferInfo()
        while (System.currentTimeMillis() - startTime < timeoutMs || extendedWait) {
            val outputIndex = codec.dequeueOutputBuffer(info, waitMs.toLong() * 1000)
            if (outputIndex >= 0) {
                if (info.size > 0) {
                    outputFrameCount++
                    if (outputFrameCount % 20 == 0 || outputFrameCount > 120) {
                        Log.d(TAG, "Output buffer #$outputFrameCount: index=$outputIndex size=${info.size} flags=${info.flags}")
                    }
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        codec.releaseOutputBuffer(outputIndex, false)
                        continue
                    }
                    // Render to surface
                    codec.releaseOutputBuffer(outputIndex, true)

                    // Skip first 5 frames to let decoder stabilize
                    if (outputFrameCount < 6) {
                        if (outputFrameCount % 20 == 0) {
                            Log.d(TAG, "Skipping frame #$outputFrameCount (waiting for IDR)")
                        }
                        // Drain ImageReader queue
                        val reader = imageReader
                        if (reader != null) {
                            while (true) {
                                val img = reader.acquireNextImage() ?: break
                                img.close()
                            }
                        }
                        continue
                    }

                    // Small delay to let Surface render
                    Thread.sleep(10)

                    // Check ImageReader for a frame
                    val reader = imageReader ?: continue
                    val image = reader.acquireNextImage()
                    if (image != null) {
                        Log.d(TAG, "ImageReader image: format=${image.format} w=${image.width} h=${image.height}")
                        val bitmap = yuvImageToBitmap(image)
                        image.close()
                        if (bitmap != null) return bitmap
                    }
                } else {
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outputFormat = codec.outputFormat
                Log.i(TAG, "Output format changed: $outputFormat")
            } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!extendedWait) {
                    // Check ImageReader even if no new output buffer
                    val reader = imageReader ?: return null
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        Log.d(TAG, "ImageReader image (from try-again): format=${image.format} w=${image.width} h=${image.height}")
                        val bitmap = yuvImageToBitmap(image)
                        image.close()
                        if (bitmap != null) return bitmap
                    }
                    return null
                }
            }
        }
        return null
    }

    private fun yuvImageToBitmap(image: Image): Bitmap? {
        try {
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

            Log.d(TAG, "YUV planes: y(ps=$yPixelStride rs=$yRowStride) u(ps=$uPixelStride rs=$uRowStride) yCap=${yBuf.remaining()} uCap=${uBuf.remaining()}")

            // Sample a few pixels for debugging
            val sy = yBuf.get(0).toInt() and 0xFF
            val su = if (uBuf.remaining() > 0) uBuf.get(0).toInt() and 0xFF else -1
            val sv = if (vBuf.remaining() > 0) vBuf.get(0).toInt() and 0xFF else -1
            Log.d(TAG, "Sample[0]: Y=$sy U=$su V=$sv")

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
            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "yuvImageToBitmap error: ${e.message}", e)
            return null
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
        extraHeaders: Map<String, String> = emptyMap(),
        body: String? = null
    ): String {
        val sb = StringBuilder()
        sb.append("$method $url RTSP/1.0\r\n")
        sb.append("CSeq: ${cSeq++}\r\n")

        // Add auth
        val auth = extractAuth(rtspUrl)
        if (auth != null) {
            sb.append("Authorization: Basic $auth\r\n")
        }

        for ((key, value) in extraHeaders) {
            sb.append("$key: $value\r\n")
        }

        if (body != null) {
            sb.append("Content-Length: ${body.toByteArray().size}\r\n")
        }

        sb.append("\r\n")

        if (body != null) {
            sb.append(body)
        }

        out.write(sb.toString().toByteArray())
        out.flush()

        return readRtspResponse(input)
    }

    private fun readRtspResponse(input: InputStream): String {
        val sb = StringBuilder()
        val buf = ByteArray(4096)
        var totalRead = 0

        while (true) {
            val b = input.read()
            if (b == -1) break
            sb.append(b.toChar())
            totalRead++

            // Check for end of headers
            if (sb.length >= 4 && sb.substring(sb.length - 4) == "\r\n\r\n") {
                // Check for Content-Length
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

            if (totalRead > 65536) break
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
        private const val TAG = "DirectRtspSnapshot"

        fun extractIp(url: String): String {
            return try {
                val uri = java.net.URI(url)
                uri.host ?: url
            } catch (_: Exception) {
                url.substringAfter("://").substringBefore(":").substringBefore("/")
            }
        }

        fun extractPort(url: String): Int {
            return try {
                val uri = java.net.URI(url)
                uri.port.takeIf { it > 0 } ?: 554
            } catch (_: Exception) {
                554
            }
        }

        fun extractAuth(url: String): String? {
            return try {
                val uri = java.net.URI(url)
                val userInfo = uri.userInfo ?: return null
                android.util.Base64.encodeToString(userInfo.toByteArray(), android.util.Base64.NO_WRAP)
            } catch (_: Exception) {
                null
            }
        }
    }
}
