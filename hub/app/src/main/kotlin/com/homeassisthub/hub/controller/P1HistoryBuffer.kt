package com.homeassisthub.hub.controller

import java.util.ArrayDeque
import java.util.Calendar
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe ring buffer that stores the last 10 minutes of P1 meter readings.
 *
 * The Huawei Kiosk API has ~5 min delay vs the real-time P1 meter.
 * To compute an accurate "House Consumption", we need the P1 reading from
 * T-5min that temporally aligns with the delayed inverter data.
 *
 * Also stores the midnight kWh baseline for daily delta calculations.
 */
object P1HistoryBuffer {

    data class P1Snapshot(
        val timestamp: Long,
        val powerImportW: Double,
        val powerExportW: Double,
        val importTotalKwh: Double,
        val exportTotalKwh: Double
    ) {
        /** Net grid power: positive = importing, negative = exporting. */
        val netGridW: Double get() = powerImportW - powerExportW
    }

    private val BUFFER_MAX_AGE_MS = 600_000L // 10 minutes
    private val lock = ReentrantReadWriteLock()
    private val deque = ArrayDeque<P1Snapshot>()

    @Volatile
    var latestSnapshot: P1Snapshot? = null
        private set

    /** The first P1 reading on the current calendar day — used as baseline for daily kWh deltas. */
    @Volatile
    private var midnightBaseline: P1Snapshot? = null

    @Volatile
    private var baselineDay: Int = -1

    /**
     * Called by [P1MeterController] on every successful poll.
     * Adds the reading, evicts old entries, and updates the midnight baseline.
     */
    fun add(snapshot: P1Snapshot) {
        lock.write {
            deque.addLast(snapshot)
            latestSnapshot = snapshot

            // Track the first reading of each calendar day as the midnight baseline
            val cal = Calendar.getInstance().apply { timeInMillis = snapshot.timestamp }
            val today = cal.get(Calendar.DAY_OF_YEAR)
            if (today != baselineDay) {
                baselineDay = today
                midnightBaseline = snapshot
                android.util.Log.i("P1HistoryBuffer", "New midnight baseline: import=${snapshot.importTotalKwh}kWh export=${snapshot.exportTotalKwh}kWh")
            }

            val cutoff = snapshot.timestamp - BUFFER_MAX_AGE_MS
            while (deque.isNotEmpty() && deque.peekFirst().timestamp < cutoff) {
                deque.pollFirst()
            }
        }
    }

    /**
     * Finds the P1 snapshot closest to [targetTimestamp] within [toleranceMs].
     * Used by [HuaweiCloudScraper] to get the T-5min P1 reading that aligns
     * with the delayed inverter data.
     */
    fun findClosest(targetTimestamp: Long, toleranceMs: Long = 120_000L): P1Snapshot? {
        return lock.read {
            if (deque.isEmpty()) return@read null
            var best: P1Snapshot? = null
            var bestDiff = Long.MAX_VALUE
            for (snap in deque) {
                val diff = kotlin.math.abs(snap.timestamp - targetTimestamp)
                if (diff < bestDiff && diff <= toleranceMs) {
                    bestDiff = diff
                    best = snap
                }
            }
            best
        }
    }

    /**
     * Convenience: find the P1 reading from approximately [minutesAgo] ago.
     */
    fun findMinutesAgo(minutesAgo: Int): P1Snapshot? {
        val target = System.currentTimeMillis() - minutesAgo * 60_000L
        return findClosest(target)
    }

    /**
     * Restores the midnight baseline from a persisted reading (e.g. from DB)
     * when the app restarts mid-day and the in-memory baseline was lost.
     * Only sets it if we don't already have one for today.
     */
    fun restoreBaselineIfNeeded(
        timestamp: Long,
        importTotalKwh: Double,
        exportTotalKwh: Double
    ) {
        lock.write {
            val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
            val day = cal.get(Calendar.DAY_OF_YEAR)
            if (day == baselineDay && midnightBaseline != null) return@write // already have it
            baselineDay = day
            midnightBaseline = P1Snapshot(
                timestamp = timestamp,
                powerImportW = 0.0,
                powerExportW = 0.0,
                importTotalKwh = importTotalKwh,
                exportTotalKwh = exportTotalKwh
            )
            android.util.Log.i("P1HistoryBuffer", "Restored baseline from DB: import=${importTotalKwh}kWh export=${exportTotalKwh}kWh ts=$timestamp")
        }
    }

    /**
     * Returns the daily import/export kWh deltas computed from the midnight baseline.
     * If no baseline exists yet (first reading of the day), returns the current cumulative values.
     */
    fun getDailyKwhDeltas(): Pair<Double, Double> {
        val baseline = midnightBaseline
        val latest = latestSnapshot
        if (baseline == null || latest == null) return 0.0 to 0.0
        val dailyImport = maxOf(0.0, latest.importTotalKwh - baseline.importTotalKwh)
        val dailyExport = maxOf(0.0, latest.exportTotalKwh - baseline.exportTotalKwh)
        return dailyImport to dailyExport
    }

    fun clear() {
        lock.write { deque.clear() }
        latestSnapshot = null
        midnightBaseline = null
        baselineDay = -1
    }
}

