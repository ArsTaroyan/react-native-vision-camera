package com.mrousavy.camera.core

import android.annotation.SuppressLint
import android.util.Log
import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import com.mrousavy.camera.core.extensions.await

@SuppressLint("RestrictedApi")
suspend fun CameraSession.focus(meteringPoint: MeteringPoint) {
  val camera = camera ?: throw CameraNotReadyError()

  val config = configuration
  val isCalibratingWhiteBalance = config?.autoWhiteBalanceCalibrateOnWhite ?: false
  val aeEnabled = (config?.autoExposure ?: true) || isCalibratingWhiteBalance
  val awbEnabled = (config?.autoWhiteBalance ?: true) || isCalibratingWhiteBalance
  var flags = FocusMeteringAction.FLAG_AF
  if (aeEnabled) flags = flags or FocusMeteringAction.FLAG_AE
  if (awbEnabled) flags = flags or FocusMeteringAction.FLAG_AWB
  val action = FocusMeteringAction.Builder(meteringPoint, flags).build()
  if (!camera.cameraInfo.isFocusMeteringSupported(action)) {
    throw FocusNotSupportedError()
  }

  try {
    Log.i(CameraSession.TAG, "Focusing to ${action.meteringPointsAf.joinToString { "(${it.x}, ${it.y})" }}...")
    val future = camera.cameraControl.startFocusAndMetering(action)
    val result = future.await(CameraQueues.cameraExecutor)
    if (result.isFocusSuccessful) {
      Log.i(CameraSession.TAG, "Focused successfully!")
    } else {
      Log.i(CameraSession.TAG, "Focus failed.")
    }
  } catch (e: CameraControl.OperationCanceledException) {
    throw FocusCanceledError()
  }
}
