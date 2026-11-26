package com.autopilot.nativecore

import com.autopilot.core.common.AutopilotLog
import com.autopilot.core.common.SubsystemTag
import java.nio.ByteBuffer

object NativeActionFlags {
  const val TAKEOFF = 1 shl 0
  const val CONTROLLED_LAND = 1 shl 1
  const val MOTOR_STOP = 1 shl 2
  const val EMERGENCY_STOP = 1 shl 3
  const val CALIBRATE_GYRO = 1 shl 4
  const val HEADLESS = 1 shl 5
}

data class NativeSessionCounters(
  val running: Boolean,
  val connected: Boolean,
  val isSynthetic: Boolean,
  val packetsSent: Long,
  val packetsReceived: Long,
  val droppedPackets: Long,
  val lastRxTimestampMs: Long,
)

data class NativeVideoFrameMeta(
  val sequence: Long,
  val timestampUs: Long,
  val transportKind: Int,
  val width: Int,
  val height: Int,
  val byteLength: Int,
  val isSynthetic: Boolean,
)

/**
 * Coarse-grained JNI boundary wrapping libautopilot_native.so.
 * Safely tracks library availability so JVM unit tests on desktop hosts can
 * query [isNativeLoaded] cleanly without crashing.
 */
object AutopilotNativeBridge {

  @Volatile
  var isNativeLoaded: Boolean = false
    private set

  init {
    try {
      System.loadLibrary("autopilot_native")
      isNativeLoaded = true
      AutopilotLog.info(SubsystemTag.NATIVE_BRIDGE, "Loaded libautopilot_native.so (C++20 NDK core)")
    } catch (t: UnsatisfiedLinkError) {
      isNativeLoaded = false
      AutopilotLog.warn(
        SubsystemTag.NATIVE_BRIDGE,
        "Native library unavailable in current host environment: ${t.message}",
      )
    }
  }

  fun readSessionCounters(handle: Long): NativeSessionCounters {
    if (!isNativeLoaded || handle == 0L) {
      return NativeSessionCounters(
        running = false,
        connected = false,
        isSynthetic = false,
        packetsSent = 0L,
        packetsReceived = 0L,
        droppedPackets = 0L,
        lastRxTimestampMs = 0L,
      )
    }
    val arr = nativeReadSessionCounters(handle)
    return NativeSessionCounters(
      running = arr[0] != 0L,
      connected = arr[1] != 0L,
      isSynthetic = arr[2] != 0L,
      packetsSent = arr[3],
      packetsReceived = arr[4],
      droppedPackets = arr[5],
      lastRxTimestampMs = arr[6],
    )
  }

  fun popVideoFrame(
    handle: Long,
    directBuffer: ByteBuffer,
    metaOut: LongArray,
  ): NativeVideoFrameMeta? {
    if (!isNativeLoaded || handle == 0L) return null
    val ok = nativePopVideoFrame(handle, directBuffer, metaOut)
    if (!ok) return null
    return NativeVideoFrameMeta(
      sequence = metaOut[0],
      timestampUs = metaOut[1],
      transportKind = metaOut[2].toInt(),
      width = metaOut[3].toInt(),
      height = metaOut[4].toInt(),
      byteLength = metaOut[5].toInt(),
      isSynthetic = metaOut[6] != 0L,
    )
  }

  external fun nativeCreateSession(
    familyWireId: Int,
    transportKind: Int,
    hostIp: String,
    controlPort: Int,
    videoPort: Int,
    heartbeatPort: Int,
    controlRateHz: Int,
    networkHandle: Long,
    allowSyntheticHarness: Boolean,
  ): Long

  external fun nativeStartSession(handle: Long): Boolean

  external fun nativeStopSession(handle: Long)

  external fun nativeDestroySession(handle: Long)

  external fun nativeUpdateSticks(
    handle: Long,
    roll: Float,
    pitch: Float,
    throttle: Float,
    yaw: Float,
    trimRoll: Float,
    trimPitch: Float,
    speedTier: Int,
    cameraTilt: Int,
  )

  external fun nativeTriggerAction(handle: Long, actionFlag: Int)

  external fun nativeClearActions(handle: Long)

  external fun nativeReadSessionCounters(handle: Long): LongArray

  external fun nativeLastCommandHex(handle: Long): String

  external fun nativePopVideoFrame(
    handle: Long,
    directBuffer: ByteBuffer,
    outMeta: LongArray,
  ): Boolean

  external fun nativeEncodePacketPreview(
    familyWireId: Int,
    roll: Float,
    pitch: Float,
    throttle: Float,
    yaw: Float,
    actionFlags: Int,
    speedTier: Int,
    cameraTilt: Int,
  ): ByteArray

  external fun nativeCreateVisionEngine(): Long

  external fun nativeResetVisionEngine(handle: Long)

  external fun nativeDestroyVisionEngine(handle: Long)

  external fun nativeProcessVisionFrame(
    handle: Long,
    rgb24DirectBuffer: ByteBuffer?,
    width: Int,
    height: Int,
    detectionsPacked: FloatArray,
    focalLengthPx: Float,
    isCalibrated: Boolean,
  ): FloatArray
}
