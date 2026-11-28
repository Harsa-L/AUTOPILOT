package com.autopilot.visionbenchmark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import com.autopilot.core.common.AutopilotLog
import com.autopilot.core.common.SubsystemTag
import com.autopilot.vision.VisionFrameResult
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Triple independent MP4 recorder (Original, Degraded, Annotated) using dedicated
 * MediaCodec H.264 encoders + MediaMuxer writers on bounded queues so recording
 * never stalls camera capture or stalls live CV inference.
 */
class BenchmarkRecordingCoordinator {

  private var originalTrack: SingleStreamMp4Writer? = null
  private var degradedTrack: SingleStreamMp4Writer? = null
  private var annotatedTrack: SingleStreamMp4Writer? = null

  private val boxPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 3f
      color = Color.rgb(0, 229, 255)
    }
  private val labelBgPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.argb(190, 12, 18, 28)
    }
  private val textPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 20f
    }
  private val flowPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 2f
      color = Color.argb(200, 105, 240, 174)
    }

  fun startSession(
    sessionDir: File,
    selection: RecordingSelection,
    originalW: Int,
    originalH: Int,
    degradedW: Int,
    degradedH: Int,
    targetFps: Int,
  ) {
    stopAndFinalize()
    sessionDir.mkdirs()

    if (selection.recordOriginal) {
      originalTrack =
        SingleStreamMp4Writer(
            role = "Original",
            outputFile = File(sessionDir, "original.mp4"),
            rawWidth = originalW,
            rawHeight = originalH,
            fps = targetFps.coerceIn(10, 30),
          )
          .also { it.start() }
    }
    if (selection.recordDegraded) {
      degradedTrack =
        SingleStreamMp4Writer(
            role = "Degraded",
            outputFile = File(sessionDir, "degraded.mp4"),
            rawWidth = degradedW,
            rawHeight = degradedH,
            fps = targetFps.coerceIn(10, 30),
          )
          .also { it.start() }
    }
    if (selection.recordAnnotated) {
      annotatedTrack =
        SingleStreamMp4Writer(
            role = "Annotated",
            outputFile = File(sessionDir, "annotated.mp4"),
            rawWidth = degradedW,
            rawHeight = degradedH,
            fps = targetFps.coerceIn(10, 30),
          )
          .also { it.start() }
    }
  }

  fun enqueueOriginal(bitmap: Bitmap, ptsUs: Long) {
    originalTrack?.enqueueFrame(bitmap, ptsUs)
  }

  fun enqueueDegraded(bitmap: Bitmap, ptsUs: Long) {
    degradedTrack?.enqueueFrame(bitmap, ptsUs)
  }

  fun enqueueAnnotated(
    degradedBitmap: Bitmap,
    visionResult: VisionFrameResult?,
    stageLabel: String,
    ptsUs: Long,
  ) {
    val writer = annotatedTrack ?: return
    if (!writer.isActive) return
    val annotated = renderAnnotatedBitmap(degradedBitmap, visionResult, stageLabel)
    writer.enqueueFrameOwned(annotated, ptsUs)
  }

  fun stopAndFinalize(): List<RecordedFileInfo> {
    val results = mutableListOf<RecordedFileInfo>()
    originalTrack?.stopAndVerify()?.let(results::add)
    degradedTrack?.stopAndVerify()?.let(results::add)
    annotatedTrack?.stopAndVerify()?.let(results::add)
    originalTrack = null
    degradedTrack = null
    annotatedTrack = null
    return results
  }

  private fun renderAnnotatedBitmap(
    base: Bitmap,
    result: VisionFrameResult?,
    stageLabel: String,
  ): Bitmap {
    val copy = base.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(copy)
    val w = copy.width.toFloat()
    val h = copy.height.toFloat()

    if (result != null) {
      for (fv in result.flowVectors) {
        if (fv.confidence < 0.1f) continue
        val sx = fv.normX * w
        val sy = fv.normY * h
        canvas.drawLine(sx, sy, sx + fv.dxNorm * w * 2.5f, sy + fv.dyNorm * h * 2.5f, flowPaint)
      }

      for (t in result.trackedTargets) {
        val rect =
          RectF(
            (t.boxNormalized.left * w).coerceIn(0f, w),
            (t.boxNormalized.top * h).coerceIn(0f, h),
            (t.boxNormalized.right * w).coerceIn(0f, w),
            (t.boxNormalized.bottom * h).coerceIn(0f, h),
          )
        canvas.drawRect(rect, boxPaint)
        val caption = "#${t.trackId} ${t.label} ${(t.confidence * 100).toInt()}%"
        val textW = textPaint.measureText(caption)
        val topY = (rect.top - 26f).coerceAtLeast(0f)
        canvas.drawRect(rect.left, topY, (rect.left + textW + 12f).coerceAtMost(w), topY + 26f, labelBgPaint)
        canvas.drawText(caption, rect.left + 6f, topY + 20f, textPaint)
      }
    }

    val banner =
      "$stageLabel | ${result?.modelId ?: "no-model"} | det=${result?.trackedTargets?.size ?: 0}"
    val bw = textPaint.measureText(banner) + 16f
    canvas.drawRect(8f, 8f, (8f + bw).coerceAtMost(w - 8f), 36f, labelBgPaint)
    canvas.drawText(banner, 14f, 29f, textPaint)
    return copy
  }
}

private class SingleStreamMp4Writer(
  private val role: String,
  private val outputFile: File,
  rawWidth: Int,
  rawHeight: Int,
  private val fps: Int,
) {
  private val width = ((rawWidth / 2) * 2).coerceAtLeast(160)
  private val height = ((rawHeight / 2) * 2).coerceAtLeast(120)

  private val running = AtomicBoolean(false)
  private val queue = ArrayBlockingQueue<Pair<Bitmap, Long>>(4)
  private val executor =
    Executors.newSingleThreadExecutor { r -> Thread(r, "BenchmarkRec-$role") }

  private var codec: MediaCodec? = null
  private var surface: Surface? = null
  private var muxer: MediaMuxer? = null
  private var pendingOutputFormat: MediaFormat? = null
  private var trackIdx = -1
  private var muxerStarted = false
  @Volatile private var framesWritten = 0L
  @Volatile private var samplesWritten = 0L
  private var firstPtsUs = -1L
  private var lastPtsUs = 0L
  private var workerFuture: java.util.concurrent.Future<*>? = null
  private val cleanedUp = AtomicBoolean(false)

  val isActive: Boolean
    get() = running.get()

  fun start() {
    val ok =
      runCatching {
          val format =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
              setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
              )
              setInteger(
                MediaFormat.KEY_BIT_RATE,
                (width * height * 4).coerceIn(900_000, 6_000_000),
              )
              setInteger(MediaFormat.KEY_FRAME_RATE, fps)
              setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
          val mc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
          mc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
          val surf = mc.createInputSurface()
          val mx = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
          mc.start()
          codec = mc
          surface = surf
          muxer = mx
          running.set(true)
          true
        }
        .getOrElse { err ->
          AutopilotLog.warn(SubsystemTag.VIDEO, "$role MP4 recorder init failed: ${err.message}")
          cleanupCodec()
          false
        }

    if (ok) {
      workerFuture = executor.submit { drainLoop() }
    }
  }

  fun enqueueFrame(bitmap: Bitmap, ptsUs: Long) {
    if (!running.get()) return
    val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
    enqueueFrameOwned(copy, ptsUs)
  }

  fun enqueueFrameOwned(ownedBitmap: Bitmap, ptsUs: Long) {
    if (!running.get()) {
      ownedBitmap.recycle()
      return
    }
    if (!queue.offer(ownedBitmap to ptsUs)) {
      val dropped = queue.poll()
      dropped?.first?.recycle()
      if (!queue.offer(ownedBitmap to ptsUs)) {
        ownedBitmap.recycle()
      }
    }
  }

  private fun drainLoop() {
    val mc = codec ?: return
    val surf = surface ?: return
    while (running.get() || queue.isNotEmpty()) {
      val item = queue.poll(40L, TimeUnit.MILLISECONDS) ?: continue
      val bmp = item.first
      val ptsUs = item.second
      try {
        if (firstPtsUs < 0L) firstPtsUs = ptsUs
        val monotonicPts =
          if (ptsUs <= lastPtsUs && framesWritten > 0L) lastPtsUs + (1_000_000L / fps) else ptsUs
        lastPtsUs = monotonicPts
        val canvas = surf.lockCanvas(Rect(0, 0, width, height))
        try {
          canvas.drawBitmap(bmp, null, Rect(0, 0, width, height), null)
        } finally {
          surf.unlockCanvasAndPost(canvas)
        }
        framesWritten++
        drainEncoder(mc, eos = false)
      } catch (t: Throwable) {
        AutopilotLog.warn(SubsystemTag.VIDEO, "$role encode error: ${t.message}")
      } finally {
        bmp.recycle()
      }
    }
    if (framesWritten > 0L) {
      runCatching {
        mc.signalEndOfInputStream()
        drainEncoder(mc, eos = true)
      }
    }
    cleanupCodec()
  }

  fun stopAndVerify(): RecordedFileInfo {
    running.set(false)
    runCatching { workerFuture?.get(2500L, TimeUnit.MILLISECONDS) }
    while (true) {
      val leftover = queue.poll() ?: break
      leftover.first.recycle()
    }
    cleanupCodec()
    executor.shutdownNow()

    if (samplesWritten <= 0L) {
      runCatching { if (outputFile.exists()) outputFile.delete() }
      return RecordedFileInfo(
        role = role,
        filePath = outputFile.absolutePath,
        fileName = outputFile.name,
        durationMs = 0L,
        frameCount = 0L,
        width = width,
        height = height,
        fileSizeBytes = 0L,
        integrityPassed = false,
        notes = "No video frames encoded",
      )
    }

    val (integrityOk, durationMs, note) = verifyMp4File(outputFile)
    return RecordedFileInfo(
      role = role,
      filePath = outputFile.absolutePath,
      fileName = outputFile.name,
      durationMs = durationMs.coerceAtLeast(if (framesWritten > 0) (framesWritten * 1000L / fps) else 0L),
      frameCount = framesWritten,
      width = width,
      height = height,
      fileSizeBytes = if (outputFile.exists()) outputFile.length() else 0L,
      integrityPassed = integrityOk && framesWritten > 0L,
      notes = note,
    )
  }

  private fun drainEncoder(mc: MediaCodec, eos: Boolean) {
    val info = MediaCodec.BufferInfo()
    val timeoutUs = if (eos) 10_000L else 0L
    val eosDeadlineNs = System.nanoTime() + 500_000_000L
    var emptyEosPolls = 0
    var spins = 0
    while (spins < 64) {
      spins++
      val outIdx = mc.dequeueOutputBuffer(info, timeoutUs)
      when {
        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
          if (!eos) break
          emptyEosPolls++
          if (System.nanoTime() >= eosDeadlineNs || emptyEosPolls >= 20) break
        }
        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          pendingOutputFormat = mc.outputFormat
        }
        outIdx >= 0 -> {
          emptyEosPolls = 0
          val encoded = mc.getOutputBuffer(outIdx)
          if (
            encoded != null &&
              (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
              info.size > 0
          ) {
            val m = muxer
            if (m != null && !muxerStarted) {
              val fmt = pendingOutputFormat ?: mc.outputFormat
              trackIdx = m.addTrack(fmt)
              m.start()
              muxerStarted = true
            }
            if (muxerStarted && trackIdx >= 0) {
              encoded.position(info.offset)
              encoded.limit(info.offset + info.size)
              m?.writeSampleData(trackIdx, encoded, info)
              samplesWritten++
            }
          }
          mc.releaseOutputBuffer(outIdx, false)
          if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
        }
      }
    }
  }

  @Synchronized
  private fun cleanupCodec() {
    if (!cleanedUp.compareAndSet(false, true)) return
    runCatching { codec?.stop() }
    runCatching { codec?.release() }
    codec = null
    runCatching { surface?.release() }
    surface = null
    if (muxerStarted && samplesWritten > 0L) {
      runCatching { muxer?.stop() }
    }
    runCatching { muxer?.release() }
    muxer = null
    pendingOutputFormat = null
    muxerStarted = false
    trackIdx = -1
  }

  private fun verifyMp4File(file: File): Triple<Boolean, Long, String> {
    if (!file.exists() || file.length() < 512L) {
      return Triple(false, 0L, "Empty or missing file")
    }
    val extractor = MediaExtractor()
    return try {
      extractor.setDataSource(file.absolutePath)
      for (i in 0 until extractor.trackCount) {
        val fmt = extractor.getTrackFormat(i)
        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
        if (mime.startsWith("video/")) {
          val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
          return Triple(true, durUs / 1000L, "Verified MP4 video track ($mime)")
        }
      }
      Triple(false, 0L, "No video track in container")
    } catch (t: Throwable) {
      Triple(false, 0L, "MP4 verify failed: ${t.message}")
    } finally {
      runCatching { extractor.release() }
    }
  }
}
