package org.openlist.mobile.data.logging

import kotlinx.coroutines.flow.StateFlow

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/** Messages exposed here have already passed through [LogSanitizer]. */
data class LogEntry(
    val id: Long,
    val timestampMillis: Long,
    val level: LogLevel,
    val module: String,
    val message: String,
)

fun interface LogClock {
    fun nowMillis(): Long
}

object SystemLogClock : LogClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

data class LogRetention(
    val maxEntries: Int = 1_000,
    val maxFileBytes: Long = 512L * 1_024,
    val maxMessageChars: Int = 4_000,
    val maxModuleChars: Int = 80,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxFileBytes in 1..Int.MAX_VALUE.toLong()) {
            "maxFileBytes must be between 1 and Int.MAX_VALUE"
        }
        require(maxMessageChars > 0) { "maxMessageChars must be positive" }
        require(maxModuleChars > 0) { "maxModuleChars must be positive" }
    }
}

/** Small synchronous logging API. Persistence implementations must sanitize before storage. */
interface AppLogger {
    val logs: StateFlow<List<LogEntry>>

    /** False indicates in-memory logging is working but the most recent disk operation failed. */
    val persistenceHealthy: StateFlow<Boolean>

    fun log(
        level: LogLevel,
        module: String,
        message: String,
        throwable: Throwable? = null,
    ): LogEntry

    fun clear()

    fun snapshot(): List<LogEntry> = logs.value

    fun debug(module: String, message: String) = log(LogLevel.DEBUG, module, message)

    fun info(module: String, message: String) = log(LogLevel.INFO, module, message)

    fun warn(module: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.WARN, module, message, throwable)

    fun error(module: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.ERROR, module, message, throwable)
}
