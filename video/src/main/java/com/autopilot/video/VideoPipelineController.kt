package com.autopilot.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaCodecList
import android.media.MediaFormat
import com.autopilot.core.common.AutopilotDispatchers
import com.autopilot.core.common.AutopilotLog
import com.autopilot.core.common.SubsystemTag
import com.autopilot.drone.protocolapi.DroneProtocol
import com.autopilot.drone.protocolapi.VideoFramePacket
import com.autopilot.drone.protocolapi.VideoTransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class VideoMetrics(
  val fps: Float = 0f,
  val decodeLatencyMs: Float = 0f,
  val frameWidth: Int = 0,
  val frameHeight: Int = 0,
  val framesDecoded: Long = 0L,
  val transport: VideoTransportType = VideoTransportType.NONE,
  val codecHardwareInfo: String = "",
)

sealed interface VideoStreamState {
  data object Idle : VideoStreamState

  data class WaitingForKeyframe(
    val transport: VideoTransportType,
    val detail: String,
  ) : VideoStreamState

  data class ActiveFrame(
    val bitmap: Bitmap,
    val sequence: Long,
    val metrics: VideoMetrics,
    val isSyntheticHarness: Boolean,
  ) : VideoStreamState

  data class Error(val message: String) : VideoStreamState
}

data class VideoCaptureState(
  val isRecording: Boolean = false,
  val recordingDurationSec: Long = 0L,
  val recordedFrames: Long = 0L,
  val lastNotice: String? = null,
)

enum class MvFilterPreset(val label: String) {
  NATURAL("Natural"),
  WARM_FPV("Warm FPV"),
  HIGH_CONTRAST("Vivid"),
  MONOCHROME("Mono"),
}

data class VideoFrameTransform(
  val rotationDeg: Int = 0,
  val mirrorHorizontal: Boolean = false,
  val mvFilter: MvFilterPreset = MvFilterPreset.NATURAL,
)

/**
 * Independent low-latency video decoding, snapshot, and MP4 recording controller.
 * Keeps transport, hardware MediaCodec decoding, transforms, and capture decoupled from UI and vision.
 */
class VideoPipelineController(
  private val scope: CoroutineScope,
  private val dispatchers: AutopilotDispatchers,
) {
  private val _streamState = MutableStateFlow<VideoStreamState>(VideoStreamState.Idle)
  val streamState: StateFlow<VideoStreamState> = _streamState.asStateFlow()

  private val _captureState = MutableStateFlow(VideoCaptureState())
  val captureState: StateFlow<VideoCaptureState> = _captureState.asStateFlow()

  @Volatile
  private var frameTransform: VideoFrameTransform = VideoFrameTransform()

  private var streamJob: Job? = null
  private var staleWatchdogJob: Job? = null
  private var annexBDecoder: AnnexBVideoDecoder? = null
  private var activeRecorder: Mp4FrameRecorder? = null
  private var recorderContext: Context? = null
  private var reusablePixels = IntArray(0)
  private var lastFpsWindowStartMs = 0L
  private var lastDecodedFrameAtMs = 0L
  private var framesInWindow = 0
  private var currentFps = 0f
  private var totalDecoded = 0L

  fun updateFrameTransform(transform: VideoFrameTransform) {
    frameTransform =
      transform.copy(rotationDeg = ((transform.rotationDeg % 360) + 360) % 360)
  }

  fun queryHardwareDecoderSupport(transport: VideoTransportType): String {
    val mime =
      when (transport) {
        VideoTransportType.RTSP_H264,
        VideoTransportType.RAW_UDP_H264,
        VideoTransportType.LEWEI_TCP_H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
        VideoTransportType.RAW_UDP_H265,
        VideoTransportType.RTSP_H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
        VideoTransportType.RAW_UDP_JPEG,
        VideoTransportType.RTP_JPEG,
        VideoTransportType.RTSP_MJPEG -> "image/jpeg (Skia/libjpeg-turbo)"
        VideoTransportType.SYNTHETIC_TEST_PATTERN -> "Synthetic RGB24 Calibration Pattern"
        VideoTransportType.NONE -> return "No video transport"
      }
    if (!mime.startsWith("video/")) return mime
    val codec =
      runCatching {
          MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .codecInfos
            .firstOrNull { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
            ?.name
        }
        .getOrNull()
    return codec ?: "No matching MediaCodec for $mime"
  }

  fun attachToProtocol(
    protocol: DroneProtocol,
    transportOverride: VideoTransportType? = null,
    onFrameDecoded: ((Bitmap, Boolean) -> Unit)? = null,
  ) {
    detach()
    val transport = transportOverride ?: protocol.descriptor.videoTransport
    val codecInfo = queryHardwareDecoderSupport(transport)
    totalDecoded = 0L
    framesInWindow = 0
    currentFps = 0f
    lastFpsWindowStartMs = System.currentTimeMillis()
    lastDecodedFrameAtMs = lastFpsWindowStartMs

    annexBDecoder =
      when (transport) {
        VideoTransportType.RTSP_H264,
        VideoTransportType.RAW_UDP_H264,
        VideoTransportType.LEWEI_TCP_H264 -> AnnexBVideoDecoder(isHevc = false)
        VideoTransportType.RAW_UDP_H265,
        VideoTransportType.RTSP_H265 -> AnnexBVideoDecoder(isHevc = true)
        else -> null
      }

    _streamState.value =
      VideoStreamState.WaitingForKeyframe(
        transport = transport,
        detail =
          when (transport) {
            VideoTransportType.RTSP_H264,
            VideoTransportType.RTSP_H265,
            VideoTransportType.RTSP_MJPEG ->
              "Negotiating RTSP stream (${protocol.descriptor.rtspPath ?: "port ${protocol.descriptor.videoPort}"}) — $codecInfo"
            VideoTransportType.LEWEI_TCP_H264 ->
              "Connecting Lewei TCP :${protocol.descriptor.videoPort} H.264 — $codecInfo"
            VideoTransportType.SYNTHETIC_TEST_PATTERN ->
              "Initializing Isolated Loopback Test Pattern…"
            VideoTransportType.NONE -> "Protocol does not expose a video stream."
            else -> "Listening on UDP :${protocol.descriptor.videoPort} ($transport) — $codecInfo"
          },
      )

    streamJob =
      protocol.videoFrames()
        .onEach { packet ->
          val startNs = System.nanoTime()
          val rawBitmap = decodePacket(packet)
          val bitmap = rawBitmap?.let { applyVideoTransform(it, frameTransform) }
          if (bitmap != null) {
            val decodeMs = (System.nanoTime() - startNs) / 1_000_000f
            totalDecoded++
            framesInWindow++
            val now = System.currentTimeMillis()
            lastDecodedFrameAtMs = now
            val elapsed = now - lastFpsWindowStartMs
            if (elapsed >= 1000L) {
              currentFps = (framesInWindow * 1000f) / elapsed.toFloat()
              framesInWindow = 0
              lastFpsWindowStartMs = now
            }
            val metrics =
              VideoMetrics(
                fps = currentFps,
                decodeLatencyMs = decodeMs,
                frameWidth = bitmap.width,
                frameHeight = bitmap.height,
                framesDecoded = totalDecoded,
                transport = packet.transport,
                codecHardwareInfo = codecInfo,
              )
            _streamState.value =
              VideoStreamState.ActiveFrame(
                bitmap = bitmap,
                sequence = packet.sequence,
                metrics = metrics,
                isSyntheticHarness = packet.isSyntheticHarness,
              )
            activeRecorder?.let { rec ->
              if (rec.isRecording) {
                rec.appendFrame(bitmap)
                _captureState.value =
                  _captureState.value.copy(
                    isRecording = true,
                    recordingDurationSec = rec.durationSec(now),
                    recordedFrames = rec.framesWritten,
                  )
              }
            }
            onFrameDecoded?.invoke(bitmap, packet.isSyntheticHarness)
          }
        }
        .flowOn(dispatchers.default)
        .launchIn(scope)

    staleWatchdogJob =
      scope.launch {
        while (true) {
          delay(1000L)
          if (_streamState.value is VideoStreamState.ActiveFrame &&
            System.currentTimeMillis() - lastDecodedFrameAtMs > 3500L
          ) {
            _streamState.value =
              VideoStreamState.WaitingForKeyframe(
                transport = transport,
                detail = "Video stream stalled (>3.5s) — awaiting next frame…",
              )
          }
        }
      }

    AutopilotLog.info(SubsystemTag.VIDEO, "VideoPipeline attached to ${protocol.descriptor.id} ($transport · $codecInfo)")
  }

  fun captureSnapshot(context: Context) {
    val active = _streamState.value as? VideoStreamState.ActiveFrame
    if (active == null) {
      _captureState.value = _captureState.value.copy(lastNotice = "Wait for active video frame before snapshot")
      return
    }
    val appCtx = context.applicationContext
    val frameCopy = active.bitmap.copy(Bitmap.Config.ARGB_8888, false)
    scope.launch(dispatchers.io) {
      val res = ScopedMediaCapture.saveSnapshotJpeg(appCtx, frameCopy)
      res.onSuccess { name ->
        _captureState.value = _captureState.value.copy(lastNotice = "Snapshot saved: $name")
      }.onFailure { err ->
        _captureState.value = _captureState.value.copy(lastNotice = "Snapshot failed: ${err.message}")
      }
    }
  }

  fun toggleRecording(context: Context) {
    val current = activeRecorder
    if (current != null && current.isRecording) {
      stopRecording()
      return
    }
    val active = _streamState.value as? VideoStreamState.ActiveFrame
    if (active == null) {
      _captureState.value = _captureState.value.copy(lastNotice = "Start video stream before recording")
      return
    }
    val appCtx = context.applicationContext
    recorderContext = appCtx
    val recorder = Mp4FrameRecorder()
    scope.launch(dispatchers.io) {
      val started = recorder.start(appCtx, active.bitmap.width, active.bitmap.height)
      if (started) {
        activeRecorder = recorder
        _captureState.value =
          VideoCaptureState(
            isRecording = true,
            recordingDurationSec = 0L,
            recordedFrames = 0L,
            lastNotice = "Recording MP4 started",
          )
      } else {
        _captureState.value =
          _captureState.value.copy(isRecording = false, lastNotice = "Unable to start hardware MP4 recorder")
      }
    }
  }

  fun stopRecording() {
    val recorder = activeRecorder ?: return
    val ctx = recorderContext ?: return
    activeRecorder = null
    scope.launch(dispatchers.io) {
      val summary = recorder.stop(ctx)
      _captureState.value =
        VideoCaptureState(
          isRecording = false,
          recordingDurationSec = 0L,
          recordedFrames = 0L,
          lastNotice = summary?.let { "Saved ${it.displayName} (${it.durationSec}s)" },
        )
    }
  }

  fun clearNotice() {
    _captureState.value = _captureState.value.copy(lastNotice = null)
  }

  fun detach() {
    if (activeRecorder?.isRecording == true) {
      val rec = activeRecorder
      val ctx = recorderContext
      activeRecorder = null
      if (rec != null && ctx != null) {
        scope.launch(dispatchers.io) {
          rec.stop(ctx)
          _captureState.value = VideoCaptureState(isRecording = false)
        }
      }
    }
    staleWatchdogJob?.cancel()
    staleWatchdogJob = null
    streamJob?.cancel()
    streamJob = null
    annexBDecoder?.release()
    annexBDecoder = null
    _streamState.value = VideoStreamState.Idle
  }

  private fun decodePacket(packet: VideoFramePacket): Bitmap? {
    return when (packet.transport) {
      VideoTransportType.RAW_UDP_JPEG,
      VideoTransportType.RTP_JPEG,
      VideoTransportType.RTSP_MJPEG -> {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        BitmapFactory.decodeByteArray(packet.payload, 0, packet.payload.size, opts)
      }
      VideoTransportType.SYNTHETIC_TEST_PATTERN -> {
        decodeRgb24(packet.payload, packet.width, packet.height)
      }
      VideoTransportType.RAW_UDP_H264,
      VideoTransportType.RAW_UDP_H265,
      VideoTransportType.RTSP_H264,
      VideoTransportType.RTSP_H265,
      VideoTransportType.LEWEI_TCP_H264 -> {
        annexBDecoder?.decodeAccessUnit(packet.payload, packet.timestampUs)
      }
      VideoTransportType.NONE -> null
    }
  }

  private fun decodeRgb24(rgb: ByteArray, width: Int, height: Int): Bitmap? {
    if (width <= 0 || height <= 0 || rgb.size < width * height * 3) return null
    val total = width * height
    if (reusablePixels.size != total) {
      reusablePixels = IntArray(total)
    }
    for (i in 0 until total) {
      val base = i * 3
      val r = rgb[base].toInt() and 0xFF
      val g = rgb[base + 1].toInt() and 0xFF
      val b = rgb[base + 2].toInt() and 0xFF
      reusablePixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(reusablePixels, width, height, Bitmap.Config.ARGB_8888)
  }

  companion object {
    fun applyVideoTransform(src: Bitmap, transform: VideoFrameTransform): Bitmap {
      val rot = ((transform.rotationDeg % 360) + 360) % 360
      if (rot == 0 && !transform.mirrorHorizontal && transform.mvFilter == MvFilterPreset.NATURAL) {
        return src
      }
      val w = src.width
      val h = src.height
      if (w <= 0 || h <= 0) return src
      val swapDims = rot == 90 || rot == 270
      val outW = if (swapDims) h else w
      val outH = if (swapDims) w else h

      val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(out)
      val matrix = Matrix()
      matrix.postTranslate(-w / 2f, -h / 2f)
      if (transform.mirrorHorizontal) {
        matrix.postScale(-1f, 1f)
      }
      if (rot != 0) {
        matrix.postRotate(rot.toFloat())
      }
      matrix.postTranslate(outW / 2f, outH / 2f)

      val paint =
        when (transform.mvFilter) {
          MvFilterPreset.NATURAL -> null
          MvFilterPreset.MONOCHROME ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
              val cm = ColorMatrix().apply { setSaturation(0f) }
              colorFilter = ColorMatrixColorFilter(cm)
            }
          MvFilterPreset.HIGH_CONTRAST ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
              val sat = ColorMatrix().apply { setSaturation(1.30f) }
              val contrast =
                ColorMatrix(
                  floatArrayOf(
                    1.18f, 0f, 0f, 0f, -18f,
                    0f, 1.18f, 0f, 0f, -18f,
                    0f, 0f, 1.18f, 0f, -18f,
                    0f, 0f, 0f, 1f, 0f,
                  )
                )
              sat.postConcat(contrast)
              colorFilter = ColorMatrixColorFilter(sat)
            }
          MvFilterPreset.WARM_FPV ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
              val warm =
                ColorMatrix(
                  floatArrayOf(
                    1.12f, 0.04f, 0f, 0f, 6f,
                    0f, 1.02f, 0f, 0f, 2f,
                    0f, 0f, 0.88f, 0f, -4f,
                    0f, 0f, 0f, 1f, 0f,
                  )
                )
              colorFilter = ColorMatrixColorFilter(warm)
            }
        }
      canvas.drawBitmap(src, matrix, paint)
      return out
    }
  }
}
