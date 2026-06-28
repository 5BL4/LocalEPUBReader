package com.epubreader.app.core.log

/**
 * A single log entry captured by [AppLogger].
 *
 * Stored in an in-memory ring buffer for display in the log viewer screen.
 * The [throwableStackTrace] is populated when a [Throwable] is passed to
 * [AppLogger.e] or [AppLogger.w].
 */
data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwableStackTrace: String? = null
)

/**
 * Log severity level, matching Android's `android.util.Log` constants.
 */
enum class LogLevel(val priority: Int, val label: String) {
    V(2, "V"),
    D(3, "D"),
    I(4, "I"),
    W(5, "W"),
    E(6, "E");

    companion object {
        /** Returns the level with the given label, or [D] if not found. */
        fun fromLabel(label: String): LogLevel =
            entries.find { it.label == label } ?: D
    }
}
