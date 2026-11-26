package com.autopilot.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import com.autopilot.core.common.AutopilotLog
import com.autopilot.core.common.SubsystemTag
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.LiteRtException
import com.google.ai.edge.litert.TensorBuffer
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * LiteRT CompiledModel implementation for YOLO26n.
 * Uses Accelerator.GPU with warm-up inference and automatic fallback to Accelerator.CPU.
 * Reuses pre-allocated TensorBuffers and stages imported/asset models in noBackupFilesDir.
 * Must only be created, invoked, and closed on the dedicated single-thread vision dispatcher.
 */
class LiteRtYolo26Detector(
  private val context: Context,
  private val preferGpu: Boolean = true,
) : ObjectDetector {

  companion object {
    const val INPUT_WIDTH = 640
    const val INPUT_HEIGHT = 640
    const val INPUT_FLOAT_COUNT = INPUT_WIDTH * INPUT_HEIGHT * 3
    private const val MODEL_ASSET_PATH = "models/yolo26n.tflite"
    private const val STAGED_FILE_NAME = "v1-yolo26n.tflite"

    private val sharedEnvironment by lazy { Environment.create() }

    private val COCO_LABELS =
      listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
        "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
        "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush",
      )

    fun labelFor(classId: Int): String = COCO_LABELS.getOrElse(classId) { "class_$classId" }
  }

  private var compiledModel: CompiledModel? = null
  private val inputBuffers = mutableListOf<TensorBuffer>()
  private val outputBuffers = mutableListOf<TensorBuffer>()
  private val inputFloats = FloatArray(INPUT_FLOAT_COUNT)
  private val pixelScratch = IntArray(INPUT_WIDTH * INPUT_HEIGHT)

  @Volatile
  override var availability: DetectorAvailability =
    DetectorAvailability.ModelNotProvisioned(
      "Import yolo26n.tflite in Vision Settings or bundle at assets/models/yolo26n.tflite."
    )
    private set

  fun importModelFromUri(uri: Uri): Boolean {
    return runCatching {
        val dir = File(context.noBackupFilesDir, "models").apply { mkdirs() }
        val target = File(dir, STAGED_FILE_NAME)
        val tmp = File("${target.path}.tmp")
        context.contentResolver.openInputStream(uri)?.use { input ->
          tmp.outputStream().use { output -> input.copyTo(output) }
        } ?: return false
        if (!tmp.renameTo(target)) {
          tmp.copyTo(target, overwrite = true)
          tmp.delete()
        }
        true
      }
      .getOrElse { err ->
        AutopilotLog.error(SubsystemTag.VISION, "Failed to import YOLO26n model: ${err.message}")
        false
      }
  }

  override fun initialize(): DetectorAvailability {
    closeModelResources()

    val modelFile = resolveOrStageModelFile()
    if (modelFile == null || !modelFile.isFile) {
      val missing =
        DetectorAvailability.ModelNotProvisioned(
          "No yolo26n.tflite weights found. Optical flow & C++ tracking remain active; import a YOLO26n .tflite file to enable ML detections."
        )
      availability = missing
      return missing
    }

    val accelerators =
      if (preferGpu) listOf(Accelerator.GPU, Accelerator.CPU) else listOf(Accelerator.CPU)

    for (acc in accelerators) {
      try {
        val model =
          CompiledModel.create(
            modelFile.absolutePath,
            CompiledModel.Options(acc),
            sharedEnvironment,
          )
        val inputs = model.createInputBuffers()
        val outputs = model.createOutputBuffers()
        if (inputs.isEmpty() || outputs.isEmpty()) {
          (inputs + outputs).forEach { it.close() }
          model.close()
          continue
        }
        // Mandatory warm-up inference on chosen accelerator
        inputs[0].writeFloat(inputFloats)
        model.run(inputs, outputs)
        outputs[0].readFloat()

        compiledModel = model
        inputBuffers += inputs
        outputBuffers += outputs
        val ready = DetectorAvailability.Ready(acc.name, "YOLO26n (LiteRT 2.3)")
        availability = ready
        AutopilotLog.info(SubsystemTag.VISION, "Initialized YOLO26n on LiteRT ${acc.name}")
        return ready
      } catch (e: LiteRtException) {
        AutopilotLog.warn(
          SubsystemTag.VISION,
          "LiteRT ${acc.name} initialization failed (${e.message}); trying fallback.",
        )
        closeModelResources()
      } catch (t: Throwable) {
        AutopilotLog.error(SubsystemTag.VISION, "Unexpected LiteRT load error: ${t.message}")
        closeModelResources()
      }
    }

    val failed = DetectorAvailability.Unavailable("Unable to compile YOLO26n model on GPU or CPU.")
    availability = failed
    return failed
  }

  override fun detect(bitmap: Bitmap, confidenceThreshold: Float): List<VisionDetection> {
    val model = compiledModel ?: return emptyList()
    if (inputBuffers.isEmpty() || outputBuffers.isEmpty()) return emptyList()

    preprocessBitmapToNhwcFloat(bitmap)
    inputBuffers[0].writeFloat(inputFloats)
    model.run(inputBuffers, outputBuffers)
    val rawOutput = outputBuffers[0].readFloat()
    return decodeYolo26Output(rawOutput, confidenceThreshold)
  }

  override fun close() {
    closeModelResources()
  }

  private fun closeModelResources() {
    (inputBuffers + outputBuffers).forEach { runCatching { it.close() } }
    inputBuffers.clear()
    outputBuffers.clear()
    runCatching { compiledModel?.close() }
    compiledModel = null
  }

  private fun resolveOrStageModelFile(): File? {
    val dir = File(context.noBackupFilesDir, "models")
    val staged = File(dir, STAGED_FILE_NAME)
    if (staged.isFile && staged.length() > 0L) {
      return staged
    }
    return runCatching {
        context.assets.open(MODEL_ASSET_PATH).use { input ->
          dir.mkdirs()
          val tmp = File("${staged.path}.tmp")
          tmp.outputStream().use { output -> input.copyTo(output) }
          if (!tmp.renameTo(staged)) {
            tmp.copyTo(staged, overwrite = true)
            tmp.delete()
          }
          staged
        }
      }
      .getOrNull()
  }

  private fun preprocessBitmapToNhwcFloat(bitmap: Bitmap) {
    val scaled =
      Bitmap.createScaledBitmap(bitmap, INPUT_WIDTH, INPUT_HEIGHT, true)
        .copy(Bitmap.Config.ARGB_8888, false)
    scaled.getPixels(pixelScratch, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)
    if (scaled !== bitmap) {
      scaled.recycle()
    }
    for (i in pixelScratch.indices) {
      val p = pixelScratch[i]
      val base = i * 3
      inputFloats[base] = ((p shr 16) and 0xFF) / 255.0f
      inputFloats[base + 1] = ((p shr 8) and 0xFF) / 255.0f
      inputFloats[base + 2] = (p and 0xFF) / 255.0f
    }
  }

  /**
   * Decodes YOLO26n NMS-free [300, 6] output ([x1, y1, x2, y2, score, classId])
   * in either pixel [0..640] or normalized [0..1] coordinates.
   */
  private fun decodeYolo26Output(output: FloatArray, threshold: Float): List<VisionDetection> {
    if (output.size % 6 != 0) return emptyList()
    val results = ArrayList<VisionDetection>(16)
    var i = 0
    while (i + 5 < output.size) {
      val conf = output[i + 4]
      if (conf >= threshold && conf <= 1.0f) {
        val rawX1 = output[i]
        val rawY1 = output[i + 1]
        val rawX2 = output[i + 2]
        val rawY2 = output[i + 3]
        val scale = if (max(rawX2, rawY2) > 2.0f) 1.0f / INPUT_WIDTH else 1.0f
        val l = (min(rawX1, rawX2) * scale).coerceIn(0f, 1f)
        val t = (min(rawY1, rawY2) * scale).coerceIn(0f, 1f)
        val r = (max(rawX1, rawX2) * scale).coerceIn(0f, 1f)
        val b = (max(rawY1, rawY2) * scale).coerceIn(0f, 1f)
        if (r - l > 0.01f && b - t > 0.01f) {
          val classId = output[i + 5].toInt().coerceAtLeast(0)
          results.add(
            VisionDetection(
              classId = classId,
              label = labelFor(classId),
              confidence = conf,
              boxNormalized = RectF(l, t, r, b),
            )
          )
        }
      }
      i += 6
    }
    return results
  }
}
