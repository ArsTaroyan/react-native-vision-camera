package com.mrousavy.camera.core

import android.annotation.SuppressLint
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.DynamicRange
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.lifecycle.Lifecycle
import com.mrousavy.camera.core.extensions.*
import com.mrousavy.camera.core.types.CameraDeviceFormat
import com.mrousavy.camera.core.types.Torch
import com.mrousavy.camera.core.types.VideoStabilizationMode
import com.mrousavy.camera.core.utils.CamcorderProfileUtils
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

private fun assertFormatRequirement(
  propName: String,
  format: CameraDeviceFormat?,
  throwIfNotMet: CameraError,
  requirement: (format: CameraDeviceFormat) -> Boolean
) {
  if (format == null) {
    // we need a format for this to work.
    throw PropRequiresFormatToBeNonNullError(propName)
  }
  val isSupported = requirement(format)
  if (!isSupported) {
    throw throwIfNotMet
  }
}

private const val MIN_WHITE_BALANCE_KELVIN = 1000.0
private const val MAX_WHITE_BALANCE_KELVIN = 40000.0
private const val MAX_WB_GAIN = 8.0

private val IDENTITY_COLOR_TRANSFORM = ColorSpaceTransform(
  intArrayOf(
    1, 1, 0, 1, 0, 1,
    0, 1, 1, 1, 0, 1,
    0, 1, 0, 1, 1, 1
  )
)

private fun kelvinToRgb(temperature: Double): Triple<Double, Double, Double> {
  val temp = temperature.coerceIn(MIN_WHITE_BALANCE_KELVIN, MAX_WHITE_BALANCE_KELVIN) / 100.0
  val r: Double
  val g: Double
  val b: Double
  if (temp <= 66.0) {
    r = 255.0
    g = 99.4708025861 * ln(temp) - 161.1195681661
    b = if (temp <= 19.0) {
      0.0
    } else {
      138.5177312231 * ln(temp - 10.0) - 305.0447927307
    }
  } else {
    r = 329.698727446 * (temp - 60.0).pow(-0.1332047592)
    g = 288.1221695283 * (temp - 60.0).pow(-0.0755148492)
    b = 255.0
  }
  val rr = r.coerceIn(0.0, 255.0) / 255.0
  val gg = g.coerceIn(0.0, 255.0) / 255.0
  val bb = b.coerceIn(0.0, 255.0) / 255.0
  return Triple(rr, gg, bb)
}

private fun buildWhiteBalanceGains(temperature: Double): RggbChannelVector {
  val (r, g, b) = kelvinToRgb(temperature)
  val safeR = max(r, 1e-6)
  val safeG = max(g, 1e-6)
  val safeB = max(b, 1e-6)
  val rGain = (safeG / safeR).coerceIn(0.1, MAX_WB_GAIN)
  val bGain = (safeG / safeB).coerceIn(0.1, MAX_WB_GAIN)
  return RggbChannelVector(rGain.toFloat(), 1f, 1f, bGain.toFloat())
}

@OptIn(ExperimentalGetImage::class)
@SuppressLint("RestrictedApi")
@Suppress("LiftReturnOrAssignment")
internal fun CameraSession.configureOutputs(configuration: CameraConfiguration) {
  val cameraId = configuration.cameraId!!
  Log.i(CameraSession.TAG, "Creating new Outputs for Camera #$cameraId...")
  val fpsRange = configuration.targetFpsRange
  val format = configuration.format

  Log.i(CameraSession.TAG, "Using FPS Range: $fpsRange")

  val photoConfig = configuration.photo as? CameraConfiguration.Output.Enabled<CameraConfiguration.Photo>
  val videoConfig = configuration.video as? CameraConfiguration.Output.Enabled<CameraConfiguration.Video>

  // 1. Preview
  val previewConfig = configuration.preview as? CameraConfiguration.Output.Enabled<CameraConfiguration.Preview>
  if (previewConfig != null) {
    Log.i(CameraSession.TAG, "Creating Preview output...")
    val preview = Preview.Builder().also { preview ->
      // Configure Preview Output
      if (configuration.videoStabilizationMode.isAtLeast(VideoStabilizationMode.CINEMATIC)) {
        assertFormatRequirement("videoStabilizationMode", format, InvalidVideoStabilizationMode(configuration.videoStabilizationMode)) {
          it.videoStabilizationModes.contains(configuration.videoStabilizationMode)
        }
        preview.setPreviewStabilizationEnabled(true)
      }
      if (fpsRange != null) {
        assertFormatRequirement("fps", format, InvalidFpsError(fpsRange.upper)) {
          fpsRange.lower >= it.minFps && fpsRange.upper <= it.maxFps
        }
        preview.setTargetFrameRate(fpsRange)
      }

      if (format != null) {
        // Preview will follow video size as it's size & aspect ratio, or photo- if video is disabled.
        val targetSize = if (videoConfig != null) format.videoSize else format.photoSize

        val previewResolutionSelector = ResolutionSelector.Builder()
          .forSize(targetSize)
          .setAllowedResolutionMode(ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION)
          .build()
        preview.setResolutionSelector(previewResolutionSelector)
      }
    }.build()
    preview.setSurfaceProvider(previewConfig.config.surfaceProvider)
    previewOutput = preview
  } else {
    previewOutput = null
  }

  // 2. Image Capture
  if (photoConfig != null) {
    Log.i(CameraSession.TAG, "Creating Photo output...")
    val photo = ImageCapture.Builder().also { photo ->
      // Configure Photo Output
      photo.setCaptureMode(photoConfig.config.photoQualityBalance.toCaptureMode())
      if (format != null) {
        Log.i(CameraSession.TAG, "Photo size: ${format.photoSize}")
        val resolutionSelector = ResolutionSelector.Builder()
          .forSize(format.photoSize)
          .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
          .build()
        photo.setResolutionSelector(resolutionSelector)
      }
    }.build()
    photoOutput = photo
  } else {
    photoOutput = null
  }

  // 3. Video Capture
  if (videoConfig != null) {
    Log.i(CameraSession.TAG, "Creating Video output...")
    val currentRecorder = recorderOutput
    val recorder = if (recording != null && currentRecorder != null) {
      // If we are currently recording, then don't re-create the recorder instance.
      // Instead, re-use it so we don't cancel the active recording.
      Log.i(CameraSession.TAG, "Re-using active Recorder because we are currently recording...")
      currentRecorder
    } else {
      // We are currently not recording, so we can re-create a recorder instance if needed.
      Log.i(CameraSession.TAG, "Creating new Recorder...")
      Recorder.Builder().also { recorder ->
        format?.let { format ->
          recorder.setQualitySelector(format.videoQualitySelector)
        }
        videoConfig.config.bitRateOverride?.let { bitRateOverride ->
          val bps = bitRateOverride * 1_000_000
          recorder.setTargetVideoEncodingBitRate(bps.toInt())
        }
        videoConfig.config.bitRateMultiplier?.let { bitRateMultiplier ->
          if (format == null) {
            // We need to get the videoSize to estimate the bitRate modifier
            throw PropRequiresFormatToBeNonNullError("videoBitRate")
          }
          val recommendedBitRate = CamcorderProfileUtils.getRecommendedBitRate(cameraId, format.videoSize)
          if (recommendedBitRate != null) {
            val targetBitRate = recommendedBitRate.toDouble() * bitRateMultiplier
            recorder.setTargetVideoEncodingBitRate(targetBitRate.toInt())
          }
        }
      }.build()
    }

    val video = VideoCapture.Builder(recorder).also { video ->
      // Configure Video Output
      if (videoConfig.config.isMirrored) {
        video.setMirrorMode(MirrorMode.MIRROR_MODE_ON)
      } else {
        video.setMirrorMode(MirrorMode.MIRROR_MODE_OFF)
      }
      if (configuration.videoStabilizationMode.isAtLeast(VideoStabilizationMode.STANDARD)) {
        assertFormatRequirement("videoStabilizationMode", format, InvalidVideoStabilizationMode(configuration.videoStabilizationMode)) {
          it.videoStabilizationModes.contains(configuration.videoStabilizationMode)
        }
        video.setVideoStabilizationEnabled(true)
      }
      if (fpsRange != null) {
        assertFormatRequirement("fps", format, InvalidFpsError(fpsRange.upper)) {
          fpsRange.lower >= it.minFps &&
            fpsRange.upper <= it.maxFps
        }
        video.setTargetFrameRate(fpsRange)
      }
      if (videoConfig.config.enableHdr) {
        assertFormatRequirement("videoHdr", format, InvalidVideoHdrError()) { it.supportsVideoHdr }
        video.setDynamicRange(DynamicRange.HDR_UNSPECIFIED_10_BIT)
      }
      if (format != null) {
        Log.i(CameraSession.TAG, "Video size: ${format.videoSize}")
        val resolutionSelector = ResolutionSelector.Builder()
          .forSize(format.videoSize)
          .setAllowedResolutionMode(ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION)
          .build()
        video.setResolutionSelector(resolutionSelector)
      }
    }.build()
    videoOutput = video
    recorderOutput = recorder
  } else {
    videoOutput = null
    recorderOutput = null
  }

  // 4. Frame Processor
  val frameProcessorConfig = configuration.frameProcessor as? CameraConfiguration.Output.Enabled<CameraConfiguration.FrameProcessor>
  if (frameProcessorConfig != null) {
    val pixelFormat = frameProcessorConfig.config.pixelFormat
    Log.i(CameraSession.TAG, "Creating $pixelFormat Frame Processor output...")
    val analyzer = ImageAnalysis.Builder().also { analysis ->
      analysis.setBackpressureStrategy(ImageAnalysis.STRATEGY_BLOCK_PRODUCER)
      analysis.setOutputImageFormat(pixelFormat.toImageAnalysisFormat())
      if (fpsRange != null) {
        assertFormatRequirement("fps", format, InvalidFpsError(fpsRange.upper)) {
          fpsRange.lower >= it.minFps &&
            fpsRange.upper <= it.maxFps
        }
        analysis.setTargetFrameRate(fpsRange)
      }
      if (format != null) {
        Log.i(CameraSession.TAG, "Frame Processor size: ${format.videoSize}")
        val resolutionSelector = ResolutionSelector.Builder()
          .forSize(format.videoSize)
          .setAllowedResolutionMode(ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION)
          .build()
        analysis.setResolutionSelector(resolutionSelector)
      }
    }.build()
    val pipeline = FrameProcessorPipeline(callback)
    analyzer.setAnalyzer(CameraQueues.videoQueue.executor, pipeline)
    frameProcessorOutput = analyzer
  } else {
    frameProcessorOutput = null
  }

  // 5. Code Scanner
  val codeScannerConfig = configuration.codeScanner as? CameraConfiguration.Output.Enabled<CameraConfiguration.CodeScanner>
  if (codeScannerConfig != null) {
    Log.i(CameraSession.TAG, "Creating CodeScanner output...")
    val analyzer = ImageAnalysis.Builder().build()
    val pipeline = CodeScannerPipeline(codeScannerConfig.config, callback)
    analyzer.setAnalyzer(CameraQueues.analyzerExecutor, pipeline)
    codeScannerOutput = analyzer
  } else {
    codeScannerOutput = null
  }
  Log.i(CameraSession.TAG, "Successfully created new Outputs for Camera #${configuration.cameraId}!")
}

@SuppressLint("RestrictedApi")
internal suspend fun CameraSession.configureCamera(provider: ProcessCameraProvider, configuration: CameraConfiguration) {
  Log.i(CameraSession.TAG, "Binding Camera #${configuration.cameraId}...")
  checkCameraPermission()

  // Outputs
  val useCases = listOfNotNull(previewOutput, photoOutput, videoOutput, frameProcessorOutput, codeScannerOutput)
  if (useCases.isEmpty()) {
    throw NoOutputsError()
  }

  // Input
  val cameraId = configuration.cameraId ?: throw NoCameraDeviceError()
  var cameraSelector = CameraSelector.Builder().byId(cameraId).build()

  // Wrap input with a vendor extension if needed (see https://developer.android.com/media/camera/camera-extensions)
  val isStreamingHDR = useCases.any { !it.currentConfig.dynamicRange.isSDR }
  val needsImageAnalysis = codeScannerOutput != null || frameProcessorOutput != null
  val photoOptions = configuration.photo as? CameraConfiguration.Output.Enabled<CameraConfiguration.Photo>
  val enableHdrExtension = photoOptions != null && photoOptions.config.enableHdr
  if (enableHdrExtension) {
    if (isStreamingHDR) {
      // extensions don't work if a camera stream is running at 10-bit HDR.
      throw PhotoHdrAndVideoHdrNotSupportedSimultaneously()
    }
    // Load HDR Vendor extension (HDR only applies to image capture)
    cameraSelector = cameraSelector.withExtension(context, provider, needsImageAnalysis, ExtensionMode.HDR, "HDR")
  }
  if (configuration.enableLowLightBoost) {
    if (isStreamingHDR) {
      // extensions don't work if a camera stream is running at 10-bit HDR.
      throw LowLightBoostNotSupportedWithHdr()
    }
    if (enableHdrExtension) {
      // low-light boost does not work when another HDR extension is already applied
      throw LowLightBoostNotSupportedWithHdr()
    }
    // Load night mode Vendor extension (only applies to image capture)
    cameraSelector = cameraSelector.withExtension(context, provider, needsImageAnalysis, ExtensionMode.NIGHT, "NIGHT")
  }

  // Unbind all currently bound use-cases before rebinding
  if (currentUseCases.isNotEmpty()) {
    Log.i(CameraSession.TAG, "Unbinding ${currentUseCases.size} use-cases for Camera #${camera?.cameraInfo?.id}...")
    provider.unbind(*currentUseCases.toTypedArray())
  }

  // Bind it all together (must be on UI Thread)
  Log.i(CameraSession.TAG, "Binding ${useCases.size} use-cases...")
  camera = provider.bindToLifecycle(this, cameraSelector, *useCases.toTypedArray())
  resetAutoWhiteBalanceLock()
  // Notify callback
  callback.onInitialized()

  // Update currentUseCases for next unbind
  currentUseCases = useCases

  // Listen to Camera events
  var lastIsStreaming = false
  camera!!.cameraInfo.cameraState.observe(this) { state ->
    Log.i(CameraSession.TAG, "Camera State: ${state.type} (has error: ${state.error != null})")

    val isStreaming = state.type == CameraState.Type.OPEN
    if (isStreaming != lastIsStreaming) {
      // Notify callback
      if (isStreaming) {
        callback.onStarted()
      } else {
        callback.onStopped()
      }
      lastIsStreaming = isStreaming
    }

    val error = state.error
    if (error != null) {
      // A Camera error occurred!
      callback.onError(error.toCameraError())
    }
  }
  Log.i(CameraSession.TAG, "Successfully bound Camera #${configuration.cameraId}!")
}

internal fun CameraSession.configureSideProps(config: CameraConfiguration) {
  val camera = camera ?: throw CameraNotReadyError()

  // Zoom
  val currentZoom = camera.cameraInfo.zoomState.value?.zoomRatio
  if (currentZoom != config.zoom) {
    camera.cameraControl.setZoomRatio(config.zoom)
  }

  // Torch
  val currentTorch = camera.cameraInfo.torchState.value == TorchState.ON
  val newTorch = config.torch == Torch.ON
  if (currentTorch != newTorch) {
    if (newTorch && !camera.cameraInfo.hasFlashUnit()) {
      throw FlashUnavailableError()
    }
    camera.cameraControl.enableTorch(newTorch)
  }

  // Exposure (only when auto exposure is enabled)
  if (config.autoExposure) {
    val currentExposureCompensation = camera.cameraInfo.exposureState.exposureCompensationIndex
    val exposureCompensation = config.exposure?.roundToInt() ?: 0
    if (currentExposureCompensation != exposureCompensation) {
      camera.cameraControl.setExposureCompensationIndex(exposureCompensation)
    }
  }

  // Auto Exposure (AE) lock
  val camera2Control = Camera2CameraControl.from(camera.cameraControl)
  val requestBuilder = CaptureRequestOptions.Builder()
  if (config.autoExposure) {
    requestBuilder
      .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
      .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
  } else {
    requestBuilder
      .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
      .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
  }

  // White Balance (AWB auto vs fixed temperature)
  if (config.autoWhiteBalance) {
    val shouldLock = config.autoWhiteBalanceLock && autoWhiteBalanceLocked
    requestBuilder
      .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
      .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, shouldLock)
    if (config.autoWhiteBalanceLock) {
      if (!shouldLock && config.isActive) {
        scheduleAutoWhiteBalanceLock(config.autoWhiteBalanceLockDelay)
      }
    } else {
      resetAutoWhiteBalanceLock()
    }
  } else {
    resetAutoWhiteBalanceLock()
    val temperature = config.whiteBalanceTemperature
    if (temperature != null) {
      requestBuilder
        .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        .setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        .setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_TRANSFORM, IDENTITY_COLOR_TRANSFORM)
        .setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, buildWhiteBalanceGains(temperature))
    } else {
      requestBuilder
        .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
    }
  }
  camera2Control.setCaptureRequestOptions(requestBuilder.build())
}

internal fun CameraSession.configureIsActive(config: CameraConfiguration) {
  if (config.isActive) {
    lifecycleRegistry.currentState = Lifecycle.State.STARTED
    lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    if (config.autoWhiteBalance && config.autoWhiteBalanceLock) {
      scheduleAutoWhiteBalanceLock(config.autoWhiteBalanceLockDelay)
    }
  } else {
    resetAutoWhiteBalanceLock()
    lifecycleRegistry.currentState = Lifecycle.State.STARTED
    lifecycleRegistry.currentState = Lifecycle.State.CREATED
  }
}
