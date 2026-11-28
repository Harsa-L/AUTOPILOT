package com.autopilot.visionbenchmark

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import com.autopilot.core.common.AutopilotLog
import com.autopilot.core.common.SubsystemTag
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

interface BenchmarkVideoSource : AutoCloseable {
  val sourceLabel: String

  fun start(): Boolean

  fun stop()
}

/**
 * Enumerates Android Camera2 devices and streams YUV_420_888 frames synchronously inside
 * ImageReader's handler before closing each Image so buffer lifetimes stay deterministic.
 */
class Camera2VideoSource(
  private val context: Context,
  private val captureConfig: CameraCaptureConfig,
  private val onFrame: (image: Image, timestampNs: Long, rotationDeg: Int, isFrontFacing: Boolean) -> Unit,
  private val onError: (String) -> Unit,
) : BenchmarkVideoSource {

  override val sourceLabel: String
    get() = "Phone Camera (${captureConfig.cameraId})"

  private val cameraManager: CameraManager =
    context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
  private var handlerThread: HandlerThread? = null
  private var handler: Handler? = null
  private var imageReader: ImageReader? = null
  private var cameraDevice: CameraDevice? = null
  private var captureSession: CameraCaptureSession? = null
  private val isOpen = AtomicBoolean(false)
  private var sensorOrientationDeg: Int = 90
  private var isFrontFacing: Boolean = false

  @SuppressLint("MissingPermission")
  override fun start(): Boolean {
    if (isOpen.get()) return true
    return runCatching {
        val characteristics = cameraManager.getCameraCharacteristics(captureConfig.cameraId)
        sensorOrientationDeg =
          characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val facing =
          characteristics.get(CameraCharacteristics.LENS_FACING)
            ?: CameraCharacteristics.LENS_FACING_BACK
        isFrontFacing = facing == CameraCharacteristics.LENS_FACING_FRONT

        val thread = HandlerThread("BenchmarkCamera2").apply { start() }
        val h = Handler(thread.looper)
        handlerThread = thread
        handler = h

        val reader =
          ImageReader.newInstance(
            captureConfig.captureWidth.coerceAtLeast(320),
            captureConfig.captureHeight.coerceAtLeast(240),
            ImageFormat.YUV_420_888,
            3,
          )
        reader.setOnImageAvailableListener(
          { r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
              if (isOpen.get()) {
                val ts = img.timestamp
                onFrame(img, ts, sensorOrientationDeg, isFrontFacing)
              }
            } finally {
              img.close()
            }
          },
          h,
        )
        imageReader = reader

        isOpen.set(true)
        cameraManager.openCamera(
          captureConfig.cameraId,
          object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
              if (!isOpen.get()) {
                camera.close()
                return
              }
              cameraDevice = camera
              createSession(camera, reader, h)
            }

            override fun onDisconnected(camera: CameraDevice) {
              camera.close()
              cameraDevice = null
              onError("Camera ${captureConfig.cameraId} disconnected")
            }

            override fun onError(camera: CameraDevice, error: Int) {
              camera.close()
              cameraDevice = null
              onError("Camera2 error code $error")
            }
          },
          h,
        )
        true
      }
      .getOrElse { err ->
        AutopilotLog.error(SubsystemTag.VIDEO, "Camera2 start failed: ${err.message}")
        stop()
        onError(err.message ?: "Failed to open Camera2")
        false
      }
  }

  private fun createSession(camera: CameraDevice, reader: ImageReader, h: Handler) {
    runCatching {
      @Suppress("DEPRECATION")
      camera.createCaptureSession(
        listOf(reader.surface),
        object : CameraCaptureSession.StateCallback() {
          override fun onConfigured(session: CameraCaptureSession) {
            if (!isOpen.get()) {
              session.close()
              return
            }
            captureSession = session
            runCatching {
              val req =
                camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                  addTarget(reader.surface)
                  set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                  )
                  set(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(captureConfig.targetMinFps, captureConfig.targetMaxFps),
                  )
                }
              session.setRepeatingRequest(req.build(), null, h)
            }.onFailure { err ->
              onError("Repeating preview failed: ${err.message}")
            }
          }

          override fun onConfigureFailed(session: CameraCaptureSession) {
            onError("Camera2 session configure failed")
          }
        },
        h,
      )
    }.onFailure { err ->
      onError("Camera2 createCaptureSession failed: ${err.message}")
    }
  }

  override fun stop() {
    isOpen.set(false)
    runCatching { captureSession?.stopRepeating() }
    runCatching { captureSession?.close() }
    captureSession = null
    runCatching { cameraDevice?.close() }
    cameraDevice = null
    runCatching { imageReader?.close() }
    imageReader = null
    runCatching { handlerThread?.quitSafely() }
    handlerThread = null
    handler = null
  }

  override fun close() = stop()

  companion object {
    fun enumerateCameras(context: Context): List<CameraDeviceInfo> {
      val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyList()
      return runCatching {
        manager.cameraIdList.mapNotNull { id ->
          val chars = manager.getCameraCharacteristics(id)
          val facing = chars.get(CameraCharacteristics.LENS_FACING)
          val facingLabel =
            when (facing) {
              CameraCharacteristics.LENS_FACING_BACK -> "Rear"
              CameraCharacteristics.LENS_FACING_FRONT -> "Front"
              CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
              else -> "Camera"
            }
          val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
          val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
          val sizes =
            map?.getOutputSizes(ImageFormat.YUV_420_888)
              ?.filter { it.width <= 1920 && it.height <= 1080 }
              ?.sortedByDescending { it.width * it.height }
              ?.take(8)
              ?.map { "${it.width}x${it.height}" }
              ?: listOf("1280x720", "640x480")
          val fpsRanges =
            chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
              ?.map { "${it.lower}-${it.upper}" }
              ?.distinct()
              ?: listOf("15-30")
          CameraDeviceInfo(
            cameraId = id,
            lensFacingLabel = facingLabel,
            displayLabel = "$facingLabel (#$id)",
            sensorOrientationDeg = orientation,
            isFrontFacing = facing == CameraCharacteristics.LENS_FACING_FRONT,
            supportedResolutions = sizes,
            supportedFpsRanges = fpsRanges,
          )
        }
      }.getOrDefault(emptyList())
    }
  }
}

/**
 * Decodes an MP4 file sequentially frame-by-frame via MediaExtractor + MediaCodec + ImageReader
 * for deterministic Controlled Model Comparison runs against identical recorded degraded frames.
 */
object RecordedMp4FrameReader {

  fun readAllFrames(
    mp4File: File,
    maxFrames: Int = 240,
    onFrame: (frameIndex: Int, bitmap: Bitmap, timestampUs: Long) -> Unit,
  ): Int {
    if (!mp4File.exists() || mp4File.length() <= 0L) return 0
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    var reader: ImageReader? = null
    var thread: HandlerThread? = null
    var argbScratch = IntArray(0)
    var framesDecoded = 0

    try {
      extractor.setDataSource(mp4File.absolutePath)
      val trackIdx =
        (0 until extractor.trackCount).firstOrNull { idx ->
          extractor.getTrackFormat(idx).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: return 0
      extractor.selectTrack(trackIdx)
      val format = extractor.getTrackFormat(trackIdx)
      val mime = format.getString(MediaFormat.KEY_MIME) ?: return 0
      val width = format.getInteger(MediaFormat.KEY_WIDTH).coerceAtLeast(64)
      val height = format.getInteger(MediaFormat.KEY_HEIGHT).coerceAtLeast(64)

      val t = HandlerThread("Mp4ReplayReader").apply { start() }
      thread = t
      var latestBmp: Bitmap? = null
      var frameLatch = CountDownLatch(1)

      val r = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)
      r.setOnImageAvailableListener(
        { ir ->
          val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
          try {
            val bmp = imageYuv420ToBitmap(img, argbScratch) { newBuf -> argbScratch = newBuf }
            if (bmp != null) {
              latestBmp = bmp
            }
          } finally {
            img.close()
            frameLatch.countDown()
          }
        },
        Handler(t.looper),
      )
      reader = r

      val mc = MediaCodec.createDecoderByType(mime)
      mc.configure(format, r.surface, null, 0)
      mc.start()
      codec = mc

      val info = MediaCodec.BufferInfo()
      var inputDone = false
      var outputDone = false

      while (!outputDone && framesDecoded < maxFrames) {
        if (!inputDone) {
          val inIdx = mc.dequeueInputBuffer(10_000L)
          if (inIdx >= 0) {
            val inBuf: ByteBuffer? = mc.getInputBuffer(inIdx)
            val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
            if (sampleSize < 0) {
              mc.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              inputDone = true
            } else {
              val pts = extractor.sampleTime
              mc.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
              extractor.advance()
            }
          }
        }

        val outIdx = mc.dequeueOutputBuffer(info, 10_000L)
        if (outIdx >= 0) {
          val render = info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0
          if (render) {
            frameLatch = CountDownLatch(1)
            latestBmp = null
          }
          mc.releaseOutputBuffer(outIdx, render)
          if (render) {
            frameLatch.await(150L, TimeUnit.MILLISECONDS)
            val bmp = latestBmp
            if (bmp != null) {
              onFrame(framesDecoded, bmp, info.presentationTimeUs)
              bmp.recycle()
              framesDecoded++
            }
          }
          if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            outputDone = true
          }
        }
      }
      return framesDecoded
    } catch (t: Throwable) {
      AutopilotLog.warn(SubsystemTag.VIDEO, "MP4 replay error: ${t.message}")
      return framesDecoded
    } finally {
      runCatching { codec?.stop() }
      runCatching { codec?.release() }
      runCatching { reader?.close() }
      runCatching { thread?.quitSafely() }
      runCatching { extractor.release() }
    }
  }

  private fun imageYuv420ToBitmap(
    image: Image,
    scratch: IntArray,
    updateScratch: (IntArray) -> Unit,
  ): Bitmap? {
    if (image.planes.size < 3) return null
    val w = image.width
    val h = image.height
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val yBuf = yPlane.buffer
    val uBuf = uPlane.buffer
    val vBuf = vPlane.buffer

    val yStride = yPlane.rowStride
    val uStride = uPlane.rowStride
    val uPixStride = uPlane.pixelStride
    val vStride = vPlane.rowStride
    val vPixStride = vPlane.pixelStride

    val total = w * h
    val buf =
      if (scratch.size != total) IntArray(total).also(updateScratch) else scratch

    for (row in 0 until h) {
      val yOff = row * yStride
      val uvRow = row shr 1
      val uOff = uvRow * uStride
      val vOff = uvRow * vStride
      val outOff = row * w
      for (col in 0 until w) {
        val uvCol = col shr 1
        val y = (yBuf.get(yOff + col).toInt() and 0xFF) - 16
        val u = (uBuf.get(uOff + uvCol * uPixStride).toInt() and 0xFF) - 128
        val v = (vBuf.get(vOff + uvCol * vPixStride).toInt() and 0xFF) - 128
        val c = if (y < 0) 0 else y
        val r = ((298 * c + 409 * v + 128) shr 8).coerceIn(0, 255)
        val g = ((298 * c - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
        val b = ((298 * c + 516 * u + 128) shr 8).coerceIn(0, 255)
        buf[outOff + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
      }
    }
    return Bitmap.createBitmap(buf, w, h, Bitmap.Config.ARGB_8888)
  }
}
