package com.autopilot.drone.protocols

import com.autopilot.core.common.ByteFraming
import com.autopilot.drone.protocolapi.ProtocolCompatibilityRegistry
import com.autopilot.drone.protocolapi.ProtocolFamily
import com.autopilot.nativecore.NativeActionFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolPacketEncoderTest {

  @Test
  fun `production registry contains all 6 TurboDrone protocols and excludes synthetic harness`() {
    val entries = ProtocolCompatibilityRegistry.productionEntries()
    assertEquals(6, entries.size)
    assertTrue(entries.none { it.descriptor.isSyntheticHarness })
    assertNull(ProtocolCompatibilityRegistry.findById("isolated_test_harness", includeSyntheticHarness = false))
    assertTrue(
      ProtocolCompatibilityRegistry.findById("isolated_test_harness", includeSyntheticHarness = true)
        ?.descriptor
        ?.isSyntheticHarness == true
    )
  }

  @Test
  fun `s2x packet encodes header, centered neutral axes, xor checksum, and footer`() {
    val pkt = ProtocolPacketEncoder.encodeControlPacket(ProtocolFamily.S2X)
    assertEquals(11, pkt.size)
    assertEquals(0x66.toByte(), pkt[0])
    assertEquals(0x80.toByte(), pkt[1])
    assertEquals(128.toByte(), pkt[2]) // neutral roll
    assertEquals(128.toByte(), pkt[3]) // neutral pitch
    assertEquals(128.toByte(), pkt[4]) // neutral throttle
    assertEquals(128.toByte(), pkt[5]) // neutral yaw
    assertEquals(ByteFraming.xorChecksum(pkt, 2, 9), pkt[9])
    assertEquals(0x99.toByte(), pkt[10])
  }

  @Test
  fun `wifi_uav and cooingdv encode 8-byte framed packets with valid xor checksum`() {
    for (family in listOf(ProtocolFamily.WIFI_UAV, ProtocolFamily.COOINGDV)) {
      val pkt =
        ProtocolPacketEncoder.encodeControlPacket(
          family = family,
          roll = 1.0f,
          pitch = -1.0f,
          actionFlags = NativeActionFlags.TAKEOFF,
        )
      assertEquals(8, pkt.size)
      assertEquals(0x66.toByte(), pkt[0])
      assertEquals(255.toByte(), pkt[1]) // +1.0 roll
      assertEquals(0.toByte(), pkt[2])   // -1.0 pitch
      assertEquals(0x01.toByte(), pkt[5]) // takeoff flag
      assertEquals(ByteFraming.xorChecksum(pkt, 1, 6), pkt[6])
      assertEquals(0x99.toByte(), pkt[7])
    }
  }

  @Test
  fun `cooingdv_jieli wraps command inside CTP prefix`() {
    val pkt = ProtocolPacketEncoder.encodeControlPacket(ProtocolFamily.COOINGDV_JIELI)
    assertEquals(14, pkt.size)
    assertEquals('C'.code.toByte(), pkt[0])
    assertEquals('T'.code.toByte(), pkt[1])
    assertEquals('P'.code.toByte(), pkt[2])
    assertEquals(':'.code.toByte(), pkt[3])
    assertEquals(0x99.toByte(), pkt[13])
    assertEquals(ByteFraming.xorChecksum(pkt, 7, 12), pkt[12])
  }

  @Test
  fun `wifi_cam and x69_lg encode distinct headers and emergency stop bit`() {
    val wifiCam =
      ProtocolPacketEncoder.encodeControlPacket(
        ProtocolFamily.WIFI_CAM,
        actionFlags = NativeActionFlags.EMERGENCY_STOP,
      )
    assertEquals(8, wifiCam.size)
    assertEquals(0xFF.toByte(), wifiCam[0])
    assertEquals(0x08.toByte(), wifiCam[1])
    assertEquals(0x04.toByte(), wifiCam[6])
    assertEquals(ByteFraming.xorChecksum(wifiCam, 2, 7), wifiCam[7])

    val x69 =
      ProtocolPacketEncoder.encodeControlPacket(
        ProtocolFamily.X69_LG,
        speedTier = 2,
        cameraTilt = 1,
      )
    assertEquals(10, x69.size)
    assertEquals(0x66.toByte(), x69[0])
    assertEquals(2.toByte(), x69[6])
    assertEquals(1.toByte(), x69[7])
    assertEquals(ByteFraming.xorChecksum(x69, 1, 8), x69[8])
    assertEquals(0x99.toByte(), x69[9])
    assertFalse(x69.contentEquals(wifiCam))
  }

  @Test
  fun `flip360, unlock motors, and fast fly encode exact protocol bits`() {
    val flipPkt =
      ProtocolPacketEncoder.encodeControlPacket(
        ProtocolFamily.WIFI_UAV,
        actionFlags = NativeActionFlags.FLIP_360,
      )
    assertEquals(0x40.toByte(), flipPkt[5])
    assertEquals(ByteFraming.xorChecksum(flipPkt, 1, 6), flipPkt[6])

    val unlockPkt =
      ProtocolPacketEncoder.encodeControlPacket(
        ProtocolFamily.S2X,
        actionFlags = NativeActionFlags.UNLOCK_MOTORS,
      )
    assertEquals(0x80.toByte(), unlockPkt[6])
    assertEquals(ByteFraming.xorChecksum(unlockPkt, 2, 9), unlockPkt[9])

    val fastS2x =
      ProtocolPacketEncoder.encodeControlPacket(
        ProtocolFamily.S2X,
        speedTier = 0,
        actionFlags = NativeActionFlags.FAST_FLY,
      )
    assertEquals(2.toByte(), fastS2x[7])
    assertEquals(0x01.toByte(), fastS2x[8])
    assertEquals(ByteFraming.xorChecksum(fastS2x, 2, 9), fastS2x[9])
  }

  @Test
  fun `registry exposes TurboDrone models, alternate host IPs, and selectable video modes`() {
    val s2x = ProtocolCompatibilityRegistry.findById("s2x")!!
    assertTrue(s2x.descriptor.knownModels.contains("PL-515"))

    val wifiUav = ProtocolCompatibilityRegistry.findById("wifi_uav")!!
    assertTrue(wifiUav.descriptor.knownModels.containsAll(listOf("V88", "V66", "GT3")))
    assertTrue(
      wifiUav.capabilities.supportedVideoModes.containsAll(
        listOf(
          com.autopilot.drone.protocolapi.VideoTransportType.RAW_UDP_JPEG,
          com.autopilot.drone.protocolapi.VideoTransportType.RAW_UDP_H264,
        )
      )
    )

    val x69 = ProtocolCompatibilityRegistry.findById("x69_lg")!!
    assertTrue(x69.descriptor.alternateHostIps.contains("172.16.11.1"))
    assertTrue(
      x69.capabilities.supportedVideoModes.contains(
        com.autopilot.drone.protocolapi.VideoTransportType.RTSP_H265
      )
    )
    assertTrue(x69.capabilities.supportsFlip)
    assertTrue(x69.capabilities.supportsUnlockCommand)
    assertTrue(x69.capabilities.supportsFastFly)
  }
}
