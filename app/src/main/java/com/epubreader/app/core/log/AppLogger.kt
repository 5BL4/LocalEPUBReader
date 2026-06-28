package com.epubreader.app.core.log

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app logger that mirrors `android.util.Log` but also stores entries in an
 * in-memory ring buffer for viewing/exporting within the app.
 *
 * Designed for devices where system logcat is inaccessible (e.g. Honor phones
 * with encrypted logcat). Every call delegates to `android.util.Log` first
 * (so standard logcat still works on other devices), then appends to the buffer.
 *
 * Thread-safe: [MutableStateFlow.update] is atomic. The ring buffer is capped
 * at [MAX_ENTRIES] entries; oldest entries are dropped when the cap is reached.
 *
 * Usage — drop-in replacement for `android.util.Log`:
 * ```
 * import com.epubreader.app.core.log.AppLogger
 * AppLogger.d("Tag", "message")
 * AppLogger.e("Tag", "error", throwable)
 * ```
 */
object AppLogger {

    /** Maximum number of entries retained in the ring buffer. */
    private const val MAX_ENTRIES = 2000

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // -- Debug --

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        addEntry(LogLevel.D, tag, msg, null)
    }

    fun d(tag: String, msg: String, tr: Throwable) {
        Log.d(tag, msg, tr)
        addEntry(LogLevel.D, tag, msg, tr)
    }

    // -- Info --

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        addEntry(LogLevel.I, tag, msg, null)
    }

    fun i(tag: String, msg: String, tr: Throwable) {
        Log.i(tag, msg, tr)
        addEntry(LogLevel.I, tag, msg, tr)
    }

    // -- Warn --

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        addEntry(LogLevel.W, tag, msg, null)
    }

    fun w(tag: String, msg: String, tr: Throwable) {
        Log.w(tag, msg, tr)
        addEntry(LogLevel.W, tag, msg, tr)
    }

    fun w(tag: String, tr: Throwable) {
        Log.w(tag, tr)
        addEntry(LogLevel.W, tag, tr.message ?: tr.javaClass.simpleName, tr)
    }

    // -- Error --

    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
        addEntry(LogLevel.E, tag, msg, null)
    }

    fun e(tag: String, msg: String, tr: Throwable) {
        Log.e(tag, msg, tr)
        addEntry(LogLevel.E, tag, msg, tr)
    }

    // -- Verbose --

    fun v(tag: String, msg: String) {
        Log.v(tag, msg)
        addEntry(LogLevel.V, tag, msg, null)
    }

    // -- Buffer management --

    /** Clears all stored log entries. */
    fun clear() {
        _entries.value = emptyList()
    }

    /**
     * Exports all log entries as formatted plain text, suitable for saving
     * to a file or sharing. Each line: `HH:mm:ss.SSS  LEVEL/Tag: message`
     * Stack traces (if any) are appended on subsequent lines.
     */
    fun exportText(): String {
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        for (entry in _entries.value) {
            sb.append(sdf.format(Date(entry.timestamp)))
            sb.append("  ")
            sb.append(entry.level.label)
            sb.append('/')
            sb.append(entry.tag)
            sb.append(": ")
            sb.append(entry.message)
            sb.append('\n')
            entry.throwableStackTrace?.let { trace ->
                sb.append(trace)
                if (!trace.endsWith('\n')) sb.append('\n')
            }
        }
        return sb.toString()
    }

    // -- Internal --

    private fun addEntry(level: LogLevel, tag: String, msg: String, tr: Throwable?) {
        val trace = tr?.let { throwableToString(it) }
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = msg,
            throwableStackTrace = trace
        )
        _entries.update { list ->
            val newList = if (list.size >= MAX_ENTRIES) {
                list.drop(list.size - MAX_ENTRIES + 1)
            } else {
                list
            }
            newList + entry
        }
    }

    private fun throwableToString(tr: Throwable): String {
        val sw = StringWriter()
        tr.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
