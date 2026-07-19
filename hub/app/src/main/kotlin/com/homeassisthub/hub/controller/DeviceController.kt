package com.homeassisthub.hub.controller

/**
 * Common contract for every local device integration (P1 meter, smart
 * plug, V380 PTZ camera, ...). Implementations MUST perform all network
 * I/O on [kotlinx.coroutines.Dispatchers.IO] and must never throw out of
 * [executeCommand]; failures are reported via [CommandResult.Failure].
 */
interface DeviceController {

    /** Stable identifier matching the id used in [com.homeassisthub.hub.security.DeviceCredential]. */
    val deviceId: String

    /**
     * Executes a single command against the device (e.g. "turn_on", "pan_left").
     * [params] carries any extra arguments the action needs (e.g. speed, duration).
     */
    suspend fun executeCommand(action: String, params: Map<String, String> = emptyMap()): CommandResult
}

sealed class CommandResult {
    data class Success(val data: Map<String, Any?> = emptyMap()) : CommandResult()
    data class Failure(val error: String) : CommandResult()
}
