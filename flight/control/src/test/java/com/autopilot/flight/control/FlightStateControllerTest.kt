package com.autopilot.flight.control

import com.autopilot.drone.protocolapi.ConnectionConfig
import com.autopilot.drone.protocolapi.ConnectionState
import com.autopilot.drone.protocolapi.DroneCapabilities
import com.autopilot.drone.protocolapi.DroneProtocol
import com.autopilot.drone.protocolapi.DroneTelemetry
import com.autopilot.drone.protocolapi.FlightCommand
import com.autopilot.drone.protocolapi.ProtocolCompatibilityRegistry
import com.autopilot.drone.protocolapi.ProtocolDescriptor
import com.autopilot.drone.protocolapi.VideoFramePacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeContractProtocol(
  override val descriptor: ProtocolDescriptor,
  override val capabilities: DroneCapabilities,
) : DroneProtocol {
  val recordedCommands = mutableListOf<FlightCommand>()
  private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
  override val connectionState: StateFlow<ConnectionState> = _state
  private val _telemetryFlow = kotlinx.coroutines.flow.MutableSharedFlow<DroneTelemetry>(extraBufferCapacity = 8)

  fun emitTelemetry(t: DroneTelemetry) {
    _telemetryFlow.tryEmit(t)
  }

  override suspend fun connect(config: ConnectionConfig) {
    _state.value = ConnectionState.Connected(config.hostIp, 1L)
  }

  override suspend fun disconnect() {
    _state.value = ConnectionState.Disconnected
  }

  override suspend fun sendCommand(command: FlightCommand) {
    recordedCommands.add(command)
  }

  override fun telemetry(): Flow<DroneTelemetry> = _telemetryFlow

  override fun videoFrames(): Flow<VideoFramePacket> = emptyFlow()
}

class FlightStateControllerTest {

  @Test
  fun `input normalizer applies deadzone and max throttle cap`() {
    val cfg = ControlSensitivityConfig(deadzone = 0.1f, expo = 0.0f, maxThrottleCap = 0.8f)
    val zero = InputNormalizer.normalizeSticks(0.05f, -0.05f, 1.0f, 0.0f, cfg)
    assertEquals(0f, zero.roll, 1e-4f)
    assertEquals(0f, zero.pitch, 1e-4f)
    assertEquals(0.8f, zero.throttle, 1e-4f)
  }

  @Test
  fun `takeoff requires arming first and emergency stop is distinct from controlled land`() = runTest {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val controller = FlightStateController(scope)
    val entry = ProtocolCompatibilityRegistry.findById("s2x")!!
    val fake = FakeContractProtocol(entry.descriptor, entry.capabilities)

    controller.connectProtocol(fake, ConnectionConfig("192.168.0.1", 40000, 40000, null))
    assertEquals(FlightState.Ready, controller.flightState.value)

    // Takeoff rejected when not armed
    assertFalse(controller.takeoff())

    // Arm then takeoff succeeds
    assertTrue(controller.arm())
    assertEquals(FlightState.Armed, controller.flightState.value)
    assertTrue(controller.takeoff())
    assertTrue(fake.recordedCommands.contains(FlightCommand.Takeoff))

    // Controlled land sends ControlledLand, NOT EmergencyStop
    assertTrue(controller.controlledLand())
    assertTrue(fake.recordedCommands.contains(FlightCommand.ControlledLand))
    assertFalse(fake.recordedCommands.contains(FlightCommand.EmergencyStop))

    // Emergency stop sends EmergencyStop and enters Emergency state
    controller.emergencyStop()
    assertEquals(FlightState.Emergency, controller.flightState.value)
    assertTrue(fake.recordedCommands.contains(FlightCommand.EmergencyStop))

    controller.disconnect()
  }

  @Test
  fun `arm sends unlockMotors, flip360 requires armed or flying, and fastFly latches speed tier 2`() = runTest {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val controller = FlightStateController(scope)
    val entry = ProtocolCompatibilityRegistry.findById("x69_lg")!!
    val fake = FakeContractProtocol(entry.descriptor, entry.capabilities)

    controller.connectProtocol(fake, ConnectionConfig("192.168.1.1", 1234, 1234, 7080))

    // Flip rejected while still in Ready state
    assertFalse(controller.flip360())

    // Arm sends UnlockMotors on wire when capability is supported
    assertTrue(controller.arm())
    assertTrue(fake.recordedCommands.contains(FlightCommand.UnlockMotors))

    // Flip succeeds once Armed
    assertTrue(controller.flip360())
    assertTrue(fake.recordedCommands.any { it is FlightCommand.Flip360 })

    // Fast Fly latches enabled and locks speed tier to max (2)
    controller.setFastFly(true)
    assertTrue(controller.fastFly.value)
    assertEquals(2, controller.speedTier.value)
    assertTrue(fake.recordedCommands.contains(FlightCommand.SetFastFly(true)))
    assertTrue(fake.recordedCommands.contains(FlightCommand.SetSpeedTier(2)))

    controller.setFastFly(false)
    assertFalse(controller.fastFly.value)
    assertTrue(fake.recordedCommands.contains(FlightCommand.SetFastFly(false)))

    controller.disconnect()
  }

  @Test
  fun `soft descent assist transmits negative throttle taper and firmware SD triggers dispatch cleanly`() = runTest {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val controller = FlightStateController(scope)
    val s2xEntry = ProtocolCompatibilityRegistry.findById("s2x")!!
    val s2xFake = FakeContractProtocol(s2xEntry.descriptor, s2xEntry.capabilities)

    controller.connectProtocol(s2xFake, ConnectionConfig("192.168.0.1", 40000, 40000, null))
    controller.triggerFirmwarePhoto()
    controller.setFirmwareRecord(true)
    assertTrue(s2xFake.recordedCommands.contains(FlightCommand.TriggerFirmwarePhoto))
    assertTrue(s2xFake.recordedCommands.contains(FlightCommand.TriggerFirmwareRecord(true)))
    controller.disconnect()

    val entry = ProtocolCompatibilityRegistry.findById("cooingdv")!!
    val fake = FakeContractProtocol(entry.descriptor, entry.capabilities)
    controller.connectProtocol(fake, ConnectionConfig("192.168.1.1", 7099, 7070, 7099))
    assertTrue(controller.arm())
    assertTrue(controller.takeoff())
    fake.recordedCommands.clear()
    assertTrue(controller.controlledLand(softDescentAssist = true))
    assertTrue(fake.recordedCommands.contains(FlightCommand.ControlledLand))
    assertTrue(
      fake.recordedCommands.any { it is FlightCommand.StickAxes && it.throttle < -0.1f }
    )
    assertEquals(FlightState.Ready, controller.flightState.value)

    assertTrue(controller.switchCamera(2))
    assertTrue(fake.recordedCommands.contains(FlightCommand.SwitchCamera(2)))

    controller.disconnect()
  }

  @Test
  fun `video-only profiles block arm, takeoff, land, stop, emergency stop, and stick transmission`() = runTest {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val controller = FlightStateController(scope)
    val entry = ProtocolCompatibilityRegistry.findById("flow_bl60x")!!
    val fake = FakeContractProtocol(entry.descriptor, entry.capabilities)

    controller.connectProtocol(fake, ConnectionConfig("192.168.169.1", 8800, 8800, 8800))
    assertEquals(FlightState.Ready, controller.flightState.value)

    assertFalse(controller.arm())
    assertFalse(controller.unlockMotors())
    assertFalse(controller.takeoff())
    assertFalse(controller.controlledLand())
    assertFalse(controller.motorStop())
    controller.emergencyStop()
    controller.sendStickInput(0.5f, 0.5f, 0.5f, 0.5f)

    assertEquals(FlightState.Ready, controller.flightState.value)
    assertTrue(fake.recordedCommands.isEmpty())
    controller.disconnect()
  }

  @Test
  fun `preShaped sticks bypass double deadzone and expo while still honoring maxThrottleCap`() = runTest {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val controller = FlightStateController(scope)
    controller.updateSensitivity(ControlSensitivityConfig(deadzone = 0.25f, expo = 0.5f, maxThrottleCap = 0.8f))
    val entry = ProtocolCompatibilityRegistry.findById("s2x")!!
    val fake = FakeContractProtocol(entry.descriptor, entry.capabilities)

    controller.connectProtocol(fake, ConnectionConfig("192.168.0.1", 40000, 40000, null))
    controller.sendStickInput(
      roll = 0.12f,
      pitch = -0.10f,
      throttle = 0.15f,
      yaw = -0.08f,
      preShapedRollPitch = true,
      preShapedThrottleYaw = true,
    )
    val cmd = fake.recordedCommands.filterIsInstance<FlightCommand.StickAxes>().last()
    assertEquals(0.12f, cmd.roll, 1e-4f)
    assertEquals(-0.10f, cmd.pitch, 1e-4f)
    assertEquals(0.15f * 0.8f, cmd.throttle, 1e-4f)
    assertEquals(-0.08f, cmd.yaw, 1e-4f)
    controller.disconnect()
  }

  @Test
  fun `experimental gate blocks arm and takeoff when disabled but permits emergency stop and tracks linkRxVerified`() = runTest {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val controller = FlightStateController(scope)
    controller.allowExperimentalFlightCommands = false
    val entry = ProtocolCompatibilityRegistry.findById("x69_lg")!!
    val fake = FakeContractProtocol(entry.descriptor, entry.capabilities)

    controller.connectProtocol(fake, ConnectionConfig("192.168.1.1", 23458, 23458, 7080))
    assertFalse(controller.linkRxVerified.value)

    // Experimental arm/takeoff/unlock blocked while flag is false
    assertFalse(controller.arm())
    assertFalse(controller.unlockMotors())
    assertFalse(controller.takeoff())
    assertEquals(FlightState.Ready, controller.flightState.value)

    // Telemetry with packetsReceived > 0 flips linkRxVerified to true
    fake.emitTelemetry(
      DroneTelemetry(
        timestampMs = System.currentTimeMillis(),
        protocolId = "x69_lg",
        packetsSent = 5L,
        packetsReceived = 3L,
        droppedPackets = 0L,
        linkRttMs = null,
        batteryPercent = null,
        rssiDbm = null,
        altitudeMeters = null,
        lastCommandHex = "CA 47 D5",
      )
    )
    assertTrue(controller.linkRxVerified.value)

    // EmergencyStop remains allowed even when experimental flight gate is locked
    controller.emergencyStop()
    assertEquals(FlightState.Emergency, controller.flightState.value)
    assertTrue(fake.recordedCommands.contains(FlightCommand.EmergencyStop))

    controller.disconnect()
    assertFalse(controller.linkRxVerified.value)
  }
}
