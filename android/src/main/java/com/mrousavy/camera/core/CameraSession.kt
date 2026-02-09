package com.mrousavy.camera.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ImageFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import androidx.annotation.MainThread
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.facebook.react.bridge.UiThreadUtil
import com.google.mlkit.vision.barcode.common.Barcode
import com.mrousavy.camera.core.extensions.await
import com.mrousavy.camera.core.types.Orientation
import com.mrousavy.camera.core.types.ShutterType
import com.mrousavy.camera.core.utils.runOnUiThread
import com.mrousavy.camera.frameprocessors.Frame
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CameraSession(internal val context: Context, internal val callback: Callback) :
  Closeable,
  LifecycleOwner,
  OrientationManager.Callback {
  companion object {
    internal const val TAG = "CameraSession"
  }

  private val identityColorTransform = android.hardware.camera2.params.ColorSpaceTransform(
    intArrayOf(
      1, 1, 0, 1, 0, 1,
      0, 1, 1, 1, 0, 1,
      0, 1, 0, 1, 1, 1
    )
  )

  // Camera Configuration
  internal var configuration: CameraConfiguration? = null
  internal val cameraProvider = ProcessCameraProvider.getInstance(context)
  internal var camera: Camera? = null

  // Camera Outputs
  internal var previewOutput: Preview? = null
  internal var photoOutput: ImageCapture? = null
  internal var videoOutput: VideoCapture<Recorder>? = null
  internal var frameProcessorOutput: ImageAnalysis? = null
  internal var codeScannerOutput: ImageAnalysis? = null
  internal var calibrationOutput: ImageAnalysis? = null
  internal var currentUseCases: List<UseCase> = emptyList()

  // Camera Outputs State
  internal val metadataProvider = MetadataProvider(context)
  internal val orientationManager = OrientationManager(context, this)
  internal var recorderOutput: Recorder? = null

  // Camera State
  internal val mutex = Mutex()
  internal var isDestroyed = false
  internal val lifecycleRegistry = LifecycleRegistry(this)
  internal var recording: Recording? = null
  internal var isRecordingCanceled = false
  internal val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  internal var autoWhiteBalanceLocked = false
  internal var lastAutoWhiteBalanceCalibrateOnWhite = false
  internal var autoWhiteBalanceCalibrated = false
  internal var autoWhiteBalanceCalibrationGains: RggbChannelVector? = null
  private val autoWhiteBalanceHandler = Handler(Looper.getMainLooper())
  private var autoWhiteBalanceLockRunnable: Runnable? = null
  private var autoWhiteBalanceCalibrateRunnable: Runnable? = null
  private val calibrationStats = WhiteBalanceCalibrationStats()

  // Threading
  internal val mainExecutor = ContextCompat.getMainExecutor(context)

  // Orientation
  val outputOrientation: Orientation
    get() = orientationManager.outputOrientation

  init {
    lifecycleRegistry.currentState = Lifecycle.State.CREATED
    lifecycle.addObserver(object : LifecycleEventObserver {
      override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        Log.i(TAG, "Camera Lifecycle changed to ${event.targetState}!")
      }
    })
  }

  override fun close() {
    Log.i(TAG, "Closing CameraSession...")
    isDestroyed = true
    resetAutoWhiteBalanceLock()
    resetAutoWhiteBalanceCalibration()
    orientationManager.stopOrientationUpdates()
    runOnUiThread {
      lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
  }

  override val lifecycle: Lifecycle
    get() = lifecycleRegistry

  /**
   * Configures the [CameraSession] with new values in one batch.
   * This must be called from the Main UI Thread.
   */
  @MainThread
  suspend fun configure(lambda: (configuration: CameraConfiguration) -> Unit) {
    if (!UiThreadUtil.isOnUiThread()) {
      throw Error("configure { ... } must be called from the Main UI Thread!")
    }
    Log.i(TAG, "configure { ... }: Waiting for lock...")

    val provider = try {
      cameraProvider.await(mainExecutor)
    } catch (error: Throwable) {
      Log.e(TAG, "Failed to get CameraProvider! Error: ${error.message}", error)
      callback.onError(error)
      return
    }

    mutex.withLock {
      // Let caller configure a new configuration for the Camera.
      val config = CameraConfiguration.copyOf(this.configuration)
      try {
        lambda(config)
      } catch (e: CameraConfiguration.AbortThrow) {
        // config changes have been aborted.
        return
      }
      val diff = CameraConfiguration.difference(this.configuration, config)
      this.configuration = config

      if (!diff.hasChanges) {
        Log.i(TAG, "Nothing changed, aborting configure { ... }")
        return@withLock
      }

      if (isDestroyed) {
        Log.i(TAG, "CameraSession is already destroyed. Skipping configure { ... }")
        return@withLock
      }

      Log.i(TAG, "configure { ... }: Updating CameraSession Configuration... $diff")

      try {
        // Build up session or update any props
        if (diff.outputsChanged) {
          // 1. outputs changed, re-create them
          configureOutputs(config)
          // 1.1. whenever the outputs changed, we need to update their orientation as well
          configureOrientation()
        }
        if (diff.deviceChanged) {
          // 2. input or outputs changed, or the session was destroyed from outside, rebind the session
          configureCamera(provider, config)
        }
        if (diff.sidePropsChanged) {
          // 3. side props such as zoom, exposure or torch changed.
          configureSideProps(config)
        }
        if (diff.isActiveChanged) {
          // 4. start or stop the session
          configureIsActive(config)
        }
        if (diff.orientationChanged) {
          // 5. update the target orientation mode
          orientationManager.setTargetOutputOrientation(config.outputOrientation)
        }
        if (diff.locationChanged) {
          // 6. start or stop location update streaming
          metadataProvider.enableLocationUpdates(config.enableLocation)
        }

        Log.i(
          TAG,
          "configure { ... }: Completed CameraSession Configuration! (State: ${lifecycle.currentState})"
        )
      } catch (error: Throwable) {
        Log.e(TAG, "Failed to configure CameraSession! Error: ${error.message}, Config-Diff: $diff", error)
        callback.onError(error)
      }
    }
  }

  internal fun checkCameraPermission() {
    val status = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
    if (status != PackageManager.PERMISSION_GRANTED) throw CameraPermissionError()
  }
  internal fun checkMicrophonePermission() {
    val status = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
    if (status != PackageManager.PERMISSION_GRANTED) throw MicrophonePermissionError()
  }

  internal fun resetAutoWhiteBalanceLock() {
    autoWhiteBalanceLocked = false
    autoWhiteBalanceLockRunnable?.let { autoWhiteBalanceHandler.removeCallbacks(it) }
    autoWhiteBalanceLockRunnable = null
    lastAutoWhiteBalanceCalibrateOnWhite = false
  }

  internal fun resetAutoWhiteBalanceCalibration() {
    autoWhiteBalanceCalibrateRunnable?.let { autoWhiteBalanceHandler.removeCallbacks(it) }
    autoWhiteBalanceCalibrateRunnable = null
    calibrationStats.reset()
  }

  internal fun scheduleAutoWhiteBalanceCalibration(delayMs: Long) {
    if (autoWhiteBalanceCalibrated) return
    if (autoWhiteBalanceCalibrateRunnable != null) return
    val runnable = Runnable {
      autoWhiteBalanceCalibrateRunnable = null
      if (isDestroyed) return@Runnable
      val config = configuration ?: return@Runnable
      if (!config.autoWhiteBalanceCalibrateOnWhite || !config.isActive) return@Runnable
      val gains = calibrationStats.computeGains()
      if (gains != null) {
        autoWhiteBalanceCalibrationGains = gains
        applyManualWhiteBalanceGains(gains)
      } else {
        autoWhiteBalanceCalibrationGains = null
        applyAutoWhiteBalanceLock(true)
      }
      autoWhiteBalanceLocked = true
      applyAutoExposureLock(true)
      autoWhiteBalanceCalibrated = true
      callback.onAutoWhiteBalanceCalibrated()
    }
    autoWhiteBalanceCalibrateRunnable = runnable
    autoWhiteBalanceHandler.postDelayed(runnable, delayMs)
  }

  internal fun scheduleAutoWhiteBalanceLock(delayMs: Long) {
    if (autoWhiteBalanceLocked || autoWhiteBalanceLockRunnable != null) return
    val runnable = Runnable {
      autoWhiteBalanceLockRunnable = null
      if (isDestroyed) return@Runnable
      val config = configuration ?: return@Runnable
      if (!config.autoWhiteBalance || !config.isActive) return@Runnable
      if (!config.autoWhiteBalanceLock) return@Runnable
      autoWhiteBalanceLocked = true
      applyAutoWhiteBalanceLock(true)
      callback.onAutoWhiteBalanceCalibrated()
    }
    autoWhiteBalanceLockRunnable = runnable
    autoWhiteBalanceHandler.postDelayed(runnable, delayMs)
  }

  internal fun applyAutoWhiteBalanceLock(lock: Boolean) {
    val camera = camera ?: return
    val camera2Control = Camera2CameraControl.from(camera.cameraControl)
    val requestBuilder = CaptureRequestOptions.Builder()
      .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
      .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, lock)
    camera2Control.setCaptureRequestOptions(requestBuilder.build())
  }

  internal fun applyManualWhiteBalanceGains(gains: RggbChannelVector) {
    val camera = camera ?: return
    val camera2Control = Camera2CameraControl.from(camera.cameraControl)
    val requestBuilder = CaptureRequestOptions.Builder()
      .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
      .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
      .setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
      .setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_TRANSFORM, identityColorTransform)
      .setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
    camera2Control.setCaptureRequestOptions(requestBuilder.build())
  }

  internal fun applyAutoExposureLock(lock: Boolean) {
    val camera = camera ?: return
    val camera2Control = Camera2CameraControl.from(camera.cameraControl)
    val requestBuilder = CaptureRequestOptions.Builder()
      .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
      .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, lock)
    camera2Control.setCaptureRequestOptions(requestBuilder.build())
  }

  @OptIn(ExperimentalGetImage::class)
  internal fun handleCalibrationFrame(imageProxy: ImageProxy) {
    try {
      val config = configuration ?: return
      if (!config.autoWhiteBalanceCalibrateOnWhite) return
      if (autoWhiteBalanceCalibrated) return
      if (imageProxy.format != ImageFormat.YUV_420_888) return

      val now = SystemClock.elapsedRealtime()
      if (!calibrationStats.shouldSample(now)) return

      val image = imageProxy.image ?: return
      val width = image.width
      val height = image.height
      if (width <= 0 || height <= 0) return

      val radius = calibrationStats.resolveSampleRadius(width, height)
      val centerX = width / 2
      val centerY = height / 2
      val startX = max(0, centerX - radius)
      val endX = min(width - 1, centerX + radius)
      val startY = max(0, centerY - radius)
      val endY = min(height - 1, centerY + radius)

      val yPlane = image.planes[0]
      val uPlane = image.planes[1]
      val vPlane = image.planes[2]
      val yBuffer = yPlane.buffer
      val uBuffer = uPlane.buffer
      val vBuffer = vPlane.buffer
      val yRowStride = yPlane.rowStride
      val yPixelStride = yPlane.pixelStride
      val uRowStride = uPlane.rowStride
      val uPixelStride = uPlane.pixelStride
      val vRowStride = vPlane.rowStride
      val vPixelStride = vPlane.pixelStride

      var sumR = 0.0
      var sumG = 0.0
      var sumB = 0.0
      var count = 0

      var y = startY
      while (y <= endY) {
        val yRow = yRowStride * y
        val uvRow = uRowStride * (y / 2)
        val vvRow = vRowStride * (y / 2)
        var x = startX
        while (x <= endX) {
          val yIndex = yRow + x * yPixelStride
          val uvIndex = uvRow + (x / 2) * uPixelStride
          val vvIndex = vvRow + (x / 2) * vPixelStride
          val yValue = yBuffer.get(yIndex).toInt() and 0xFF
          val uValue = uBuffer.get(uvIndex).toInt() and 0xFF
          val vValue = vBuffer.get(vvIndex).toInt() and 0xFF
          val rgb = yuvToRgb(yValue, uValue, vValue)
          sumR += rgb[0]
          sumG += rgb[1]
          sumB += rgb[2]
          count += 1
          x += 1
        }
        y += 1
      }

      if (count > 0) {
        calibrationStats.addSample(sumR, sumG, sumB, count)
      }
    } finally {
      imageProxy.close()
    }
  }

  private fun yuvToRgb(y: Int, u: Int, v: Int): IntArray {
    val c = y - 16
    val d = u - 128
    val e = v - 128
    val r = (298 * c + 409 * e + 128) shr 8
    val g = (298 * c - 100 * d - 208 * e + 128) shr 8
    val b = (298 * c + 516 * d + 128) shr 8
    return intArrayOf(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
  }

  private class WhiteBalanceCalibrationStats {
    private var sumR = 0.0
    private var sumG = 0.0
    private var sumB = 0.0
    private var count = 0
    private var lastSampleMs = 0L

    fun reset() {
      sumR = 0.0
      sumG = 0.0
      sumB = 0.0
      count = 0
      lastSampleMs = 0L
    }

    fun shouldSample(nowMs: Long, intervalMs: Long = 50L): Boolean {
      if (lastSampleMs != 0L && nowMs - lastSampleMs < intervalMs) return false
      lastSampleMs = nowMs
      return true
    }

    fun resolveSampleRadius(width: Int, height: Int): Int {
      val base = min(width, height) / 40
      return max(2, min(10, base))
    }

    fun addSample(r: Double, g: Double, b: Double, sampleCount: Int) {
      sumR += r
      sumG += g
      sumB += b
      count += sampleCount
    }

    fun computeGains(): RggbChannelVector? {
      if (count <= 0) return null
      val avgR = sumR / count
      val avgG = sumG / count
      val avgB = sumB / count
      val safeR = max(avgR, 1.0)
      val safeG = max(avgG, 1.0)
      val safeB = max(avgB, 1.0)
      val rGain = (safeG / safeR).coerceIn(0.1, 8.0)
      val bGain = (safeG / safeB).coerceIn(0.1, 8.0)
      return RggbChannelVector(rGain.toFloat(), 1f, 1f, bGain.toFloat())
    }
  }

  override fun onOutputOrientationChanged(outputOrientation: Orientation) {
    Log.i(TAG, "Output orientation changed! $outputOrientation")
    configureOrientation()
    callback.onOutputOrientationChanged(outputOrientation)
  }

  override fun onPreviewOrientationChanged(previewOrientation: Orientation) {
    Log.i(TAG, "Preview orientation changed! $previewOrientation")
    configureOrientation()
    callback.onPreviewOrientationChanged(previewOrientation)
  }

  private fun configureOrientation() {
    // Preview Orientation
    orientationManager.previewOrientation.toSurfaceRotation().let { previewRotation ->
      previewOutput?.targetRotation = previewRotation
      codeScannerOutput?.targetRotation = previewRotation
    }
    // Outputs Orientation
    orientationManager.outputOrientation.toSurfaceRotation().let { outputRotation ->
      photoOutput?.targetRotation = outputRotation
      videoOutput?.targetRotation = outputRotation
    }
    // Frame Processor output will not receive a target rotation, user is responsible for rotating himself
  }

  interface Callback {
    fun onError(error: Throwable)
    fun onFrame(frame: Frame)
    fun onInitialized()
    fun onStarted()
    fun onStopped()
    fun onShutter(type: ShutterType)
    fun onOutputOrientationChanged(outputOrientation: Orientation)
    fun onPreviewOrientationChanged(previewOrientation: Orientation)
    fun onCodeScanned(codes: List<Barcode>, scannerFrame: CodeScannerFrame)
    fun onAutoWhiteBalanceCalibrated()
  }
}
