package com.homeassisthub.hub.api

import com.homeassisthub.hub.api.dto.ApiErrorDto
import com.homeassisthub.hub.api.dto.ApiHealthDto
import com.homeassisthub.hub.api.dto.DeviceCredentialDto
import com.homeassisthub.hub.api.dto.toDomain
import com.homeassisthub.hub.api.dto.toDto
import com.homeassisthub.hub.api.dto.toSummaryDto
import com.homeassisthub.hub.data.db.P1Dao
import com.homeassisthub.hub.discovery.DiscoveryManager
import com.homeassisthub.hub.security.SecureCredentialStore
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * Local-network REST API (Ktor/Netty) used by the Android Client app's
 * Settings screen when it is on the same LAN as the Hub: device
 * discovery, credential management and P1 meter history for charts.
 *
 * This is intentionally separate from the Socket.IO cloud bridge
 * ([com.homeassisthub.hub.bridge.HubSocketClient]), which handles
 * remote command/response + WebRTC signaling.
 */
class HubApiServer(
    private val discoveryManager: DiscoveryManager,
    private val credentialStore: SecureCredentialStore,
    private val p1Dao: P1Dao,
    private val port: Int = DEFAULT_PORT
) {

    private var engine: ApplicationEngine? = null
    private val startTimeMs = System.currentTimeMillis()

    fun start() {
        if (engine != null) return
        engine = embeddedServer(Netty, port = port) {
            install(ContentNegotiation) { json() }

            routing {
                get("/api/v1/health") {
                    call.respond(ApiHealthDto("ok", System.currentTimeMillis() - startTimeMs))
                }

                get("/api/v1/devices/discover") {
                    val timeoutMs = call.request.queryParameters["timeoutMs"]?.toLongOrNull() ?: 3000L
                    val devices = discoveryManager.discoverAll(timeoutMs)
                    call.respond(devices.map { it.toDto() })
                }

                get("/api/v1/devices") {
                    call.respond(credentialStore.getAllCredentials().map { it.toSummaryDto() })
                }

                post("/api/v1/devices") {
                    val body = runCatching { call.receive<DeviceCredentialDto>() }.getOrNull()
                    if (body == null || body.deviceId.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ApiErrorDto("Invalid device payload"))
                        return@post
                    }
                    credentialStore.saveCredential(body.toDomain())
                    call.respond(HttpStatusCode.Created, body.toDomain().toSummaryDto())
                }

                delete("/api/v1/devices/{deviceId}") {
                    val deviceId = call.parameters["deviceId"]
                    if (deviceId.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ApiErrorDto("Missing deviceId"))
                        return@delete
                    }
                    credentialStore.removeCredential(deviceId)
                    call.respond(HttpStatusCode.NoContent)
                }

                get("/api/v1/p1/history") {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                    call.respond(p1Dao.getRecent(limit).map { it.toDto() })
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(GRACE_PERIOD_MS, TIMEOUT_MS)
        engine = null
    }

    companion object {
        const val DEFAULT_PORT = 8080
        private const val GRACE_PERIOD_MS = 1_000L
        private const val TIMEOUT_MS = 2_000L
    }
}
