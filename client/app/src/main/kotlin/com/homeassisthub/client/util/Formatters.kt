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

/**
 * Formats a power value (W) for display: values >= 1000 W are shown in
 * kW with 2 decimal places, smaller values are shown in whole watts
 * (e.g. 850 W -> "850 W", 2680 W -> "2.68 kW").
 */
fun formatW(w: Double): String {
    return if (abs(w) < 1000.0) {
        "${w.toInt()} W"
    } else {
        "%.2f kW".format(w / 1000.0)
    }
}
