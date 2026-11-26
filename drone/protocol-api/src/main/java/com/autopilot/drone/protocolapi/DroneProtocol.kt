package com.autopilot.drone.protocolapi

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Hardware-independent contract implemented by each isolated drone protocol adapter.
 * UI and flight-control domain interact solely with this contract via capability discovery.
 */
interface DroneProtocol {
  val descriptor: ProtocolDescriptor
  val capabilities: DroneCapabilities
  val connectionState: StateFlow<ConnectionState>

  suspend fun connect(config: ConnectionConfig)

  suspend fun disconnect()

  suspend fun sendCommand(command: FlightCommand)

  fun telemetry(): Flow<DroneTelemetry>

  fun videoFrames(): Flow<VideoFramePacket>
}
