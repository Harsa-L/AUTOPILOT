package com.autopilot.vision

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.autopilot.core.common.AutopilotLog
import com.autopilot.core.common.SubsystemTag
import java.io.File

/**
 * Shared dynamic YOLO / LiteRT model registry used by both production flight CV
 * and Vision Benchmark Studio. Discovers actual `.tflite` models bundled in
 * `assets/models/` and staged/imported in `noBackupFilesDir/models/` without
 * inventing or hardcoding missing weights.
 */
object VisionModelRegistry {

  private const val ASSET_DIR = "models"
  private const val LOCAL_DIR = "models"

  fun modelsDir(context: Context): File =
    File(context.noBackupFilesDir, LOCAL_DIR).apply { mkdirs() }

  fun discoverModels(context: Context): List<VisionModelSpec> {
    val specs = linkedMapOf<String, VisionModelSpec>()

    // 1. Discover bundled asset `.tflite` models
    runCatching {
      context.assets.list(ASSET_DIR)?.filter { it.endsWith(".tflite", ignoreCase = true) }?.forEach { fileName ->
        val assetPath = "$ASSET_DIR/$fileName"
        val size =
          runCatching { context.assets.openFd(assetPath).use { it.length } }
            .getOrElse {
              runCatching { context.assets.open(assetPath).use { it.available().toLong() } }.getOrDefault(0L)
            }
        val id = normalizeModelId(fileName)
        specs[id] =
          VisionModelSpec(
            id = id,
            displayName = formatDisplayName(fileName),
            source = VisionModelSource.BUNDLED_ASSET,
            location = assetPath,
            fileSizeBytes = size,
            postprocessMode = inferPostprocessMode(fileName),
          )
      }
    }

    // 2. Discover imported / staged `.tflite` models in noBackupFilesDir/models
    runCatching {
      val dir = modelsDir(context)
      dir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".tflite", ignoreCase = true) && it.length() > 0L }
        ?.sortedBy { it.name.lowercase() }
        ?.forEach { file ->
          val id = normalizeModelId(file.name)
          specs[id] =
            VisionModelSpec(
              id = id,
              displayName = formatDisplayName(file.name),
              source = VisionModelSource.IMPORTED_FILE,
              location = file.absolutePath,
              fileSizeBytes = file.length(),
              postprocessMode = inferPostprocessMode(file.name),
            )
        }
    }

    return specs.values.toList()
  }

  fun findById(context: Context, modelId: String?): VisionModelSpec? {
    val all = discoverModels(context)
    if (modelId.isNullOrBlank()) return all.firstOrNull()
    return all.firstOrNull { it.id == modelId } ?: all.firstOrNull()
  }

  fun importModelFromUri(
    context: Context,
    uri: Uri,
    explicitFileName: String? = null,
  ): VisionModelSpec? {
    return runCatching {
        val resolvedName = sanitizeTfliteFileName(explicitFileName ?: queryDisplayName(context, uri))
        val dir = modelsDir(context)
        val target = File(dir, resolvedName)
        val tmp = File("${target.path}.tmp")
        context.contentResolver.openInputStream(uri)?.use { input ->
          tmp.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        if (!tmp.renameTo(target)) {
          tmp.copyTo(target, overwrite = true)
          tmp.delete()
        }
        val id = normalizeModelId(target.name)
        VisionModelSpec(
          id = id,
          displayName = formatDisplayName(target.name),
          source = VisionModelSource.IMPORTED_FILE,
          location = target.absolutePath,
          fileSizeBytes = target.length(),
          postprocessMode = inferPostprocessMode(target.name),
        )
      }
      .getOrElse { err ->
        AutopilotLog.error(SubsystemTag.VISION, "Failed to import .tflite model: ${err.message}")
        null
      }
  }

  internal fun normalizeModelId(fileName: String): String {
    val base = fileName.removeSuffix(".tflite").removePrefix("v1-").lowercase()
    return base.replace(Regex("[^a-z0-9_.-]"), "_")
  }

  internal fun inferPostprocessMode(fileName: String): YoloPostprocessMode {
    val lower = fileName.lowercase()
    return when {
      lower.contains("yolo26") -> YoloPostprocessMode.YOLO26_NMS_FREE
      lower.contains("yolo11") || lower.contains("yolov8") || lower.contains("yolov10") ->
        YoloPostprocessMode.YOLO_ANCHOR_FREE_NMS
      else -> YoloPostprocessMode.AUTO
    }
  }

  internal fun formatDisplayName(fileName: String): String {
    val clean = fileName.removeSuffix(".tflite").removePrefix("v1-")
    return when {
      clean.equals("yolo26n", ignoreCase = true) -> "YOLO26n"
      clean.equals("yolo26s", ignoreCase = true) -> "YOLO26s"
      clean.equals("yolo11n", ignoreCase = true) -> "YOLO11n"
      clean.equals("yolo11s", ignoreCase = true) -> "YOLO11s"
      else -> clean
    }
  }

  private fun queryDisplayName(context: Context, uri: Uri): String {
    var candidate: String? = null
    runCatching {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
          if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) candidate = cursor.getString(idx)
          }
        }
    }
    return candidate ?: uri.lastPathSegment ?: "v1-yolo26n.tflite"
  }

  private fun sanitizeTfliteFileName(raw: String): String {
    val leaf = raw.substringAfterLast('/').substringAfterLast('\\')
    val clean = leaf.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "imported_model.tflite" }
    return if (clean.endsWith(".tflite", ignoreCase = true)) clean else "$clean.tflite"
  }
}
