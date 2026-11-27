package com.autopilot.drone.protocols

import com.autopilot.core.common.ByteFraming
import com.autopilot.drone.protocolapi.ProtocolCompatibilityRegistry
import com.autopilot.drone.protocolapi.ProtocolFamily
import com.autopilot.drone.protocolapi.VideoTransportType
import com.autopilot.nativecore.NativeActionFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolPacketEncoderTest {

  @Test
  fun `production registry contains all 11 protocol entries and excludes synthetic harness`() {
    val entries = ProtocolCompatibilityRegistry.productionEntries()
    assertEquals(11, entries.size)
    assertEquals("s2x", entries.first().descriptor.id)
    assertTrue(entries.none { it.descriptor.isSyntheticHarness })
    assertNull(ProtocolCompatibilityRegistry.findById("isolated_test_harness", includeSyntheticHarness = false))
    assertTrue(
      ProtocolCompatibilityRegistry.findById("isolated_test_harness", includeSyntheticHarness = true)
        ?.descriptor
        ?.isSyntheticHarness == true
    )
  }

  @Test
  fun `s2x packet encodes 11-byte header, centered neutral axes, xor checksum, and footer`() {
    val pkt = ProtocolPacketEncoder.encodeControlPacket(ProtocolFamily.S2X)
    assertEquals(11, pkt.size)
    assertEquals(0x66.toByte(), pkt[0])
    assertEquals(0x80.toByte(), pkt[1])
    assertEquals(128.toByte(), pkt[2])
    assertEquals(128.toByte(), pkt[3])
    assertEquals(128.toByte(), pkt[4])
    assertEquals(128.toByte(), pkt[5])
    assertEquals(ByteFraming.xorChecksum(pkt, 2, 9), pkt[9])
    assertEquals(0x99.toByte(), pkt[10])
  }

  @Test
  fun `wifi_uav encodes 8-byte frame while cooingdv encodes 9-byte TC frame with throttle 1 coerced to 0`() {
    val wifiUav =
      ProtocolPacketEncoder.encodeControlPacket(
        family = ProtocolFamily.WIFI_UAV,
        roll = 1.0f,
        pitch = -1.0f,
        actionFlags = NativeActionFlags.TAKEOFF,
      )
    assertEquals(8, wifiUav.size)
    assertEquals(0x66.toByte(), wifiUav[0])
    assertEquals(255.toByte(), wifiUav[1])
    assertEquals(0.toByte(), wifiUav[2])
    assertEquals(0x01.toByte(), wifiUav[5])
    assertEquals(ByteFraming.xorChecksum(wifiUav, 1, 6), wifiUav[6])
    assertEquals(0x99.toByte(), wifiUav[7])

    val cooingTc =
      ProtocolPacketEncoder.encodeControlPacket(
        family = ProtocolFamily.COOINGDV,
        roll = 0f,
        pitch = 0f,
        throttle = -1.0f,
        yaw = 0f,
        actionFlags = NativeActionFlags.CALIBRATE_GYRO or NativeActionFlags.FLIP_360,
      )
    assertEquals(9, cooingTc.size)
    assertEquals(0x03.toByte(), cooingTc[0])
    assertEquals(0x66.toByte(), cooingTc[1])
    assertEquals(128.toByte(), cooingTc[2])
    assertEquals(128.toByte(), cooingTc[3])
    assertEquals(0.toByte(), cooingTc[4]) // throttle == 1 coerced to 0 per CooingDV spec
    assertEquals(0x88.toByte(), cooingTc[6]) // 0x80 calibrate | 0x08 flip
    assertEquals(ByteFraming.xorChecksum(cooingTc, 2, 7), cooingTc[7])
    assertEquals(0x99.toByte(), cooingTc[8])
  }

  @Test
  fun `cooingdv_gl and flow_ky_y_series encode distinct 21-byte frames`() {
    val gl =
      ProtocolPacketEncoder.encodeControlPacket(
        family = ProtocolFamily.COOINGDV_GL,
        actionFlags = NativeActionFlags.TAKEOFF or NativeActionFlags.HEADLESS,
      )
    assertEquals(21, gl.size)
    assertEquals(0x03.toByte(), gl[0])
    assertEquals(0x66.toByte(), gl[1])
    assertEquals(0x14.toByte(), gl[2])
    assertEquals(0x01.toByte(), gl[7]) // flags1 takeoff
    assertEquals(0x01.toByte(), gl[8]) // flags2 headless
    assertEquals(ByteFraming.xorChecksum(gl, 3, 19), gl[19])
    assertEquals(0x99.toByte(), gl[20])

    val kySlow =
      ProtocolPacketEncoder.encodeControlPacket(
        family = ProtocolFamily.FLOW_KY_Y_SERIES,
        roll = 1.0f,
        speedTier = 0, // 30% scaling -> 128 + 127*0.3 = 166
        actionFlags = NativeActionFlags.CALIBRATE_GYRO,
      )
    assertEquals(21, kySlow.size)
    assertEquals(0x03.toByte(), kySlow[0])
    assertEquals(0x66.toByte(), kySlow[1])
    assertEquals(0x14.toByte(), kySlow[2])
    assertEquals(166.toByte(), kySlow[3])
    assertEquals(0x03.toByte(), kySlow[7]) // discrete cmd 0x03 calibrate
    assertEquals(0x02.toByte(), kySlow[8]) // constant pyDroneWire speed byte
    assertEquals(ByteFraming.xorChecksum(kySlow, 3, 19), kySlow[19])
    assertEquals(0x99.toByte(), kySlow[20])
  }

  @Test
  fun `cooingdv_jieli wraps FLYING_CTRL JSON inside CTP frame`() {
    val pkt = ProtocolPacketEncoder.encodeControlPacket(ProtocolFamily.COOINGDV_JIELI)
    assertTrue(pkt.size > 40)
    assertEquals('C'.code.toByte(), pkt[0])
    assertEquals('T'.code.toByte(), pkt[1])
    assertEquals('P'.code.toByte(), pkt[2])
    assertEquals(':'.code.toByte(), pkt[3])
    val text = pkt.decodeToString()
    assertTrue(text.contains("FLYING_CTRL"))
    assertTrue(text.contains("\"BYTE0\":\"102\""))
    assertTrue(text.contains("\"BYTE7\":\"153\""))
  }

  @Test
  fun `wifi_cam encodes 8-byte header and x69_lg encodes 25-byte wrapped CA 47 D5 00 14 frame`() {
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
    assertEquals(25, x69.size)
    assertEquals(0xCA.toByte(), x69[0])
    assertEquals(0x47.toByte(), x69[1])
    assertEquals(0xD5.toByte(), x69[2])
    assertEquals(0x00.toByte(), x69[3])
    assertEquals(0x14.toByte(), x69[4])
    assertEquals(0x66.toByte(), x69[5])
    assertEquals(2.toByte(), x69[11])
    assertEquals(1.toByte(), x69[12])
    assertEquals(ByteFraming.xorChecksum(x69, 6, 23), x69[23])
    assertEquals(0x99.toByte(), x69[24])
    assertFalse(x69.contentEquals(wifiCam))
  }

  @Test
  fun `video-only families return empty flight control payloads`() {
    for (family in listOf(ProtocolFamily.FLOW_BL60X, ProtocolFamily.JIELI_AC79, ProtocolFamily.LEWEI)) {
      val pkt =
        ProtocolPacketEncoder.encodeControlPacket(
          family = family,
          roll = 1.0f,
          throttle = 1.0f,
          actionFlags = NativeActionFlags.TAKEOFF,
        )
      assertEquals(0, pkt.size)
    }
  }

  @Test
  fun `ssid multi-match distinguishes ambiguous FLOW-UFO prefix and validates updated defaults`() {
    val flowMatches = ProtocolCompatibilityRegistry.findMatchesBySsid("FLOW-UFO-4K-99")
    val matchIds = flowMatches.map { it.descriptor.id }
    assertTrue(matchIds.contains("flow_bl60x"))
    assertTrue(matchIds.contains("flow_ky_y_series"))
    assertFalse(matchIds.contains("s2x"))

    val x69 = ProtocolCompatibilityRegistry.findById("x69_lg")!!
    assertEquals(23458, x69.descriptor.controlPort)
    assertTrue(x69.descriptor.alternateHostIps.contains("172.16.11.1"))
    assertFalse(x69.capabilities.supportsFirmwareSdCapture)
    assertEquals(7080, x69.descriptor.resolvedVideoPort(VideoTransportType.RAW_UDP_JPEG))
    assertEquals(554, x69.descriptor.resolvedVideoPort(VideoTransportType.RTSP_H265))
    assertEquals(
      9123,
      x69.descriptor.resolvedVideoPort(VideoTransportType.RTSP_H265, customPort = 9123),
    )

    val cooing = ProtocolCompatibilityRegistry.findById("cooingdv")!!
    assertEquals(VideoTransportType.RTSP_MJPEG, cooing.descriptor.videoTransport)
    assertEquals(270, cooing.descriptor.defaultAutoRotationDeg)
    assertTrue(cooing.capabilities.supportsCameraSwitch)
  }
}
