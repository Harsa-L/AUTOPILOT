package com.autopilot.video

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import com.autopilot.core.common.AutopilotLog
import com.autopilot.core.common.SubsystemTag
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/**
 * Hardware-accelerated MediaCodec decoder for Annex-B H.264 (AVC) and H.265 (HEVC)
 * payloads yielding ARGB_8888 Bitmaps for full-screen Compose rendering, Vision YOLO
 * inference, snapshots, and MP4 recording.
 */
class AnnexBVideoDecoder(private val isHevc: Boolean) {

  private var codec: MediaCodec? = null
  private var imageReader: ImageReader? = null
  private var readerThread: HandlerThread? = null
  private var csdCached: ByteArray? = null
  private var configured = false
  private var width = 1280
  private var height = 720
  private var argbScratch = IntArray(0)
  private val latestBitmap = AtomicReference<Bitmap?>(null)

  private val mimeType: String
    get() = if (isHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

  fun decodeAccessUnit(annexB: ByteArray, timestampUs: Long): Bitmap? {
    if (annexB.size < 5) return null

    // Scan Annex-B stream for VPS/SPS/PPS configuration NALs before configuring MediaCodec.
    val extractedCsd = extractConfigNals(annexB, isHevc)
    if (extractedCsd != null) {
      csdCached = extractedCsd
    }

    if (!configured) {
      val csd = csdCached ?: return null
      if (!initDecoder(csd)) return null
    }

    val activeCodec = codec ?: return null
    runCatching {
      val inIdx = activeCodec.dequeueInputBuffer(8_000L)
      if (inIdx >= 0) {
        val inBuf = activeCodec.getInputBuffer(inIdx)
        if (inBuf != null && inBuf.capacity() >= annexB.size) {
          inBuf.clear()
          inBuf.put(annexB)
          activeCodec.queueInputBuffer(inIdx, 0, annexB.size, timestampUs, 0)
        } else {
          activeCodec.queueInputBuffer(inIdx, 0, 0, timestampUs, 0)
        }
      }

      val info = MediaCodec.BufferInfo()
      while (true) {
        val outIdx = activeCodec.dequeueOutputBuffer(info, 2_000L)
        when {
          outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            val fmt = activeCodec.outputFormat
            val newW = fmt.getInteger(MediaFormat.KEY_WIDTH)
            val newH = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            if (newW > 0 && newH > 0 && (newW != width || newH != height)) {
              width = newW
              height = newH
            }
          }
          outIdx >= 0 -> {
            val render = (info.size > 0) && ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0)
            activeCodec.releaseOutputBuffer(outIdx, render)
          }
          else -> break
        }
      }
    }.onFailure { err ->
      AutopilotLog.warn(SubsystemTag.VIDEO, "MediaCodec decode cycle reset: ${err.message}")
      releaseCodecOnly()
    }

    return latestBitmap.getAndSet(null)
  }

  fun release() {
    releaseCodecOnly()
    csdCached = null
    latestBitmap.set(null)
  }

  private fun initDecoder(csd: ByteArray): Boolean {
    return runCatching {
        val thread = HandlerThread("AutopilotImageReader").apply { start() }
        readerThread = thread
        val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
        reader.setOnImageAvailableListener(
          { r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
              val bmp = yuv420ToBitmap(img)
              if (bmp != null) {
                latestBitmap.set(bmp)
              }
            } finally {
              img.close()
            }
          },
          Handler(thread.looper),
        )
        imageReader = reader

        val format =
          MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
          }
        val mc = MediaCodec.createDecoderByType(mimeType)
        mc.configure(format, reader.surface, null, 0)
        mc.start()
        codec = mc
        configured = true
        AutopilotLog.info(SubsystemTag.VIDEO, "Initialized $mimeType hardware decoder (${width}x$height)")
        true
      }
      .getOrElse { err ->
        AutopilotLog.warn(SubsystemTag.VIDEO, "Failed to init $mimeType decoder: ${err.message}")
        releaseCodecOnly()
        false
      }
  }

  private fun releaseCodecOnly() {
    configured = false
    runCatching { codec?.stop() }
    runCatching { codec?.release() }
    codec = null
    runCatching { imageReader?.close() }
    imageReader = null
    runCatching { readerThread?.quitSafely() }
    readerThread = null
  }

  private fun yuv420ToBitmap(image: Image): Bitmap? {
    val w = image.width
    val h = image.height
    if (w <= 0 || h <= 0 || image.planes.size < 3) return null

    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val yBuf = yPlane.buffer
    val uBuf = uPlane.buffer
    val vBuf = vPlane.buffer

    val yRowStride = yPlane.rowStride
    val uRowStride = uPlane.rowStride
    val uPixStride = uPlane.pixelStride
    val vRowStride = vPlane.rowStride
    val vPixStride = vPlane.pixelStride

    val total = w * h
    if (argbScratch.size != total) {
      argbScratch = IntArray(total)
    }

    for (row in 0 until h) {
      val yRowOffset = row * yRowStride
      val uvRow = row shr 1
      val uRowOffset = uvRow * uRowStride
      val vRowOffset = uvRow * vRowStride
      val outOffset = row * w
      for (col in 0 until w) {
        val uvCol = col shr 1
        val y = (yBuf.get(yRowOffset + col).toInt() and 0xFF) - 16
        val u = (uBuf.get(uRowOffset + uvCol * uPixStride).toInt() and 0xFF) - 128
        val v = (vBuf.get(vRowOffset + uvCol * vPixStride).toInt() and 0xFF) - 128

        val c = if (y < 0) 0 else y
        val r = ((298 * c + 409 * v + 128) shr 8).coerceIn(0, 255)
        val g = ((298 * c - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
        val b = ((298 * c + 516 * u + 128) shr 8).coerceIn(0, 255)
        argbScratch[outOffset + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
      }
    }
    return Bitmap.createBitmap(argbScratch, w, h, Bitmap.Config.ARGB_8888)
  }

  private fun extractConfigNals(data: ByteArray, hevc: Boolean): ByteArray? {
    val starts = mutableListOf<Int>()
    var i = 0
    while (i + 3 < data.size) {
      if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
        if (data[i + 2] == 1.toByte()) {
          starts.add(i)
          i += 3
          continue
        } else if (i + 4 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
          starts.add(i)
          i += 4
          continue
        }
      }
      i++
    }
    if (starts.isEmpty()) return null

    val csdParts = mutableListOf<ByteArray>()
    var hasSps = false
    for (idx in starts.indices) {
      val s = starts[idx]
      val scLen = if (data[s + 2] == 1.toByte()) 3 else 4
      val end = if (idx + 1 < starts.size) starts[idx + 1] else data.size
      if (s + scLen >= end) continue
      val hdr = data[s + scLen].toInt() and 0xFF
      val isCsd =
        if (hevc) {
          val naluType = (hdr shr 1) and 0x3F
          if (naluType == 33) hasSps = true
          naluType == 32 || naluType == 33 || naluType == 34
        } else {
          val naluType = hdr and 0x1F
          if (naluType == 7) hasSps = true
          naluType == 7 || naluType == 8
        }
      if (isCsd) {
        val len = end - s
        val unit = ByteArray(4 + (len - scLen))
        unit[0] = 0
        unit[1] = 0
        unit[2] = 0
        unit[3] = 1
        System.arraycopy(data, s + scLen, unit, 4, len - scLen)
        csdParts.add(unit)
      }
    }
    if (!hasSps || csdParts.isEmpty()) return null
    val totalLen = csdParts.sumOf { it.size }
    val out = ByteArray(totalLen)
    var pos = 0
    for (part in csdParts) {
      System.arraycopy(part, 0, out, pos, part.size)
      pos += part.size
    }
    return out
  }
}
