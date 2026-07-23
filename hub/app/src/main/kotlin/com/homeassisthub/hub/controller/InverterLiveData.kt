package com.homeassisthub.hub.controller

/**
 * Thread-safe holder for the most recent Huawei SUN2000 inverter reading.
 * Updated by [HuaweiInverterController]'s poll loop and read by
 * [com.homeassisthub.hub.bridge.CommandRouter] to merge inverter + P1
 * meter data into a single "real house consumption" figure:
 *
 *   RealConsumptionW = InverterProductionW - P1ExportW + P1ImportW
 */
object InverterLiveData {

    @Volatile
    var activePowerW: Double = 0.0
        private set

    @Volatile
    var lastUpdateMs: Long = 0L
        private set

    fun update(powerW: Double) {
        activePowerW = powerW
        lastUpdateMs = System.currentTimeMillis()
    }

    /** True if we have a reading from the last 2 minutes; stale data is ignored by consumers. */
    fun isFresh(): Boolean = lastUpdateMs > 0L && (System.currentTimeMillis() - lastUpdateMs) < 120_000L
}
