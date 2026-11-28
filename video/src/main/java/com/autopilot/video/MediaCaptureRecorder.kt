package com.autopilot.video

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import com.autopilot.core.common.AutopilotLog
import com.autopilot.core.common.SubsystemTag
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CaptureSummary(
  val displayName: String,
  val frameCount: Long,
  val durationSec: Long,
)

/**
 * Play-Policy compliant scoped media saver (zero READ_EXTERNAL_STORAGE / READ_MEDIA_* permissions).
 * Uses MediaStore with IS_PENDING on API 29+ and app-scoped Pictures/Movies storage on API 26-28.
 */
object ScopedMediaCapture {

  fun saveSnapshotJpeg(context: Context, bitmap: Bitmap): Result<String> =
    runCatching {
      val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
      val fileName = "AUTOPILOT_$stamp.jpg"

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values =
          ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AUTOPILOT")
            put(MediaStore.Images.Media.IS_PENDING, 1)
          }
        val uri =
          resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create MediaStore image entry.")
        resolver.openOutputStream(uri)?.use { out ->
          check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) { "JPEG encode failed" }
        } ?: error("Unable to open image output stream.")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        fileName
      } else {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "AUTOPILOT")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        file.outputStream().use { out ->
          check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) { "JPEG encode failed" }
        }
        fileName
      }
    }
}

/**
 * Hardware H.264 MediaCodec + MediaMuxer local MP4 video recorder driven from decoded FPV Bitmaps.
 */
class Mp4FrameRecorder {

  private var encoder: MediaCodec? = null
  private var inputSurface: Surface? = null
  private var muxer: MediaMuxer? = null
  private var pendingOutputFormat: MediaFormat? = null
  private var trackIndex = -1
  private var muxerStarted = false
  private var pendingUri: Uri? = null
  private var pendingPfd: ParcelFileDescriptor? = null
  private var displayName: String = ""
  private var startedAtMs: Long = 0L
  private var recordedFrames: Long = 0L
  private var samplesWritten: Long = 0L
  private var width = 0
  private var height = 0

  val isRecording: Boolean
    get() = encoder != null

  val framesWritten: Long
    get() = recordedFrames

  fun durationSec(nowMs: Long = System.currentTimeMillis()): Long =
    if (startedAtMs <= 0L) 0L else ((nowMs - startedAtMs) / 1000L).coerceAtLeast(0L)

  fun start(context: Context, frameWidth: Int, frameHeight: Int): Boolean {
    if (encoder != null) return true
    // Hardware H.264 encoders require even dimensions.
    val alignedW = (frameWidth.coerceAtLeast(160) / 2) * 2
    val alignedH = (frameHeight.coerceAtLeast(120) / 2) * 2
    width = alignedW
    height = alignedH
    recordedFrames = 0L
    samplesWritten = 0L
    pendingOutputFormat = null
    trackIndex = -1
    muxerStarted = false
    startedAtMs = System.currentTimeMillis()
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAtMs))
    displayName = "AUTOPILOT_$stamp.mp4"

    return runCatching {
        val activeMuxer: MediaMuxer
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          val resolver = context.contentResolver
          val values =
            ContentValues().apply {
              put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
              put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
              put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/AUTOPILOT")
              put(MediaStore.Video.Media.IS_PENDING, 1)
            }
          val uri =
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
              ?: error("Unable to create MediaStore video entry.")
          val pfd = resolver.openFileDescriptor(uri, "w") ?: error("Unable to open video descriptor.")
          pendingUri = uri
          pendingPfd = pfd
          activeMuxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
          val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "AUTOPILOT")
          if (!dir.exists()) dir.mkdirs()
          val outFile = File(dir, displayName)
          activeMuxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }
        muxer = activeMuxer

        val format =
          MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, alignedW, alignedH).apply {
            setInteger(
              MediaFormat.KEY_COLOR_FORMAT,
              MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, (alignedW * alignedH * 4).coerceIn(1_200_000, 8_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, 25)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
          }

        val mc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = mc.createInputSurface()
        mc.start()

        encoder = mc
        inputSurface = surface
        AutopilotLog.info(SubsystemTag.VIDEO, "Started MP4 recording $displayName (${alignedW}x$alignedH)")
        true
      }
      .getOrElse { err ->
        AutopilotLog.warn(SubsystemTag.VIDEO, "MP4 recorder start failed: ${err.message}")
        cleanupResources(context, finalizeUri = false)
        false
      }
  }

  @Synchronized
  fun appendFrame(bitmap: Bitmap) {
    val mc = encoder ?: return
    val surface = inputSurface ?: return
    runCatching {
      val canvas = surface.lockCanvas(Rect(0, 0, width, height))
      try {
        canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height), null)
      } finally {
        surface.unlockCanvasAndPost(canvas)
      }
      recordedFrames++
      drainEncoder(mc, endOfStream = false)
    }.onFailure { err ->
      AutopilotLog.warn(SubsystemTag.VIDEO, "Frame encode warning: ${err.message}")
    }
  }

  @Synchronized
  fun stop(context: Context): CaptureSummary? {
    val mc = encoder ?: return null
    val summary =
      CaptureSummary(
        displayName = displayName,
        frameCount = recordedFrames,
        durationSec = durationSec(),
      )
    if (recordedFrames > 0L) {
      runCatching {
        mc.signalEndOfInputStream()
        drainEncoder(mc, endOfStream = true)
      }
    }
    cleanupResources(context, finalizeUri = samplesWritten > 0L)
    AutopilotLog.info(SubsystemTag.VIDEO, "Stopped MP4 recording ${summary.displayName} (${summary.frameCount} frames)")
    return summary
  }

  private fun drainEncoder(mc: MediaCodec, endOfStream: Boolean) {
    val info = MediaCodec.BufferInfo()
    val timeoutUs = if (endOfStream) 10_000L else 0L
    val eosDeadlineNs = System.nanoTime() + 500_000_000L
    var emptyEosPolls = 0
    while (true) {
      val outIdx = mc.dequeueOutputBuffer(info, timeoutUs)
      when {
        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
          if (!endOfStream) break
          emptyEosPolls++
          if (System.nanoTime() >= eosDeadlineNs || emptyEosPolls >= 20) break
        }
        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          pendingOutputFormat = mc.outputFormat
        }
        outIdx >= 0 -> {
          emptyEosPolls = 0
          val encoded = mc.getOutputBuffer(outIdx)
          if (encoded != null && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
            val m = muxer
            if (m != null && !muxerStarted) {
              val fmt = pendingOutputFormat ?: mc.outputFormat
              trackIndex = m.addTrack(fmt)
              m.start()
              muxerStarted = true
            }
            if (muxerStarted && trackIndex >= 0) {
              encoded.position(info.offset)
              encoded.limit(info.offset + info.size)
              m?.writeSampleData(trackIndex, encoded, info)
              samplesWritten++
            }
          }
          mc.releaseOutputBuffer(outIdx, false)
          if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
        }
      }
    }
  }

  private fun cleanupResources(context: Context, finalizeUri: Boolean) {
    runCatching { encoder?.stop() }
    runCatching { encoder?.release() }
    encoder = null
    runCatching { inputSurface?.release() }
    inputSurface = null
    if (muxerStarted && samplesWritten > 0L) {
      runCatching { muxer?.stop() }
    }
    runCatching { muxer?.release() }
    muxer = null
    pendingOutputFormat = null
    muxerStarted = false
    trackIndex = -1

    runCatching { pendingPfd?.close() }
    pendingPfd = null

    val uri = pendingUri
    pendingUri = null
    if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      runCatching {
        if (finalizeUri) {
          val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
          context.contentResolver.update(uri, done, null, null)
        } else {
          context.contentResolver.delete(uri, null, null)
        }
      }
    }
  }
}
