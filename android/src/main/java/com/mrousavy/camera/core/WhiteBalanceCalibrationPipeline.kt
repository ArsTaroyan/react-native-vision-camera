package com.mrousavy.camera.core

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

class WhiteBalanceCalibrationPipeline(private val session: CameraSession) : ImageAnalysis.Analyzer {
  override fun analyze(imageProxy: ImageProxy) {
    session.handleCalibrationFrame(imageProxy)
  }
}
