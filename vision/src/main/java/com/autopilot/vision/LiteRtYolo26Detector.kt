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
import com.google.ai.edge.litert.TensorType
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * LiteRT CompiledModel implementation supporting YOLO26n/26s/11n and compatible `.tflite`
 * models from [VisionModelRegistry].
 * Uses Accelerator.GPU with warm-up inference and automatic fallback to Accelerator.CPU.
 * Reuses pre-allocated TensorBuffers and stages imported/asset models in noBackupFilesDir.
 * Must only be created, invoked, and closed on its dedicated single-thread vision dispatcher.
 */
class LiteRtYolo26Detector(
  private val context: Context,
  @Volatile private var preferGpu: Boolean = true,
  initialModelSpec: VisionModelSpec? = null,
) : ObjectDetector {

  companion object {
    const val DEFAULT_INPUT_WIDTH = 640
    const val DEFAULT_INPUT_HEIGHT = 640
    const val INPUT_WIDTH = DEFAULT_INPUT_WIDTH
    const val INPUT_HEIGHT = DEFAULT_INPUT_HEIGHT
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
  private var inputWidth = DEFAULT_INPUT_WIDTH
  private var inputHeight = DEFAULT_INPUT_HEIGHT
  private var inputElementType = VisionTensorType.FLOAT32
  private var postprocessMode = YoloPostprocessMode.AUTO
  private var inputFloats = FloatArray(DEFAULT_INPUT_WIDTH * DEFAULT_INPUT_HEIGHT * 3)
  private var inputBytes = ByteArray(0)
  private var pixelScratch = IntArray(DEFAULT_INPUT_WIDTH * DEFAULT_INPUT_HEIGHT)

  @Volatile
  var activeModelSpec: VisionModelSpec? = initialModelSpec
    private set

  @Volatile
  override var availability: DetectorAvailability =
    DetectorAvailability.ModelNotProvisioned(
      "Import a .tflite YOLO model (e.g. yolo26n.tflite, yolo26s.tflite, yolo11n.tflite) in Vision Settings or bundle at assets/models/."
    )
    private set

  fun setPreferGpu(prefer: Boolean) {
    preferGpu = prefer
  }

  fun setModelSpec(spec: VisionModelSpec?) {
    activeModelSpec = spec
  }

  fun importModelFromUri(uri: Uri): Boolean {
    val imported = VisionModelRegistry.importModelFromUri(context, uri)
    if (imported != null) {
      activeModelSpec = imported
      return true
    }
    return false
  }

  override fun initialize(): DetectorAvailability {
    closeModelResources()

    val resolvedSpec = activeModelSpec ?: VisionModelRegistry.findById(context, null)
    val modelFile = resolveOrStageModelFile(resolvedSpec)
    if (modelFile == null || !modelFile.isFile) {
      val missing =
        DetectorAvailability.ModelNotProvisioned(
          "No .tflite weights found. Optical flow & C++ tracking remain active; import a YOLO .tflite file to enable ML detections."
        )
      availability = missing
      return missing
    }

    val modelLabel = resolvedSpec?.displayName ?: "YOLO26n"
    postprocessMode = resolvedSpec?.postprocessMode ?: YoloPostprocessMode.AUTO
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

        inspectAndAllocateTensorBuffers(model)

        // Mandatory warm-up inference on chosen accelerator
        if (inputElementType == VisionTensorType.FLOAT32) {
          inputs[0].writeFloat(inputFloats)
        } else {
          inputs[0].writeInt8(inputBytes)
        }
        model.run(inputs, outputs)
        outputs[0].readFloat()

        compiledModel = model
        inputBuffers += inputs
        outputBuffers += outputs
        activeModelSpec =
          (resolvedSpec
            ?: VisionModelSpec(
              id = "yolo26n",
              displayName = modelLabel,
              source = VisionModelSource.IMPORTED_FILE,
              location = modelFile.absolutePath,
              fileSizeBytes = modelFile.length(),
            ))
            .copy(
              inputWidth = inputWidth,
              inputHeight = inputHeight,
              tensorType = inputElementType,
              postprocessMode = postprocessMode,
            )

        val ready = DetectorAvailability.Ready(acc.name, "$modelLabel (LiteRT 2.3)")
        availability = ready
        AutopilotLog.info(
          SubsystemTag.VISION,
          "Initialized $modelLabel (${inputWidth}x${inputHeight} $inputElementType) on LiteRT ${acc.name}",
        )
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

    val failed = DetectorAvailability.Unavailable("Unable to compile $modelLabel model on GPU or CPU.")
    availability = failed
    return failed
  }

  override fun detect(bitmap: Bitmap, confidenceThreshold: Float): List<VisionDetection> =
    detectWithTimings(bitmap, confidenceThreshold).first

  fun detectWithTimings(
    bitmap: Bitmap,
    confidenceThreshold: Float,
  ): Pair<List<VisionDetection>, VisionStageTimingsMs> {
    val model = compiledModel ?: return emptyList<VisionDetection>() to VisionStageTimingsMs()
    if (inputBuffers.isEmpty() || outputBuffers.isEmpty()) {
      return emptyList<VisionDetection>() to VisionStageTimingsMs()
    }

    val t0 = System.nanoTime()
    preprocessBitmap(bitmap)
    if (inputElementType == VisionTensorType.FLOAT32) {
      inputBuffers[0].writeFloat(inputFloats)
    } else {
      inputBuffers[0].writeInt8(inputBytes)
    }
    val t1 = System.nanoTime()

    model.run(inputBuffers, outputBuffers)
    val rawOutput = outputBuffers[0].readFloat()
    val t2 = System.nanoTime()

    val decoded = decodeOutput(rawOutput, confidenceThreshold)
    val t3 = System.nanoTime()

    val timings =
      VisionStageTimingsMs(
        preprocessMs = (t1 - t0) / 1_000_000f,
        inferenceMs = (t2 - t1) / 1_000_000f,
        postprocessMs = (t3 - t2) / 1_000_000f,
        totalMs = (t3 - t0) / 1_000_000f,
      )
    return decoded to timings
  }

  override fun close() {
    closeModelResources()
  }

  private fun inspectAndAllocateTensorBuffers(model: CompiledModel) {
    val inType =
      runCatching { model.getInputTensorType("serving_default", "images") }
        .getOrElse {
          runCatching { model.getInputTensorType("serving_default", "input_0") }.getOrNull()
        }
    val dims = inType?.layout?.dimensions
    if (dims != null && dims.size == 4 && dims[1] > 0 && dims[2] > 0) {
      inputHeight = dims[1]
      inputWidth = dims[2]
    } else {
      inputWidth = DEFAULT_INPUT_WIDTH
      inputHeight = DEFAULT_INPUT_HEIGHT
    }
    inputElementType =
      when (inType?.elementType) {
        TensorType.ElementType.INT8 -> VisionTensorType.INT8
        else -> VisionTensorType.FLOAT32
      }
    val pixelCount = inputWidth * inputHeight
    if (pixelScratch.size != pixelCount) pixelScratch = IntArray(pixelCount)
    if (inputElementType == VisionTensorType.FLOAT32) {
      if (inputFloats.size != pixelCount * 3) inputFloats = FloatArray(pixelCount * 3)
      inputBytes = ByteArray(0)
    } else {
      if (inputBytes.size != pixelCount * 3) inputBytes = ByteArray(pixelCount * 3)
      inputFloats = FloatArray(0)
    }
  }

  private fun closeModelResources() {
    (inputBuffers + outputBuffers).forEach { runCatching { it.close() } }
    inputBuffers.clear()
    outputBuffers.clear()
    runCatching { compiledModel?.close() }
    compiledModel = null
  }

  private fun resolveOrStageModelFile(spec: VisionModelSpec?): File? {
    if (spec != null) {
      return when (spec.source) {
        VisionModelSource.IMPORTED_FILE -> File(spec.location).takeIf { it.isFile && it.length() > 0L }
        VisionModelSource.BUNDLED_ASSET -> stageAssetModel(spec.location, "staged-${spec.id}.tflite")
      }
    }
    val dir = File(context.noBackupFilesDir, "models")
    val staged = File(dir, STAGED_FILE_NAME)
    if (staged.isFile && staged.length() > 0L) {
      return staged
    }
    return stageAssetModel(MODEL_ASSET_PATH, STAGED_FILE_NAME)
  }

  private fun stageAssetModel(assetPath: String, stagedName: String): File? {
    val dir = File(context.noBackupFilesDir, "models")
    val staged = File(dir, stagedName)
    if (staged.isFile && staged.length() > 0L) return staged
    return runCatching {
        context.assets.open(assetPath).use { input ->
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

  private fun preprocessBitmap(bitmap: Bitmap) {
    val scaled =
      Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        .copy(Bitmap.Config.ARGB_8888, false)
    scaled.getPixels(pixelScratch, 0, inputWidth, 0, 0, inputWidth, inputHeight)
    if (scaled !== bitmap) {
      scaled.recycle()
    }
    if (inputElementType == VisionTensorType.FLOAT32) {
      for (i in pixelScratch.indices) {
        val p = pixelScratch[i]
        val base = i * 3
        inputFloats[base] = ((p shr 16) and 0xFF) / 255.0f
        inputFloats[base + 1] = ((p shr 8) and 0xFF) / 255.0f
        inputFloats[base + 2] = (p and 0xFF) / 255.0f
      }
    } else {
      for (i in pixelScratch.indices) {
        val p = pixelScratch[i]
        val base = i * 3
        inputBytes[base] = (((p shr 16) and 0xFF) - 128).toByte()
        inputBytes[base + 1] = (((p shr 8) and 0xFF) - 128).toByte()
        inputBytes[base + 2] = ((p and 0xFF) - 128).toByte()
      }
    }
  }

  private fun decodeOutput(output: FloatArray, threshold: Float): List<VisionDetection> {
    if (output.isEmpty()) return emptyList()
    return when (postprocessMode) {
      YoloPostprocessMode.YOLO26_NMS_FREE -> decodeYolo26Output(output, threshold)
      YoloPostprocessMode.YOLO_ANCHOR_FREE_NMS -> decodeYoloAnchorFreeWithNms(output, threshold)
      YoloPostprocessMode.AUTO -> {
        if (output.size <= 6000 && output.size % 6 == 0) {
          decodeYolo26Output(output, threshold)
        } else if (output.size % 84 == 0 && output.size >= 84 * 64) {
          decodeYoloAnchorFreeWithNms(output, threshold)
        } else if (output.size % 6 == 0) {
          decodeYolo26Output(output, threshold)
        } else {
          emptyList()
        }
      }
    }
  }

  /**
   * Decodes YOLO26 NMS-free [300, 6] output ([x1, y1, x2, y2, score, classId])
   * in either pixel [0..inputWidth] or normalized [0..1] coordinates.
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
        val scaleX = if (max(rawX2, rawY2) > 2.0f) 1.0f / inputWidth else 1.0f
        val scaleY = if (max(rawX2, rawY2) > 2.0f) 1.0f / inputHeight else 1.0f
        val l = (min(rawX1, rawX2) * scaleX).coerceIn(0f, 1f)
        val t = (min(rawY1, rawY2) * scaleY).coerceIn(0f, 1f)
        val r = (max(rawX1, rawX2) * scaleX).coerceIn(0f, 1f)
        val b = (max(rawY1, rawY2) * scaleY).coerceIn(0f, 1f)
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

  /**
   * Decodes standard YOLO11 / YOLOv8 [1, 84, N] (cx, cy, w, h + 80 class scores)
   * and applies class-aware greedy IoU Non-Maximum Suppression.
   */
  private fun decodeYoloAnchorFreeWithNms(
    output: FloatArray,
    threshold: Float,
    iouThreshold: Float = 0.45f,
  ): List<VisionDetection> {
    val channels = 84
    if (output.size % channels != 0) return emptyList()
    val numAnchors = output.size / channels
    val candidates = ArrayList<VisionDetection>(32)

    for (a in 0 until numAnchors) {
      var bestCls = -1
      var bestScore = threshold
      for (c in 0 until 80) {
        val score = output[(4 + c) * numAnchors + a]
        if (score >= bestScore && score <= 1.0f) {
          bestScore = score
          bestCls = c
        }
      }
      if (bestCls < 0) continue

      val cx = output[0 * numAnchors + a]
      val cy = output[1 * numAnchors + a]
      val bw = output[2 * numAnchors + a]
      val bh = output[3 * numAnchors + a]
      val normScaleX = if (cx > 2.0f || bw > 2.0f) 1.0f / inputWidth else 1.0f
      val normScaleY = if (cy > 2.0f || bh > 2.0f) 1.0f / inputHeight else 1.0f

      val halfW = (bw * normScaleX) * 0.5f
      val halfH = (bh * normScaleY) * 0.5f
      val cxN = cx * normScaleX
      val cyN = cy * normScaleY
      val l = (cxN - halfW).coerceIn(0f, 1f)
      val t = (cyN - halfH).coerceIn(0f, 1f)
      val r = (cxN + halfW).coerceIn(0f, 1f)
      val b = (cyN + halfH).coerceIn(0f, 1f)
      if (r - l > 0.01f && b - t > 0.01f) {
        candidates.add(
          VisionDetection(
            classId = bestCls,
            label = labelFor(bestCls),
            confidence = bestScore,
            boxNormalized = RectF(l, t, r, b),
          )
        )
      }
    }

    if (candidates.isEmpty()) return emptyList()
    candidates.sortByDescending { it.confidence }

    val kept = ArrayList<VisionDetection>(candidates.size.coerceAtMost(100))
    val suppressed = BooleanArray(candidates.size)
    for (i in candidates.indices) {
      if (suppressed[i]) continue
      val a = candidates[i]
      kept.add(a)
      if (kept.size >= 100) break
      for (j in i + 1 until candidates.size) {
        if (suppressed[j] || candidates[j].classId != a.classId) continue
        if (rectIoU(a.boxNormalized, candidates[j].boxNormalized) > iouThreshold) {
          suppressed[j] = true
        }
      }
    }
    return kept
  }

  private fun rectIoU(a: RectF, b: RectF): Float {
    val il = max(a.left, b.left)
    val it = max(a.top, b.top)
    val ir = min(a.right, b.right)
    val ib = min(a.bottom, b.bottom)
    val iw = max(0f, ir - il)
    val ih = max(0f, ib - it)
    val inter = iw * ih
    if (inter <= 0f) return 0f
    val areaA = max(0f, a.right - a.left) * max(0f, a.bottom - a.top)
    val areaB = max(0f, b.right - b.left) * max(0f, b.bottom - b.top)
    val denom = areaA + areaB - inter
    return if (denom > 1e-6f) inter / denom else 0f
  }
}
