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
  const val FLIP_360 = 1 shl 6
  const val UNLOCK_MOTORS = 1 shl 7
  const val FAST_FLY = 1 shl 8
  const val SD_CAPTURE_PULSE = 1 shl 9
  const val SD_RECORD_LATCH = 1 shl 10
}

data class NativeSessionCounters(
  val running: Boolean,
  val connected: Boolean,
  val isSynthetic: Boolean,
  val packetsSent: Long,
  val packetsReceived: Long,
  val droppedPackets: Long,
  val lastRxTimestampMs: Long,
  val batteryPercent: Int? = null,
  val firmwareStatusFlags: Int? = null,
)

data class NativeVideoFrameMeta(
  val sequence: Long,
  val timestampUs: Long,
  val transportKind: Int,
  val width: Int,
  val height: Int,
  val byteLength: Int,
  val isSynthetic: Boolean,
  val isKeyFrame: Boolean = true,
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
        batteryPercent = null,
        firmwareStatusFlags = null,
      )
    }
    val arr = nativeReadSessionCounters(handle)
    val batteryRaw = if (arr.size > 7) arr[7].toInt() else -1
    val statusRaw = if (arr.size > 8) arr[8].toInt() else -1
    return NativeSessionCounters(
      running = arr[0] != 0L,
      connected = arr[1] != 0L,
      isSynthetic = arr[2] != 0L,
      packetsSent = arr[3],
      packetsReceived = arr[4],
      droppedPackets = arr[5],
      lastRxTimestampMs = arr[6],
      batteryPercent = batteryRaw.takeIf { it in 0..100 },
      firmwareStatusFlags = statusRaw.takeIf { it >= 0 },
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
      isKeyFrame = if (metaOut.size > 7) metaOut[7] != 0L else true,
    )
  }

  external fun nativeCreateSession(
    familyWireId: Int,
    transportKind: Int,
    hostIp: String,
    controlPort: Int,
    videoPort: Int,
    heartbeatPort: Int,
    rtspPath: String,
    controlRateHz: Int,
    networkHandle: Long,
    allowSyntheticHarness: Boolean,
    rtspAllowUdpFallback: Boolean = true,
    rtspPreferUdp: Boolean = false,
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

  external fun nativeTriggerFlip(handle: Long, direction: Int)

  external fun nativeSetLatchedAction(handle: Long, actionFlag: Int, enabled: Boolean)

  external fun nativeRequestKeyframe(handle: Long)

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
