package com.autopilot.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class VisionModelRegistryTest {

  @Test
  fun `normalizes model ids display names and yolo postprocessing modes`() {
    assertEquals("yolo26n", VisionModelRegistry.normalizeModelId("v1-yolo26n.tflite"))
    assertEquals("yolo26s", VisionModelRegistry.normalizeModelId("YOLO26s.tflite"))
    assertEquals("yolo11n", VisionModelRegistry.normalizeModelId("yolo11n.tflite"))

    assertEquals("YOLO26n", VisionModelRegistry.formatDisplayName("v1-yolo26n.tflite"))
    assertEquals("YOLO11n", VisionModelRegistry.formatDisplayName("yolo11n.tflite"))

    assertEquals(
      YoloPostprocessMode.YOLO26_NMS_FREE,
      VisionModelRegistry.inferPostprocessMode("v1-yolo26n.tflite"),
    )
    assertEquals(
      YoloPostprocessMode.YOLO_ANCHOR_FREE_NMS,
      VisionModelRegistry.inferPostprocessMode("yolo11n_int8.tflite"),
    )
    assertEquals(
      YoloPostprocessMode.AUTO,
      VisionModelRegistry.inferPostprocessMode("custom_detector.tflite"),
    )
  }
}
