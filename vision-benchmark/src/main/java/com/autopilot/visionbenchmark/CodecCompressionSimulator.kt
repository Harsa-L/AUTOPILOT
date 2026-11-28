package com.autopilot.visionbenchmark

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import com.autopilot.core.common.AutopilotLog
import com.autopilot.core.common.SubsystemTag
import com.autopilot.video.AnnexBVideoDecoder
import kotlin.random.Random

data class CodecLoopbackResult(
  val bitmap: Bitmap,
  val elapsedMs: Float,
  val statusLabel: String,
)

/**
 * Optional real MediaCodec H.264 / H.265 encoder -> decoder loopback that injects authentic
 * quantization, bitrate starvation, keyframe interval, and dropped-P-frame artifacts.
 * Safely falls back without crashing if hardware codec rejects an unusual resolution or lacks support.
 */
class CodecCompressionSimulator : AutoCloseable {

  private var encoder: MediaCodec? = null
  private var encoderSurface: Surface? = null
  private var decoder: AnnexBVideoDecoder? = null
  private var configuredCodec: CodecType = CodecType.NONE
  private var configuredWidth: Int = 0
  private var configuredHeight: Int = 0
  private var configuredBitrateKbps: Int = 0
  private var configuredIFrameSec: Int = 1
  private var lastGoodBitmap: Bitmap? = null
  private var frameCounter: Long = 0L

  val supportedCodecs: Set<CodecType> by lazy { discoverSupportedCodecs() }

  fun applyIfEnabled(
    input: Bitmap,
    config: ManualDegradationConfig,
    seed: Long,
  ): CodecLoopbackResult {
    if (!config.codecLoopbackEnabled || config.codecType == CodecType.NONE) {
      releaseCodecs()
      return CodecLoopbackResult(input, 0f, "Disabled")
    }
    if (!supportedCodecs.contains(config.codecType)) {
      return CodecLoopbackResult(input, 0f, "C++ Fallback (${config.codecType.label} unsupported)")
    }

    val startNs = System.nanoTime()
    val w = (input.width / 2) * 2
    val h = (input.height / 2) * 2
    if (w < 64 || h < 64) {
      return CodecLoopbackResult(input, 0f, "Bypassed (<64px)")
    }

    if (
      encoder == null ||
        configuredCodec != config.codecType ||
        configuredWidth != w ||
        configuredHeight != h ||
        configuredBitrateKbps != config.bitrateKbps ||
        configuredIFrameSec != config.iFrameIntervalSec
    ) {
      if (!initCodecLoopback(config.codecType, w, h, config.bitrateKbps, config.iFrameIntervalSec, config.targetFps)) {
        return CodecLoopbackResult(input, 0f, "C++ Fallback (${config.codecType.label} init err)")
      }
    }

    val enc = encoder ?: return CodecLoopbackResult(input, 0f, "C++ Fallback")
    val surf = encoderSurface ?: return CodecLoopbackResult(input, 0f, "C++ Fallback")
    val dec = decoder ?: return CodecLoopbackResult(input, 0f, "C++ Fallback")

    return runCatching {
        val canvas = surf.lockCanvas(Rect(0, 0, w, h))
        try {
          canvas.drawBitmap(input, null, Rect(0, 0, w, h), null)
        } finally {
          surf.unlockCanvasAndPost(canvas)
        }
        frameCounter++

        val info = MediaCodec.BufferInfo()
        var decodedBitmap: Bitmap? = null
        val dropSeed = Random(seed xor frameCounter)
        val allowDropNonIdr =
          config.transmission.keyframeDropEnabled &&
            (frameCounter % 4L == 0L || dropSeed.nextFloat() < 0.25f)

        var spins = 0
        while (spins < 6) {
          spins++
          val outIdx = enc.dequeueOutputBuffer(info, 3_500L)
          if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
          if (outIdx >= 0) {
            val buf = enc.getOutputBuffer(outIdx)
            if (buf != null && info.size > 0) {
              val bytes = ByteArray(info.size)
              buf.position(info.offset)
              buf.get(bytes)
              val isCsdOrKey =
                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 ||
                  (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
              if (isCsdOrKey || !allowDropNonIdr) {
                val bmp = dec.decodeAccessUnit(bytes, info.presentationTimeUs)
                if (bmp != null) {
                  decodedBitmap = bmp
                }
              }
            }
            enc.releaseOutputBuffer(outIdx, false)
            if (decodedBitmap != null) break
          }
        }

        if (decodedBitmap != null) {
          lastGoodBitmap = decodedBitmap
        }
        val outBmp = decodedBitmap ?: lastGoodBitmap ?: input
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000f
        CodecLoopbackResult(
          bitmap = outBmp,
          elapsedMs = elapsedMs,
          statusLabel = "${config.codecType.label} @ ${config.bitrateKbps} kbps",
        )
      }
      .getOrElse { err ->
        AutopilotLog.warn(SubsystemTag.VIDEO, "Benchmark codec loopback fallback: ${err.message}")
        releaseCodecs()
        CodecLoopbackResult(input, 0f, "C++ Fallback (Codec err)")
      }
  }

  private fun initCodecLoopback(
    codecType: CodecType,
    w: Int,
    h: Int,
    bitrateKbps: Int,
    iFrameSec: Int,
    fps: Int,
  ): Boolean {
    releaseCodecs()
    val mime = codecType.mimeType ?: return false
    return runCatching {
        val format =
          MediaFormat.createVideoFormat(mime, w, h).apply {
            setInteger(
              MediaFormat.KEY_COLOR_FORMAT,
              MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps.coerceIn(64, 12_000) * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceIn(5, 60))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameSec.coerceIn(1, 10))
          }
        val mc = MediaCodec.createEncoderByType(mime)
        mc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surf = mc.createInputSurface()
        mc.start()
        encoder = mc
        encoderSurface = surf
        decoder = AnnexBVideoDecoder(isHevc = codecType == CodecType.H265_HEVC)
        configuredCodec = codecType
        configuredWidth = w
        configuredHeight = h
        configuredBitrateKbps = bitrateKbps
        configuredIFrameSec = iFrameSec
        true
      }
      .getOrElse { err ->
        AutopilotLog.warn(SubsystemTag.VIDEO, "Unable to open $mime loopback: ${err.message}")
        releaseCodecs()
        false
      }
  }

  private fun discoverSupportedCodecs(): Set<CodecType> =
    runCatching {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        val hasAvcEnc = list.any { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
        val hasAvcDec = list.any { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
        val hasHevcEnc = list.any { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } }
        val hasHevcDec = list.any { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } }
        buildSet {
          add(CodecType.NONE)
          if (hasAvcEnc && hasAvcDec) add(CodecType.H264_AVC)
          if (hasHevcEnc && hasHevcDec) add(CodecType.H265_HEVC)
        }
      }
      .getOrDefault(setOf(CodecType.NONE))

  fun releaseCodecs() {
    configuredCodec = CodecType.NONE
    runCatching { encoder?.stop() }
    runCatching { encoder?.release() }
    encoder = null
    runCatching { encoderSurface?.release() }
    encoderSurface = null
    runCatching { decoder?.release() }
    decoder = null
    lastGoodBitmap = null
  }

  override fun close() {
    releaseCodecs()
  }
}
