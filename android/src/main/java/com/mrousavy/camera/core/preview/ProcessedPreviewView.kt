package com.mrousavy.camera.core.preview

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.SurfaceView
import com.mrousavy.camera.core.types.ResizeMode
import kotlin.math.max
import kotlin.math.min

class ProcessedPreviewView(context: Context) : SurfaceView(context) {
  var resizeMode: ResizeMode = ResizeMode.COVER

  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    isFilterBitmap = true
  }

  init {
    setZOrderMediaOverlay(true)
  }

  fun renderFrame(bitmap: android.graphics.Bitmap, mirrored: Boolean, rotationDegrees: Int) {
    val holder = holder
    if (!holder.surface.isValid) return
    val canvas = holder.lockCanvas() ?: return
    try {
      canvas.drawColor(Color.BLACK)
      val viewWidth = width.toFloat()
      val viewHeight = height.toFloat()
      if (viewWidth <= 0f || viewHeight <= 0f) return

      val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
      val bmpWidth = bitmap.width.toFloat()
      val bmpHeight = bitmap.height.toFloat()
      val renderWidth = if (normalizedRotation == 90 || normalizedRotation == 270) bmpHeight else bmpWidth
      val renderHeight = if (normalizedRotation == 90 || normalizedRotation == 270) bmpWidth else bmpHeight
      val scale = if (resizeMode == ResizeMode.COVER) {
        max(viewWidth / renderWidth, viewHeight / renderHeight)
      } else {
        min(viewWidth / renderWidth, viewHeight / renderHeight)
      }
      val scaledWidth = renderWidth * scale
      val scaledHeight = renderHeight * scale
      val left = (viewWidth - scaledWidth) / 2f
      val top = (viewHeight - scaledHeight) / 2f
      val dst = RectF(left, top, left + scaledWidth, top + scaledHeight)

      canvas.save()
      if (normalizedRotation != 0) {
        canvas.translate(viewWidth / 2f, viewHeight / 2f)
        canvas.rotate(normalizedRotation.toFloat())
        canvas.translate(-viewWidth / 2f, -viewHeight / 2f)
      }
      if (mirrored) {
        canvas.translate(viewWidth, 0f)
        canvas.scale(-1f, 1f)
      }
      canvas.drawBitmap(bitmap, null, dst, paint)
      canvas.restore()
    } finally {
      holder.unlockCanvasAndPost(canvas)
    }
  }
}
