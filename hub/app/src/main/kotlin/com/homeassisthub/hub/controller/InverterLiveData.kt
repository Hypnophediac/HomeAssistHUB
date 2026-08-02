package com.homeassisthub.hub.controller

/**
 * Thread-safe holder for the most recent Huawei SUN2000 inverter reading.
 * Updated by [HuaweiCloudScraper]'s poll loop and read by
 * [com.homeassisthub.hub.bridge.CommandRouter] to merge inverter + P1
 * meter data into a single "real house consumption" figure.
 *
 * The Kiosk API has ~5 min delay, so [realConsumptionW] is computed
 * using a T-5min P1 reading from [P1HistoryBuffer] for temporal alignment.
 */
object InverterLiveData {

    @Volatile
    var activePowerW: Double = 0.0
        private set

    @Volatile
    var lastUpdateMs: Long = 0L
        private set

    /** Cached house consumption computed from synchronized P1 (T-5min) + inverter data. */
    @Volatile
    var realConsumptionW: Double = 0.0
        private set

    /** Daily yield from Huawei Kiosk API (kWh). */
    @Volatile
    var dailyEnergyKwh: Double = 0.0
        private set

    fun update(powerW: Double) {
        activePowerW = powerW
        lastUpdateMs = System.currentTimeMillis()
    }

    /** Called by [HuaweiCloudScraper] after computing synchronized house consumption. */
    fun updateRealConsumption(consumptionW: Double, dailyKwh: Double) {
        realConsumptionW = consumptionW
        dailyEnergyKwh = dailyKwh
    }

    /** True if we have a reading from the last 30 minutes.
     *  The Kiosk API can be temporarily unreachable (DNS, maintenance), so
     *  we keep showing the last known inverter value for up to 30 minutes
     *  instead of blanking it out after 6 minutes. */
    fun isFresh(): Boolean = lastUpdateMs > 0L && (System.currentTimeMillis() - lastUpdateMs) < 1_800_000L
}

