package com.autopilot.drone.protocols

import com.autopilot.core.common.AutopilotDispatchers
import com.autopilot.core.common.AutopilotLog
import com.autopilot.core.common.ByteFraming
import com.autopilot.core.common.SubsystemTag
import com.autopilot.drone.protocolapi.ConnectionConfig
import com.autopilot.drone.protocolapi.ConnectionState
import com.autopilot.drone.protocolapi.DroneCapabilities
import com.autopilot.drone.protocolapi.DroneProtocol
import com.autopilot.drone.protocolapi.DroneTelemetry
import com.autopilot.drone.protocolapi.FlightCommand
import com.autopilot.drone.protocolapi.ProtocolDescriptor
import com.autopilot.drone.protocolapi.ProtocolFamily
import com.autopilot.drone.protocolapi.ProtocolRegistryEntry
import com.autopilot.drone.protocolapi.VideoFramePacket
import com.autopilot.drone.protocolapi.VideoTransportType
import com.autopilot.nativecore.AutopilotNativeBridge
import com.autopilot.nativecore.NativeActionFlags
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Isolated DroneProtocol adapter delegating high-frequency packet I/O and video reassembly
 * to the C++20 NDK NativeSession via coarse-grained JNI.
 */
class NativeDroneProtocolAdapter(
  private val registryEntry: ProtocolRegistryEntry,
  private val dispatchers: AutopilotDispatchers,
) : DroneProtocol {

  override val descriptor: ProtocolDescriptor = registryEntry.descriptor
  override val capabilities: DroneCapabilities = registryEntry.capabilities

  private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
  override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

  private val nativeHandle = AtomicLong(0L)
  private var speedTier: Int = 1
  private var lastRoll: Float = 0f
  private var lastPitch: Float = 0f
  private var lastThrottle: Float = 0f
  private var lastYaw: Float = 0f
  private var lastTrimRoll: Float = 0f
  private var lastTrimPitch: Float = 0f
  private var activeConfig: ConnectionConfig? = null

  override suspend fun connect(config: ConnectionConfig) =
    withContext(dispatchers.io) {
      if (descriptor.isSyntheticHarness && !config.allowSyntheticHarness) {
        _connectionState.value =
          ConnectionState.Error("Synthetic test harness is disabled outside isolated test mode.")
        return@withContext
      }
      disconnect()

      _connectionState.value = ConnectionState.Connecting(config.hostIp)
      activeConfig = config

      if (!AutopilotNativeBridge.isNativeLoaded) {
        _connectionState.value =
          ConnectionState.Error("C++20 native library libautopilot_native.so is not loaded.")
        return@withContext
      }

      val selectedTransport = config.videoTransportOverride ?: descriptor.videoTransport
      val resolvedRtspPath = config.rtspPath ?: descriptor.rtspPath ?: ""

      val handle =
        AutopilotNativeBridge.nativeCreateSession(
          familyWireId = descriptor.family.wireId,
          transportKind = selectedTransport.toNativeKind(),
          hostIp = config.hostIp,
          controlPort = config.controlPort,
          videoPort = config.videoPort,
          heartbeatPort = config.heartbeatPort ?: 0,
          rtspPath = resolvedRtspPath,
          controlRateHz = descriptor.controlRateHz,
          networkHandle = config.networkHandle ?: 0L,
          allowSyntheticHarness = config.allowSyntheticHarness,
          rtspAllowUdpFallback = config.rtspAllowUdpFallback,
          rtspPreferUdp = config.preferRtspUdp,
        )

      if (handle == 0L) {
        _connectionState.value = ConnectionState.Error("Failed to allocate native session.")
        return@withContext
      }

      val started = AutopilotNativeBridge.nativeStartSession(handle)
      if (!started) {
        AutopilotNativeBridge.nativeDestroySession(handle)
        _connectionState.value =
          ConnectionState.Error("Failed to open network sockets for ${descriptor.displayName}.")
        return@withContext
      }

      nativeHandle.set(handle)
      val now = System.currentTimeMillis()
      _connectionState.value = ConnectionState.Connected(config.hostIp, now)
      AutopilotLog.info(
        SubsystemTag.PROTOCOL,
        "Started ${descriptor.id} native session -> ${config.hostIp}:${config.controlPort} " +
          "(video=$selectedTransport, synthetic=${descriptor.isSyntheticHarness})",
      )
    }

  override suspend fun disconnect() =
    withContext(dispatchers.io) {
      val handle = nativeHandle.getAndSet(0L)
      if (handle != 0L && AutopilotNativeBridge.isNativeLoaded) {
        AutopilotNativeBridge.nativeStopSession(handle)
        AutopilotNativeBridge.nativeDestroySession(handle)
        AutopilotLog.info(SubsystemTag.PROTOCOL, "Stopped ${descriptor.id} native session.")
      }
      _connectionState.value = ConnectionState.Disconnected
    }

  override fun setControlArmed(armed: Boolean) {
    val handle = nativeHandle.get()
    if (handle != 0L && AutopilotNativeBridge.isNativeLoaded) {
      AutopilotNativeBridge.nativeSetControlArmed(handle, armed)
    }
  }

  override suspend fun sendCommand(command: FlightCommand) {
    val handle = nativeHandle.get()
    if (handle == 0L || !AutopilotNativeBridge.isNativeLoaded) return

    when (command) {
      is FlightCommand.StickAxes -> {
        lastRoll = command.roll
        lastPitch = command.pitch
        lastThrottle = command.throttle
        lastYaw = command.yaw
        lastTrimRoll = command.trimRoll
        lastTrimPitch = command.trimPitch
        AutopilotNativeBridge.nativeUpdateSticks(
          handle = handle,
          roll = command.roll,
          pitch = command.pitch,
          throttle = command.throttle,
          yaw = command.yaw,
          trimRoll = command.trimRoll,
          trimPitch = command.trimPitch,
          speedTier = speedTier,
          cameraTilt = 0,
        )
      }
      FlightCommand.Takeoff -> {
        if (capabilities.supportsTakeoff) {
          AutopilotNativeBridge.nativeTriggerAction(handle, NativeActionFlags.TAKEOFF)
        }
      }
      FlightCommand.ControlledLand -> {
        if (capabilities.supportsControlledLanding) {
          AutopilotNativeBridge.nativeTriggerAction(handle, NativeActionFlags.CONTROLLED_LAND)
        }
      }
      FlightCommand.MotorStop -> {
        if (capabilities.supportsMotorStop) {
          AutopilotNativeBridge.nativeTriggerAction(handle, NativeActionFlags.MOTOR_STOP)
        }
      }
      FlightCommand.EmergencyStop -> {
        if (capabilities.supportsEmergencyMotorStop) {
          AutopilotNativeBridge.nativeTriggerAction(handle, NativeActionFlags.EMERGENCY_STOP)
        }
      }
      FlightCommand.CalibrateGyro -> {
        if (capabilities.supportsGyroCalibration) {
          AutopilotNativeBridge.nativeTriggerAction(handle, NativeActionFlags.CALIBRATE_GYRO)
        }
      }
      is FlightCommand.SetSpeedTier -> {
        if (capabilities.supportsSpeedTiers) {
          speedTier = command.tier.coerceIn(0, 2)
          AutopilotNativeBridge.nativeUpdateSticks(
            handle = handle,
            roll = lastRoll,
            pitch = lastPitch,
            throttle = lastThrottle,
            yaw = lastYaw,
            trimRoll = lastTrimRoll,
            trimPitch = lastTrimPitch,
            speedTier = speedTier,
            cameraTilt = 0,
          )
        }
      }
      is FlightCommand.SetHeadlessMode -> {
        if (capabilities.supportsHeadlessMode) {
          AutopilotNativeBridge.nativeSetLatchedAction(
            handle,
            NativeActionFlags.HEADLESS,
            command.enabled,
          )
        }
      }
      is FlightCommand.CameraTilt -> {
        if (capabilities.supportsCameraTilt && command.delta != 0) {
          AutopilotNativeBridge.nativeUpdateSticks(
            handle = handle,
            roll = lastRoll,
            pitch = lastPitch,
            throttle = lastThrottle,
            yaw = lastYaw,
            trimRoll = lastTrimRoll,
            trimPitch = lastTrimPitch,
            speedTier = speedTier,
            cameraTilt = command.delta.coerceIn(-1, 1),
          )
        }
      }
      is FlightCommand.Flip360 -> {
        if (capabilities.supportsFlip) {
          AutopilotNativeBridge.nativeTriggerFlip(handle, command.direction)
        }
      }
      FlightCommand.UnlockMotors -> {
        if (capabilities.supportsUnlockCommand) {
          AutopilotNativeBridge.nativeTriggerAction(handle, NativeActionFlags.UNLOCK_MOTORS)
        }
      }
      is FlightCommand.SetFastFly -> {
        if (capabilities.supportsFastFly) {
          if (command.enabled) speedTier = 2
          AutopilotNativeBridge.nativeSetLatchedAction(
            handle,
            NativeActionFlags.FAST_FLY,
            command.enabled,
          )
        }
      }
      FlightCommand.TriggerFirmwarePhoto -> {
        if (capabilities.supportsFirmwareSdCapture) {
          AutopilotNativeBridge.nativeTriggerAction(handle, NativeActionFlags.SD_CAPTURE_PULSE)
        }
      }
      is FlightCommand.TriggerFirmwareRecord -> {
        if (capabilities.supportsFirmwareSdCapture) {
          AutopilotNativeBridge.nativeSetLatchedAction(
            handle,
            NativeActionFlags.SD_RECORD_LATCH,
            command.active,
          )
        }
      }
      is FlightCommand.SwitchCamera -> {
        if (capabilities.supportsCameraSwitch) {
          AutopilotNativeBridge.nativeSwitchCamera(handle, command.cameraIndex)
        }
      }
      FlightCommand.RequestKeyframe -> {
        AutopilotNativeBridge.nativeRequestKeyframe(handle)
      }
    }
  }

  override fun telemetry(): Flow<DroneTelemetry> =
    flow {
        while (currentCoroutineContext().isActive) {
          val handle = nativeHandle.get()
          if (handle != 0L && AutopilotNativeBridge.isNativeLoaded) {
            val counters = AutopilotNativeBridge.readSessionCounters(handle)
            val hex = AutopilotNativeBridge.nativeLastCommandHex(handle)
            val total = counters.packetsSent.coerceAtLeast(1L)
            val loss =
              if (counters.droppedPackets > 0L) {
                (counters.droppedPackets.toFloat() / total.toFloat()).coerceIn(0f, 1f)
              } else {
                0f
              }
            // Hardware-truth rule: fields remain null unless physically verified from RX telemetry.
            emit(
              DroneTelemetry(
                timestampMs = System.currentTimeMillis(),
                protocolId = descriptor.id,
                packetsSent = counters.packetsSent,
                packetsReceived = counters.packetsReceived,
                droppedPackets = counters.droppedPackets,
                linkRttMs = null,
                batteryPercent = counters.batteryPercent,
                rssiDbm = null,
                altitudeMeters = null,
                lastCommandHex = hex,
                isSyntheticHarness = counters.isSynthetic,
                firmwareStatusFlags = counters.firmwareStatusFlags,
                controlPacketsReceived = counters.controlPacketsReceived,
                videoPacketsReceived = counters.videoPacketsReceived,
                controlArmed = counters.controlArmed,
                lastSocketErrno = counters.lastSocketErrno,
              )
            )
            if (!counters.running && _connectionState.value is ConnectionState.Connected) {
              _connectionState.value = ConnectionState.ConnectionLost("Native worker exited.")
            }
            if (loss > 0.5f && counters.packetsSent > 50L) {
              AutopilotLog.warn(SubsystemTag.PROTOCOL, "High drop ratio on ${descriptor.id}: $loss")
            }
          }
          delay(100L)
        }
      }
      .flowOn(dispatchers.io)

  override fun videoFrames(): Flow<VideoFramePacket> =
    flow {
        val directBuffer = ByteBuffer.allocateDirect(512 * 1024)
        val metaOut = LongArray(8)
        while (currentCoroutineContext().isActive) {
          val handle = nativeHandle.get()
          if (handle != 0L && AutopilotNativeBridge.isNativeLoaded) {
            val meta = AutopilotNativeBridge.popVideoFrame(handle, directBuffer, metaOut)
            if (meta != null && meta.byteLength > 0) {
              directBuffer.position(0)
              directBuffer.limit(meta.byteLength)
              val copy = ByteArray(meta.byteLength)
              directBuffer.get(copy)
              directBuffer.clear()
              emit(
                VideoFramePacket(
                  sequence = meta.sequence,
                  timestampUs = meta.timestampUs,
                  transport = meta.transportKind.toVideoTransportType(),
                  width = meta.width,
                  height = meta.height,
                  payload = copy,
                  isKeyFrame = meta.isKeyFrame,
                  isSyntheticHarness = meta.isSynthetic,
                )
              )
            }
          }
          delay(12L)
        }
      }
      .flowOn(dispatchers.io)
}

/**
 * Pure-Kotlin mirror of C++20 binary packet framing used as diagnostic previewer
 * and JVM contract verification when libautopilot_native.so is not loaded on host JVM.
 */
object ProtocolPacketEncoder {

  fun encodeControlPacket(
    family: ProtocolFamily,
    roll: Float = 0f,
    pitch: Float = 0f,
    throttle: Float = 0f,
    yaw: Float = 0f,
    actionFlags: Int = 0,
    speedTier: Int = 1,
    cameraTilt: Int = 0,
  ): ByteArray {
    if (AutopilotNativeBridge.isNativeLoaded) {
      return AutopilotNativeBridge.nativeEncodePacketPreview(
        familyWireId = family.wireId,
        roll = roll,
        pitch = pitch,
        throttle = throttle,
        yaw = yaw,
        actionFlags = actionFlags,
        speedTier = speedTier,
        cameraTilt = cameraTilt,
      )
    }

    val r = axisToByte(roll)
    val p = axisToByte(pitch)
    val t = axisToByte(throttle)
    val y = axisToByte(yaw)
    val f = mapFlags(actionFlags)
    val effectiveSpeed =
      if ((actionFlags and NativeActionFlags.FAST_FLY) != 0) 2 else (speedTier and 0x03)

    return when (family) {
      ProtocolFamily.S2X ->
        byteArrayOf(
            0x66.toByte(),
            0x80.toByte(),
            r,
            p,
            t,
            y,
            f,
            effectiveSpeed.toByte(),
            (
              (if ((actionFlags and NativeActionFlags.FAST_FLY) != 0) 0x01 else 0x00) or
                (if ((actionFlags and NativeActionFlags.SD_CAPTURE_PULSE) != 0) 0x02 else 0x00) or
                (if ((actionFlags and NativeActionFlags.SD_RECORD_LATCH) != 0) 0x04 else 0x00)
            ).toByte(),
            0x00,
            0x99.toByte(),
          )
          .also { it[9] = ByteFraming.xorChecksum(it, 2, 9) }

      ProtocolFamily.WIFI_UAV ->
        byteArrayOf(0x66.toByte(), r, p, t, y, f, 0x00, 0x99.toByte()).also {
          it[6] = ByteFraming.xorChecksum(it, 1, 6)
        }

      ProtocolFamily.COOINGDV -> {
        val b1 = floatToCooingdvAxis(roll)
        val b2 = floatToCooingdvAxis(pitch)
        var acc = floatToCooingdvAxis(throttle)
        if (acc == 1) acc = 0
        val turn = floatToCooingdvAxis(yaw)
        val tcFlags = mapCooingdvTcFlags(actionFlags)
        byteArrayOf(
            0x03,
            0x66.toByte(),
            b1.toByte(),
            b2.toByte(),
            acc.toByte(),
            turn.toByte(),
            tcFlags,
            0x00,
            0x99.toByte(),
          )
          .also { it[7] = ByteFraming.xorChecksum(it, 2, 7) }
      }

      ProtocolFamily.COOINGDV_GL -> {
        val b1 = floatToCooingdvAxis(roll)
        val b2 = floatToCooingdvAxis(pitch)
        var acc = floatToCooingdvAxis(throttle)
        if (acc == 1) acc = 0
        val turn = floatToCooingdvAxis(yaw)
        var flags1 = 0
        if ((actionFlags and (NativeActionFlags.TAKEOFF or NativeActionFlags.CONTROLLED_LAND)) != 0) {
          flags1 = flags1 or 0x01
        }
        if ((actionFlags and (NativeActionFlags.EMERGENCY_STOP or NativeActionFlags.MOTOR_STOP)) != 0) {
          flags1 = flags1 or 0x02
        }
        if ((actionFlags and NativeActionFlags.CALIBRATE_GYRO) != 0) flags1 = flags1 or 0x04
        if ((actionFlags and NativeActionFlags.FLIP_360) != 0) flags1 = flags1 or 0x08
        val flags2 = if ((actionFlags and NativeActionFlags.HEADLESS) != 0) 0x01 else 0x00
        ByteArray(21).also {
          it[0] = 0x03
          it[1] = 0x66.toByte()
          it[2] = 0x14
          it[3] = b1.toByte()
          it[4] = b2.toByte()
          it[5] = acc.toByte()
          it[6] = turn.toByte()
          it[7] = flags1.toByte()
          it[8] = flags2.toByte()
          it[19] = ByteFraming.xorChecksum(it, 3, 19)
          it[20] = 0x99.toByte()
        }
      }

      ProtocolFamily.FLOW_KY_Y_SERIES -> {
        val scale =
          when (effectiveSpeed) {
            0 -> 0.30f
            1 -> 0.60f
            else -> 1.00f
          }
        val b1 = floatToCooingdvAxis(roll * scale)
        val b2 = floatToCooingdvAxis(pitch * scale)
        var acc = floatToCooingdvAxis(throttle * scale)
        if (acc == 1) acc = 0
        val turn = floatToCooingdvAxis(yaw * scale)
        val cmd =
          when {
            (actionFlags and (NativeActionFlags.EMERGENCY_STOP or NativeActionFlags.MOTOR_STOP)) != 0 -> 0x02
            (actionFlags and (NativeActionFlags.TAKEOFF or NativeActionFlags.CONTROLLED_LAND)) != 0 -> 0x01
            (actionFlags and NativeActionFlags.CALIBRATE_GYRO) != 0 -> 0x03
            (actionFlags and NativeActionFlags.FLIP_360) != 0 -> 0x05
            (actionFlags and NativeActionFlags.HEADLESS) != 0 -> 0x04
            else -> 0x00
          }
        ByteArray(21).also {
          it[0] = 0x03
          it[1] = 0x66.toByte()
          it[2] = 0x14
          it[3] = b1.toByte()
          it[4] = b2.toByte()
          it[5] = acc.toByte()
          it[6] = turn.toByte()
          it[7] = cmd.toByte()
          it[8] = 0x02
          it[19] = ByteFraming.xorChecksum(it, 3, 19)
          it[20] = 0x99.toByte()
        }
      }

      ProtocolFamily.COOINGDV_JIELI -> {
        val b1 = floatToCooingdvAxis(roll)
        val b2 = floatToCooingdvAxis(pitch)
        var acc = floatToCooingdvAxis(throttle)
        if (acc == 1) acc = 0
        val turn = floatToCooingdvAxis(yaw)
        val tcFlags = mapCooingdvTcFlags(actionFlags).toInt() and 0xFF
        val chk = b1 xor b2 xor acc xor turn xor tcFlags
        val topic = "FLYING_CTRL".encodeToByteArray()
        val json =
          """{"op":"PUT","param":{"BYTE0":"102","BYTE1":"$b1","BYTE2":"$b2","BYTE3":"$acc","BYTE4":"$turn","BYTE5":"$tcFlags","BYTE6":"$chk","BYTE7":"153"}}"""
            .encodeToByteArray()
        ByteArray(4 + 2 + topic.size + 4 + json.size).also { out ->
          out[0] = 'C'.code.toByte()
          out[1] = 'T'.code.toByte()
          out[2] = 'P'.code.toByte()
          out[3] = ':'.code.toByte()
          out[4] = (topic.size and 0xFF).toByte()
          out[5] = ((topic.size ushr 8) and 0xFF).toByte()
          topic.copyInto(out, 6)
          val jOff = 6 + topic.size
          out[jOff] = (json.size and 0xFF).toByte()
          out[jOff + 1] = ((json.size ushr 8) and 0xFF).toByte()
          out[jOff + 2] = ((json.size ushr 16) and 0xFF).toByte()
          out[jOff + 3] = ((json.size ushr 24) and 0xFF).toByte()
          json.copyInto(out, jOff + 4)
        }
      }

      ProtocolFamily.WIFI_CAM ->
        byteArrayOf(0xFF.toByte(), 0x08, r, p, t, y, f, 0x00).also {
          it[7] = ByteFraming.xorChecksum(it, 2, 7)
        }

      ProtocolFamily.X69_LG ->
        ByteArray(25).also {
          it[0] = 0xCA.toByte()
          it[1] = 0x47.toByte()
          it[2] = 0xD5.toByte()
          it[3] = 0x00
          it[4] = 0x14
          it[5] = 0x66.toByte()
          it[6] = r
          it[7] = p
          it[8] = t
          it[9] = y
          it[10] = f
          it[11] = effectiveSpeed.toByte()
          it[12] = cameraTilt.toByte()
          it[23] = ByteFraming.xorChecksum(it, 6, 23)
          it[24] = 0x99.toByte()
        }

      ProtocolFamily.FLOW_BL60X,
      ProtocolFamily.JIELI_AC79,
      ProtocolFamily.LEWEI -> ByteArray(0)

      ProtocolFamily.ISOLATED_TEST_HARNESS ->
        byteArrayOf(
            0xAA.toByte(),
            0x55,
            r,
            p,
            t,
            y,
            f,
            effectiveSpeed.toByte(),
            0x00,
            0x5A,
          )
          .also { it[8] = ByteFraming.xorChecksum(it, 2, 8) }
    }
  }

  private fun axisToByte(v: Float): Byte {
    val clamped = v.coerceIn(-1f, 1f)
    return (((clamped + 1f) * 127.5f).roundToInt().coerceIn(0, 255)).toByte()
  }

  private fun floatToCooingdvAxis(v: Float): Int {
    val clamped = v.coerceIn(-1f, 1f)
    return (128f + clamped * 127f).roundToInt().coerceIn(1, 255)
  }

  private fun mapCooingdvTcFlags(actionFlags: Int): Byte {
    var out = 0
    if ((actionFlags and NativeActionFlags.TAKEOFF) != 0) out = out or 0x01
    if ((actionFlags and NativeActionFlags.CONTROLLED_LAND) != 0) out = out or 0x02
    if ((actionFlags and (NativeActionFlags.EMERGENCY_STOP or NativeActionFlags.MOTOR_STOP)) != 0) {
      out = out or 0x04
    }
    if ((actionFlags and NativeActionFlags.FLIP_360) != 0) out = out or 0x08
    if ((actionFlags and NativeActionFlags.HEADLESS) != 0) out = out or 0x10
    if ((actionFlags and NativeActionFlags.UNLOCK_MOTORS) != 0) out = out or 0x20
    if ((actionFlags and NativeActionFlags.CALIBRATE_GYRO) != 0) out = out or 0x80
    return out.toByte()
  }

  private fun mapFlags(actionFlags: Int): Byte {
    var out = 0
    if ((actionFlags and NativeActionFlags.TAKEOFF) != 0) out = out or 0x01
    if ((actionFlags and NativeActionFlags.CONTROLLED_LAND) != 0) out = out or 0x02
    if ((actionFlags and NativeActionFlags.EMERGENCY_STOP) != 0) out = out or 0x04
    if ((actionFlags and NativeActionFlags.CALIBRATE_GYRO) != 0) out = out or 0x08
    if ((actionFlags and NativeActionFlags.HEADLESS) != 0) out = out or 0x10
    if ((actionFlags and NativeActionFlags.MOTOR_STOP) != 0) out = out or 0x20
    if ((actionFlags and NativeActionFlags.FLIP_360) != 0) out = out or 0x40
    if ((actionFlags and NativeActionFlags.UNLOCK_MOTORS) != 0) out = out or 0x80
    return out.toByte()
  }
}

private fun VideoTransportType.toNativeKind(): Int =
  when (this) {
    VideoTransportType.NONE -> 0
    VideoTransportType.RTSP_H264 -> 1
    VideoTransportType.RTP_JPEG -> 2
    VideoTransportType.RAW_UDP_JPEG -> 3
    VideoTransportType.RAW_UDP_H264 -> 4
    VideoTransportType.RAW_UDP_H265 -> 5
    VideoTransportType.SYNTHETIC_TEST_PATTERN -> 6
    VideoTransportType.RTSP_H265 -> 7
    VideoTransportType.RTSP_MJPEG -> 8
    VideoTransportType.LEWEI_TCP_H264 -> 9
  }

private fun Int.toVideoTransportType(): VideoTransportType =
  when (this) {
    1 -> VideoTransportType.RTSP_H264
    2 -> VideoTransportType.RTP_JPEG
    3 -> VideoTransportType.RAW_UDP_JPEG
    4 -> VideoTransportType.RAW_UDP_H264
    5 -> VideoTransportType.RAW_UDP_H265
    6 -> VideoTransportType.SYNTHETIC_TEST_PATTERN
    7 -> VideoTransportType.RTSP_H265
    8 -> VideoTransportType.RTSP_MJPEG
    9 -> VideoTransportType.LEWEI_TCP_H264
    else -> VideoTransportType.NONE
  }
