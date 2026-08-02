package com.homeassisthub.hub.controller

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe in-memory ring buffer for Hub log entries.
 * Captured from key service components (CloudSync, P1 poller, Kiosk scraper,
 * Socket.IO, daily summary worker) so the Hub dashboard can display them
 * in real time without needing logcat.
 */
object HubLogBuffer {

    data class LogEntry(
        val timestamp: Long,
        val tag: String,
        val level: Level,
        val message: String
    )

    enum class Level(val label: String) {
        INFO("I"), WARN("W"), ERROR("E"), DEBUG("D")
    }

    private const val MAX_ENTRIES = 200
    private val lock = ReentrantReadWriteLock()
    private val deque = ArrayDeque<LogEntry>()

    @Volatile
    var latestEntries: List<LogEntry> = emptyList()
        private set

    fun log(tag: String, level: Level, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), tag, level, message)
        lock.write {
            deque.addLast(entry)
            while (deque.size > MAX_ENTRIES) deque.pollFirst()
            latestEntries = deque.toList()
        }
    }

    fun i(tag: String, msg: String) = log(tag, Level.INFO, msg)
    fun w(tag: String, msg: String) = log(tag, Level.WARN, msg)
    fun e(tag: String, msg: String) = log(tag, Level.ERROR, msg)
    fun d(tag: String, msg: String) = log(tag, Level.DEBUG, msg)

    fun clear() {
        lock.write {
            deque.clear()
            latestEntries = emptyList()
        }
    }
}
