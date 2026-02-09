//
//  CameraSession.swift
//  VisionCamera
//
//  Created by Marc Rousavy on 11.10.23.
//  Copyright © 2023 mrousavy. All rights reserved.
//

import AVFoundation
import Foundation

/**
 A fully-featured Camera Session supporting preview, video, photo, frame processing, and code scanning outputs.
 All changes to the session have to be controlled via the `configure` function.
 */
final class CameraSession: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate {
  // Configuration
  private var isInitialized = false
  var configuration: CameraConfiguration?
  var currentConfigureCall: DispatchTime = .now()
  // Capture Session
  let captureSession = AVCaptureSession()
  let audioCaptureSession = AVCaptureSession()
  // Inputs & Outputs
  var videoDeviceInput: AVCaptureDeviceInput?
  var audioDeviceInput: AVCaptureDeviceInput?
  var photoOutput: AVCapturePhotoOutput?
  var videoOutput: AVCaptureVideoDataOutput?
  var audioOutput: AVCaptureAudioDataOutput?
  var codeScannerOutput: AVCaptureMetadataOutput?
  // State
  var metadataProvider = MetadataProvider()
  var recordingSession: RecordingSession?
  var didCancelRecording = false
  var orientationManager = OrientationManager()
  var autoWhiteBalanceLocked = false
  var autoWhiteBalanceLockWorkItem: DispatchWorkItem?
  var autoWhiteBalanceCalibrateWorkItem: DispatchWorkItem?
  var lastAutoWhiteBalanceCalibrateOnWhite = false
  var autoWhiteBalanceCalibrated = false
  var autoWhiteBalanceCalibrationGains: AVCaptureDevice.WhiteBalanceGains?
  private var calibrationStats = WhiteBalanceCalibrationStats()

  // Callbacks
  weak var delegate: CameraSessionDelegate?

  // Public accessors
  var maxZoom: Double {
    if let device = videoDeviceInput?.device {
      return device.activeFormat.videoMaxZoomFactor
    }
    return 1.0
  }

  /**
   Create a new instance of the `CameraSession`.
   The `onError` callback is used for any runtime errors.
   */
  override init() {
    super.init()
    NotificationCenter.default.addObserver(self,
                                           selector: #selector(sessionRuntimeError),
                                           name: .AVCaptureSessionRuntimeError,
                                           object: captureSession)
    NotificationCenter.default.addObserver(self,
                                           selector: #selector(sessionRuntimeError),
                                           name: .AVCaptureSessionRuntimeError,
                                           object: audioCaptureSession)
    NotificationCenter.default.addObserver(self,
                                           selector: #selector(audioSessionInterrupted),
                                           name: AVAudioSession.interruptionNotification,
                                           object: AVAudioSession.sharedInstance)
  }

  private func initialize() {
    if isInitialized {
      return
    }
    orientationManager.delegate = self
    isInitialized = true
  }

  deinit {
    NotificationCenter.default.removeObserver(self,
                                              name: .AVCaptureSessionRuntimeError,
                                              object: captureSession)
    NotificationCenter.default.removeObserver(self,
                                              name: .AVCaptureSessionRuntimeError,
                                              object: audioCaptureSession)
    NotificationCenter.default.removeObserver(self,
                                              name: AVAudioSession.interruptionNotification,
                                              object: AVAudioSession.sharedInstance)
  }

  /**
   Creates a PreviewView for the current Capture Session
   */
  func createPreviewView(frame: CGRect) -> PreviewView {
    return PreviewView(frame: frame, session: captureSession)
  }

  func onConfigureError(_ error: Error) {
    if let error = error as? CameraError {
      // It's a typed Error
      delegate?.onError(error)
    } else {
      // It's any kind of unknown error
      let cameraError = CameraError.unknown(message: error.localizedDescription)
      delegate?.onError(cameraError)
    }
  }

  /**
   Update the session configuration.
   Any changes in here will be re-configured only if required, and under a lock (in this case, the serial cameraQueue DispatchQueue).
   The `configuration` object is a copy of the currently active configuration that can be modified by the caller in the lambda.
   */
  func configure(_ lambda: @escaping (_ configuration: CameraConfiguration) throws -> Void) {
    initialize()

    VisionLogger.log(level: .info, message: "configure { ... }: Waiting for lock...")

    // Set up Camera (Video) Capture Session (on camera queue, acts like a lock)
    CameraQueues.cameraQueue.async {
      // Let caller configure a new configuration for the Camera.
      let config = CameraConfiguration(copyOf: self.configuration)
      do {
        try lambda(config)
      } catch CameraConfiguration.AbortThrow.abort {
        // call has been aborted and changes shall be discarded
        return
      } catch {
        // another error occured, possibly while trying to parse enums
        self.onConfigureError(error)
        return
      }
      let difference = CameraConfiguration.Difference(between: self.configuration, and: config)

      VisionLogger.log(level: .info, message: "configure { ... }: Updating CameraSession Configuration... \(difference)")

      do {
        // If needed, configure the AVCaptureSession (inputs, outputs)
        if difference.isSessionConfigurationDirty {
          self.captureSession.beginConfiguration()

          // 1. Update input device
          if difference.inputChanged {
            try self.configureDevice(configuration: config)
          }
          // 2. Update outputs
          if difference.outputsChanged {
            try self.configureOutputs(configuration: config)
          }
          // 3. Update Video Stabilization
          if difference.videoStabilizationChanged {
            self.configureVideoStabilization(configuration: config)
          }
          // 4. Update target output orientation
          if difference.orientationChanged {
            self.orientationManager.setTargetOutputOrientation(config.outputOrientation)
          }
        }

        guard let device = self.videoDeviceInput?.device else {
          throw CameraError.device(.noDevice)
        }

        // If needed, configure the AVCaptureDevice (format, zoom, low-light-boost, ..)
        if difference.isDeviceConfigurationDirty {
          try device.lockForConfiguration()
          defer {
            device.unlockForConfiguration()
          }

          // 5. Configure format
          if difference.formatChanged {
            try self.configureFormat(configuration: config, device: device)
          }
          // 6. After step 2. and 4., we also need to configure some output properties that depend on format.
          //    This needs to be done AFTER we updated the `format`, as this controls the supported properties.
          if difference.outputsChanged || difference.formatChanged {
            self.configureVideoOutputFormat(configuration: config)
            self.configurePhotoOutputFormat(configuration: config)
          }
          // 7. Configure side-props (fps, lowLightBoost)
          if difference.sidePropsChanged {
            try self.configureSideProps(configuration: config, device: device)
          }
          // 8. Configure zoom
          if difference.zoomChanged {
            self.configureZoom(configuration: config, device: device)
          }
          // 9. Configure exposure bias
          if difference.exposureChanged {
            self.configureExposure(configuration: config, device: device)
          }
        }

        if difference.isSessionConfigurationDirty {
          // We commit the session config updates AFTER the device config,
          // that way we can also batch those changes into one update instead of doing two updates.
          self.captureSession.commitConfiguration()
        }

        // 10. Start or stop the session if needed
        self.checkIsActive(configuration: config)

        // 11. Enable or disable the Torch if needed (requires session to be running)
        if difference.torchChanged {
          try device.lockForConfiguration()
          defer {
            device.unlockForConfiguration()
          }
          try self.configureTorch(configuration: config, device: device)
        }

        // After configuring, set this to the new configuration.
        self.configuration = config
      } catch {
        self.onConfigureError(error)
      }

      // Set up Audio Capture Session (on audio queue)
      if difference.audioSessionChanged {
        CameraQueues.audioQueue.async {
          do {
            // Lock Capture Session for configuration
            VisionLogger.log(level: .info, message: "Beginning AudioSession configuration...")
            self.audioCaptureSession.beginConfiguration()

            try self.configureAudioSession(configuration: config)

            // Unlock Capture Session again and submit configuration to Hardware
            self.audioCaptureSession.commitConfiguration()
            VisionLogger.log(level: .info, message: "Committed AudioSession configuration!")
          } catch {
            self.onConfigureError(error)
          }
        }
      }

      // Set up Location streaming (on location queue)
      if difference.locationChanged {
        CameraQueues.locationQueue.async {
          do {
            VisionLogger.log(level: .info, message: "Beginning Location Output configuration...")
            try self.configureLocationOutput(configuration: config)
            VisionLogger.log(level: .info, message: "Finished Location Output configuration!")
          } catch {
            self.onConfigureError(error)
          }
        }
      }
    }
  }

  /**
   Starts or stops the CaptureSession if needed (`isActive`)
   */
  private func checkIsActive(configuration: CameraConfiguration) {
    if configuration.isActive == captureSession.isRunning {
      return
    }

    // Start/Stop session
    if configuration.isActive {
      captureSession.startRunning()
      delegate?.onCameraStarted()
      scheduleAutoWhiteBalanceLockIfNeeded(configuration: configuration)
      scheduleAutoWhiteBalanceCalibrationIfNeeded(configuration: configuration)
    } else {
      resetAutoWhiteBalanceLock()
      resetAutoWhiteBalanceCalibration()
      autoWhiteBalanceCalibrated = false
      captureSession.stopRunning()
      delegate?.onCameraStopped()
    }
  }

  public final func captureOutput(_ captureOutput: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
    switch captureOutput {
    case is AVCaptureVideoDataOutput:
      onVideoFrame(sampleBuffer: sampleBuffer, orientation: connection.orientation, isMirrored: connection.isVideoMirrored)
    case is AVCaptureAudioDataOutput:
      onAudioFrame(sampleBuffer: sampleBuffer)
    default:
      break
    }
  }

  private final func onVideoFrame(sampleBuffer: CMSampleBuffer, orientation: Orientation, isMirrored: Bool) {
    if let recordingSession {
      do {
        // Write the Video Buffer to the .mov/.mp4 file
        try recordingSession.append(buffer: sampleBuffer, ofType: .video)
      } catch let error as CameraError {
        delegate?.onError(error)
      } catch {
        delegate?.onError(.capture(.unknown(message: error.localizedDescription)))
      }
    }

    handleCalibrationFrame(sampleBuffer: sampleBuffer)

    if let delegate {
      // Call Frame Processor (delegate) for every Video Frame
      delegate.onFrame(sampleBuffer: sampleBuffer, orientation: orientation, isMirrored: isMirrored)
    }
  }

  private final func onAudioFrame(sampleBuffer: CMSampleBuffer) {
    if let recordingSession {
      do {
        // Synchronize the Audio Buffer with the Video Session's time because it's two separate
        // AVCaptureSessions, then write it to the .mov/.mp4 file
        audioCaptureSession.synchronizeBuffer(sampleBuffer, toSession: captureSession)
        try recordingSession.append(buffer: sampleBuffer, ofType: .audio)
      } catch let error as CameraError {
        delegate?.onError(error)
      } catch {
        delegate?.onError(.capture(.unknown(message: error.localizedDescription)))
      }
    }
  }

  // pragma MARK: Notifications

  @objc
  func sessionRuntimeError(notification: Notification) {
    VisionLogger.log(level: .error, message: "Unexpected Camera Runtime Error occured!")
    guard let error = notification.userInfo?[AVCaptureSessionErrorKey] as? AVError else {
      return
    }

    // Notify consumer about runtime error
    delegate?.onError(.unknown(message: error._nsError.description, cause: error._nsError))

    let shouldRestart = configuration?.isActive == true
    if shouldRestart {
      // restart capture session after an error occured
      CameraQueues.cameraQueue.async {
        self.captureSession.startRunning()
      }
    }
  }

  private final func handleCalibrationFrame(sampleBuffer: CMSampleBuffer) {
    guard let config = configuration,
          config.autoWhiteBalanceCalibrateOnWhite,
          !autoWhiteBalanceCalibrated else {
      return
    }
    let now = CACurrentMediaTime()
    guard calibrationStats.shouldSample(now: now) else {
      return
    }
    guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
      return
    }

    CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
    defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

    let width = CVPixelBufferGetWidth(pixelBuffer)
    let height = CVPixelBufferGetHeight(pixelBuffer)
    if width == 0 || height == 0 {
      return
    }

    let radius = calibrationStats.resolveSampleRadius(width: width, height: height)
    let centerX = width / 2
    let centerY = height / 2
    let startX = max(0, centerX - radius)
    let endX = min(width - 1, centerX + radius)
    let startY = max(0, centerY - radius)
    let endY = min(height - 1, centerY + radius)

    let format = CVPixelBufferGetPixelFormatType(pixelBuffer)
    var sumR: Double = 0
    var sumG: Double = 0
    var sumB: Double = 0
    var count = 0
    var sumLuma: Double = 0

    if format == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange ||
        format == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange {
      let yBase = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0)
      let uvBase = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 1)
      let yBytesPerRow = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)
      let uvBytesPerRow = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 1)

      guard let yPtr = yBase?.assumingMemoryBound(to: UInt8.self),
            let uvPtr = uvBase?.assumingMemoryBound(to: UInt8.self) else {
        return
      }

      var y = startY
      while y <= endY {
        let yRow = y * yBytesPerRow
        let uvRow = (y / 2) * uvBytesPerRow
        var x = startX
        while x <= endX {
          let yIndex = yRow + x
          let uvIndex = uvRow + (x / 2) * 2
          let yValue = Int(yPtr[yIndex])
          let uValue = Int(uvPtr[uvIndex])
          let vValue = Int(uvPtr[uvIndex + 1])
          let rgb = yuvToRgb(y: yValue, u: uValue, v: vValue)
          sumR += rgb.r
          sumG += rgb.g
          sumB += rgb.b
          sumLuma += Double(yValue)
          count += 1
          x += 1
        }
        y += 1
      }
    } else if format == kCVPixelFormatType_32BGRA {
      guard let base = CVPixelBufferGetBaseAddress(pixelBuffer)?
        .assumingMemoryBound(to: UInt8.self) else {
        return
      }
      let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
      var y = startY
      while y <= endY {
        let row = y * bytesPerRow
        var x = startX
        while x <= endX {
          let idx = row + x * 4
          let b = Double(base[idx])
          let g = Double(base[idx + 1])
          let r = Double(base[idx + 2])
          let luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
          sumR += r
          sumG += g
          sumB += b
          sumLuma += luma
          count += 1
          x += 1
        }
        y += 1
      }
    } else {
      return
    }

    if count > 0 {
      calibrationStats.addSample(sumR: sumR, sumG: sumG, sumB: sumB, count: count)
      let avgLuma = sumLuma / Double(count)
      adjustExposureForCalibration(avgLuma: avgLuma)
    }
  }

  internal func resetCalibrationStats() {
    calibrationStats.reset()
  }

  internal func computeCalibrationGains(maxGain: Float) -> AVCaptureDevice.WhiteBalanceGains? {
    return calibrationStats.computeGains(maxGain: maxGain)
  }

  private func adjustExposureForCalibration(avgLuma: Double) {
    guard calibrationStats.shouldAdjustExposure(now: CACurrentMediaTime()) else {
      return
    }
    let targetLuma = 230.0
    let tolerance = 8.0
    var delta: Float = 0
    if avgLuma < targetLuma - tolerance {
      delta = 0.3
    } else if avgLuma > targetLuma + tolerance {
      delta = -0.3
    } else {
      return
    }

    CameraQueues.cameraQueue.async { [weak self] in
      guard let self else { return }
      guard let config = self.configuration,
            config.autoWhiteBalanceCalibrateOnWhite,
            !self.autoWhiteBalanceCalibrated,
            let device = self.videoDeviceInput?.device else {
        return
      }
      let minBias = device.minExposureTargetBias
      let maxBias = device.maxExposureTargetBias
      let current = device.exposureTargetBias
      let next = min(max(current + delta, minBias), maxBias)
      if next == current {
        return
      }
      do {
        try device.lockForConfiguration()
        defer { device.unlockForConfiguration() }
        device.setExposureTargetBias(next)
      } catch {
        // ignore exposure bias adjustment errors
      }
    }
  }
}

private struct WhiteBalanceCalibrationStats {
  private var sumR: Double = 0
  private var sumG: Double = 0
  private var sumB: Double = 0
  private var count: Int = 0
  private var lastSampleTime: CFTimeInterval = 0
  private var lastExposureAdjustTime: CFTimeInterval = 0

  mutating func reset() {
    sumR = 0
    sumG = 0
    sumB = 0
    count = 0
    lastSampleTime = 0
    lastExposureAdjustTime = 0
  }

  mutating func shouldSample(now: CFTimeInterval, interval: CFTimeInterval = 0.05) -> Bool {
    if lastSampleTime != 0, now - lastSampleTime < interval {
      return false
    }
    lastSampleTime = now
    return true
  }

  mutating func shouldAdjustExposure(now: CFTimeInterval, interval: CFTimeInterval = 0.12) -> Bool {
    if lastExposureAdjustTime != 0, now - lastExposureAdjustTime < interval {
      return false
    }
    lastExposureAdjustTime = now
    return true
  }

  func resolveSampleRadius(width: Int, height: Int) -> Int {
    let base = min(width, height) / 40
    return max(2, min(10, base))
  }

  mutating func addSample(sumR: Double, sumG: Double, sumB: Double, count: Int) {
    self.sumR += sumR
    self.sumG += sumG
    self.sumB += sumB
    self.count += count
  }

  func computeGains(maxGain: Float) -> AVCaptureDevice.WhiteBalanceGains? {
    if count <= 0 {
      return nil
    }
    let avgR = sumR / Double(count)
    let avgG = sumG / Double(count)
    let avgB = sumB / Double(count)
    let safeR = max(avgR, 1.0)
    let safeG = max(avgG, 1.0)
    let safeB = max(avgB, 1.0)
    let redGain = min(max(Float(safeG / safeR), 1.0), maxGain)
    let blueGain = min(max(Float(safeG / safeB), 1.0), maxGain)
    let greenGain: Float = 1.0
    return AVCaptureDevice.WhiteBalanceGains(redGain: redGain, greenGain: greenGain, blueGain: blueGain)
  }
}

private func yuvToRgb(y: Int, u: Int, v: Int) -> (r: Double, g: Double, b: Double) {
  let c = y - 16
  let d = u - 128
  let e = v - 128
  let r = (298 * c + 409 * e + 128) >> 8
  let g = (298 * c - 100 * d - 208 * e + 128) >> 8
  let b = (298 * c + 516 * d + 128) >> 8
  return (Double(max(0, min(255, r))),
          Double(max(0, min(255, g))),
          Double(max(0, min(255, b))))
}
