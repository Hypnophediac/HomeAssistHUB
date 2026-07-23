package com.homeassisthub.hub.controller

import android.util.Log
import com.homeassisthub.hub.security.DeviceCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Polls a Huawei SUN2000 solar inverter over Modbus TCP for its current
 * active power output — register 32080, 2x16-bit signed I32, gain 1
 * (unit: W), per Huawei's published SUN2000 Modbus register map (the same
 * register used by Home Assistant's `huawei_solar` integration).
 *
 * This implements the minimal Modbus TCP protocol (MBAP header + function
 * code 0x03 "Read Holding Registers") directly over a plain socket instead
 * of pulling in a third-party Modbus library: most mature Java Modbus
 * libraries (e.g. j2mod) transitively depend on native serial-port code
 * (RXTX/nrjavaserial) that is fragile to package on Android and is
 * completely unused here since we only ever talk Modbus **TCP**. The
 * protocol itself is ~30 lines and fully self-contained, so a hand-rolled
 * client is the more robust choice for this app.
 */
class HuaweiInverterController(
    private val credential: DeviceCredential,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 10_000L
) : DeviceController {

    override val deviceId: String = credential.deviceId
    private var pollingJob: Job? = null

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        Log.i(TAG, "Starting Huawei inverter polling for ${credential.ipAddress}:${credential.port}")
        pollingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                fetchOnce()
                delay(pollIntervalMs)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    suspend fun fetchOnce(): CommandResult = withContext(Dispatchers.IO) {
        runCatching {
            readActivePowerW(credential.ipAddress, credential.port)
        }.fold(
            onSuccess = { powerW ->
                InverterLiveData.update(powerW)
                Log.i(TAG, "Inverter active power: ${powerW}W")
                CommandResult.Success(mapOf("activePowerW" to powerW))
            },
            onFailure = { throwable ->
                Log.e(TAG, "Inverter read failed: ${throwable.message}", throwable)
                CommandResult.Failure(throwable.message ?: "Unknown inverter error")
            }
        )
    }

    override suspend fun executeCommand(action: String, params: Map<String, String>): CommandResult {
        return when (action) {
            "refresh" -> fetchOnce()
            else -> CommandResult.Failure("Unsupported action '$action' for Huawei inverter")
        }
    }

    /** Reads the "Active power" register (32080, 2x16-bit, signed I32, gain 1 => watts) via raw Modbus TCP. */
    private fun readActivePowerW(host: String, port: Int): Double {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            val unitId = 1
            val quantity = 2 // 2x16-bit registers = 1 I32 value

            // MBAP header (7 bytes) + PDU (unitId is part of MBAP, function code + start addr + qty = 5 bytes of PDU)
            val request = ByteArrayOutputStream().apply {
                writeShort(TRANSACTION_ID)      // Transaction ID
                writeShort(0)                   // Protocol ID (always 0 for Modbus)
                writeShort(6)                   // Length: unitId + function + addr + qty = 6 bytes
                write(unitId)                   // Unit ID
                write(FUNCTION_READ_HOLDING_REGISTERS)
                writeShort(REGISTER_ACTIVE_POWER)
                writeShort(quantity)
            }.toByteArray()

            out.write(request)
            out.flush()

            // 7-byte MBAP header + 1-byte function code
            val headerAndFn = ByteArray(8)
            input.readFully(headerAndFn)
            val functionCode = headerAndFn[7].toInt() and 0xFF
            if (functionCode and 0x80 != 0) {
                error("Modbus exception response from inverter (function=$functionCode)")
            }

            val byteCount = input.readUnsignedByte()
            val data = ByteArray(byteCount)
            input.readFully(data)

            require(byteCount >= 4) { "Unexpected register byte count: $byteCount" }
            val raw = ((data[0].toInt() and 0xFF) shl 24) or
                ((data[1].toInt() and 0xFF) shl 16) or
                ((data[2].toInt() and 0xFF) shl 8) or
                (data[3].toInt() and 0xFF)
            return raw.toDouble() // gain = 1 => already in watts
        }
    }

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    companion object {
        private const val TAG = "HuaweiInverterCtrl"
        private const val REGISTER_ACTIVE_POWER = 32080
        private const val FUNCTION_READ_HOLDING_REGISTERS = 0x03
        private const val TRANSACTION_ID = 1
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 3_000
    }
}
