package com.autopilot.core.common

object ByteFraming {
  fun xorChecksum(bytes: ByteArray, start: Int = 0, endExclusive: Int = bytes.size): Byte {
    var acc = 0
    for (i in start until endExclusive) {
      acc = acc xor (bytes[i].toInt() and 0xFF)
    }
    return (acc and 0xFF).toByte()
  }

  fun toHexString(bytes: ByteArray, maxBytes: Int = 64): String {
    val count = minOf(bytes.size, maxBytes)
    val sb = StringBuilder(count * 3)
    for (i in 0 until count) {
      if (i > 0) sb.append(' ')
      sb.append("%02X".format(bytes[i].toInt() and 0xFF))
    }
    if (bytes.size > maxBytes) {
      sb.append(" …(+${bytes.size - maxBytes}B)")
    }
    return sb.toString()
  }
}
