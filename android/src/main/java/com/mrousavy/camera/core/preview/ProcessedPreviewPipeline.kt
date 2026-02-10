package com.mrousavy.camera.core.preview

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ProcessedPreviewPipeline(
  private val view: ProcessedPreviewView,
  private val isMirrored: Boolean,
  private val callback: Callback,
  private val renderIntervalMs: Long = 33L,
  private val sampleIntervalMs: Long = 120L
) : ImageAnalysis.Analyzer {
  interface Callback {
    fun onWhiteBalanceSampled(r: Int, g: Int, b: Int)
    fun onCalibrationApplied()
  }

  @Volatile
  private var calibrationPending = false
  private var hasCalibration = false
  private var gainR = 1f
  private var gainG = 1f
  private var gainB = 1f
  private var gainScale = 1f

  private var bitmap: Bitmap? = null
  private var pixelBuffer: IntArray? = null
  private var lastRenderTime = 0L
  private var lastSampleTime = 0L

  fun requestCalibration() {
    calibrationPending = true
  }

  fun resetCalibration() {
    calibrationPending = false
    hasCalibration = false
    gainR = 1f
    gainG = 1f
    gainB = 1f
    gainScale = 1f
  }

  @OptIn(ExperimentalGetImage::class)
  override fun analyze(imageProxy: ImageProxy) {
    try {
      if (calibrationPending) {
        calibrationPending = false
        computeCalibration(imageProxy)
      }

      val now = SystemClock.uptimeMillis()
      if (now - lastRenderTime < renderIntervalMs) return
      lastRenderTime = now
      if (!view.holder.surface.isValid) return

      val image = imageProxy.image ?: return
      val width = imageProxy.width
      val height = imageProxy.height
      val bmp = ensureBitmap(width, height)
      val pixels = pixelBuffer ?: return

      val yPlane = image.planes[0]
      val uPlane = image.planes[1]
      val vPlane = image.planes[2]
      val yBuffer = yPlane.buffer
      val uBuffer = uPlane.buffer
      val vBuffer = vPlane.buffer
      val yRowStride = yPlane.rowStride
      val uvRowStride = uPlane.rowStride
      val uvPixelStride = uPlane.pixelStride

      val centerX = width / 2
      val centerY = height / 2
      val sampleRadius = 2
      var sampleSumR = 0
      var sampleSumG = 0
      var sampleSumB = 0
      var sampleCount = 0
      val shouldSample = now - lastSampleTime >= sampleIntervalMs

      var index = 0
      for (y in 0 until height) {
        val yRow = y * yRowStride
        val uvRow = (y / 2) * uvRowStride
        for (x in 0 until width) {
          val yValue = yBuffer.get(yRow + x).toInt() and 0xFF
          val uvIndex = uvRow + (x / 2) * uvPixelStride
          val uValue = uBuffer.get(uvIndex).toInt() and 0xFF
          val vValue = vBuffer.get(uvIndex).toInt() and 0xFF

          val c = yValue - 16
          val d = uValue - 128
          val e = vValue - 128
          var r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
          var g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
          var b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)

          if (hasCalibration) {
            r = (r * gainR * gainScale).roundToInt().coerceIn(0, 255)
            g = (g * gainG * gainScale).roundToInt().coerceIn(0, 255)
            b = (b * gainB * gainScale).roundToInt().coerceIn(0, 255)
          }

          if (shouldSample &&
            x in (centerX - sampleRadius)..(centerX + sampleRadius) &&
            y in (centerY - sampleRadius)..(centerY + sampleRadius)
          ) {
            sampleSumR += r
            sampleSumG += g
            sampleSumB += b
            sampleCount++
          }

          pixels[index++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
      }

      bmp.setPixels(pixels, 0, width, 0, 0, width, height)
      view.renderFrame(bmp, isMirrored)

      if (shouldSample && sampleCount > 0) {
        lastSampleTime = now
        callback.onWhiteBalanceSampled(
          sampleSumR / sampleCount,
          sampleSumG / sampleCount,
          sampleSumB / sampleCount
        )
      }
    } finally {
      imageProxy.close()
    }
  }

  @OptIn(ExperimentalGetImage::class)
  private fun computeCalibration(imageProxy: ImageProxy) {
    val image = imageProxy.image ?: return
    val width = imageProxy.width
    val height = imageProxy.height
    if (width <= 0 || height <= 0) return

    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val yRowStride = yPlane.rowStride
    val uvRowStride = uPlane.rowStride
    val uvPixelStride = uPlane.pixelStride

    val centerX = width / 2
    val centerY = height / 2
    val regionRadius = max(6, min(width, height) / 10)
    val startX = max(0, centerX - regionRadius)
    val endX = min(width - 1, centerX + regionRadius)
    val startY = max(0, centerY - regionRadius)
    val endY = min(height - 1, centerY + regionRadius)
    val stepSize = 2

    var sumR = 0.0
    var sumG = 0.0
    var sumB = 0.0
    var count = 0

    for (y in startY..endY step stepSize) {
      val yRow = y * yRowStride
      val uvRow = (y / 2) * uvRowStride
      for (x in startX..endX step stepSize) {
        val yValue = yBuffer.get(yRow + x).toInt() and 0xFF
        val uvIndex = uvRow + (x / 2) * uvPixelStride
        val uValue = uBuffer.get(uvIndex).toInt() and 0xFF
        val vValue = vBuffer.get(uvIndex).toInt() and 0xFF

        val c = yValue - 16
        val d = uValue - 128
        val e = vValue - 128
        val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
        val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
        val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)

        sumR += r
        sumG += g
        sumB += b
        count++
      }
    }

    if (count <= 0) return

    val avgR = sumR / count
    val avgG = sumG / count
    val avgB = sumB / count
    val avg = (avgR + avgG + avgB) / 3.0
    val eps = 1.0
    val minGain = 0.4
    val maxGain = 3.0

    gainR = (avg / (avgR + eps)).coerceIn(minGain, maxGain).toFloat()
    gainG = (avg / (avgG + eps)).coerceIn(minGain, maxGain).toFloat()
    gainB = (avg / (avgB + eps)).coerceIn(minGain, maxGain).toFloat()

    val targetLuma = 245.0
    gainScale = (targetLuma / (avg + eps)).coerceIn(0.6, 2.5).toFloat()

    hasCalibration = true
    callback.onCalibrationApplied()
  }

  private fun ensureBitmap(width: Int, height: Int): Bitmap {
    val existing = bitmap
    if (existing != null && existing.width == width && existing.height == height) {
      return existing
    }
    val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap = created
    pixelBuffer = IntArray(width * height)
    return created
  }
}
