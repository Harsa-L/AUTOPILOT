package com.autopilot.core.common

import java.util.concurrent.ConcurrentLinkedDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LogLevel {
  DEBUG,
  INFO,
  WARN,
  ERROR,
}

enum class SubsystemTag {
  CONNECTIVITY,
  NATIVE_BRIDGE,
  PROTOCOL,
  FLIGHT_SAFETY,
  VIDEO,
  VISION,
  THEME_RUNTIME,
  PERSISTENCE,
}

data class DiagnosticLogEntry(
  val id: Long,
  val timestampMs: Long,
  val level: LogLevel,
  val tag: SubsystemTag,
  val message: String,
)

object AutopilotLog {
  private const val MAX_ENTRIES = 250
  private val buffer = ConcurrentLinkedDeque<DiagnosticLogEntry>()
  private var nextId = 1L
  private val _entries = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())
  val entries: StateFlow<List<DiagnosticLogEntry>> = _entries.asStateFlow()

  fun log(level: LogLevel, tag: SubsystemTag, message: String) {
    val entry = DiagnosticLogEntry(
      id = nextId++,
      timestampMs = System.currentTimeMillis(),
      level = level,
      tag = tag,
      message = message,
    )
    buffer.addFirst(entry)
    while (buffer.size > MAX_ENTRIES) {
      buffer.pollLast()
    }
    _entries.value = buffer.toList()
  }

  fun info(tag: SubsystemTag, message: String) = log(LogLevel.INFO, tag, message)

  fun warn(tag: SubsystemTag, message: String) = log(LogLevel.WARN, tag, message)

  fun error(tag: SubsystemTag, message: String) = log(LogLevel.ERROR, tag, message)

  fun debug(tag: SubsystemTag, message: String) = log(LogLevel.DEBUG, tag, message)

  fun clear() {
    buffer.clear()
    _entries.value = emptyList()
  }
}
