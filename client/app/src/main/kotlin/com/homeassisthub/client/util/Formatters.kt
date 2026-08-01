package com.homeassisthub.client.util

import kotlin.math.abs

/**
 * Formats an energy value (kWh) for display: values >= 1 kWh are shown in
 * kWh with [decimals] decimal places, smaller values are shown in Wh so
 * near-zero readings stay readable (e.g. 0.85 kWh -> "850 Wh").
 */
fun formatKwh(kwh: Double, decimals: Int = 2): String {
    return if (abs(kwh) < 1.0) {
        "${(kwh * 1000).toInt()} Wh"
    } else {
        "%.${decimals}f kWh".format(kwh)
    }
}
