import { useCallback, useEffect, useMemo } from 'react'
import { Platform } from 'react-native'
import type { SkImage } from '@shopify/react-native-skia'

import type { DrawableFrameProcessor } from '../types/CameraProps'
import type { Frame } from '../types/Frame'
import { VisionCameraProxy } from '../frame-processors/VisionCameraProxy'
import { SkiaProxy } from '../dependencies/SkiaProxy'
import { WorkletsProxy } from '../dependencies/WorkletsProxy'
import { useSkiaFrameProcessor } from './useSkiaFrameProcessor'

export interface WhiteBalanceShadingCorrectionOptions {
  /**
   * Resolution of the per-cell gain map.
   * Higher values improve accuracy but cost more CPU at calibration time.
   * @default { width: 32, height: 24 }
   */
  mapSize?: { width: number; height: number }
  /**
   * Step size in pixels when sampling during calibration.
   * @default 4
   */
  sampleStep?: number
  /**
   * Minimum allowed gain per channel.
   * @default 0.5
   */
  minGain?: number
  /**
   * Maximum allowed gain per channel.
   * @default 2.0
   */
  maxGain?: number
  /**
   * Minimum average luma to apply a correction for a cell.
   * Cells below this value are treated as neutral (gain=1).
   * @default 8
   */
  minLuma?: number
  /**
   * Normalized sample point within the frame (0..1). Defaults to center.
   * @default { x: 0.5, y: 0.5 }
   */
  samplePoint?: { x: number; y: number }
  /**
   * Sample radius (in pixels) around the sample point.
   * @default 2
   */
  sampleRadius?: number
  /**
   * Throttle interval for sample callbacks (in ms).
   * @default 120
   */
  sampleIntervalMs?: number
  /**
   * Optional callback that receives the corrected sample color (post gain).
   */
  onSample?: (rgb: WhiteBalanceSample) => void
  /**
   * Aggressively normalize illumination so calibration white becomes neutral/bright.
   * This boosts darker regions using a target luma from bright pixels.
   * @default false
   */
  forceWhite?: boolean
  /**
   * Percentile (0..1) of brightest pixels used as target luma when forceWhite is enabled.
   * @default 0.9
   */
  targetLumaPercentile?: number
}

export interface WhiteBalanceSample {
  r: number
  g: number
  b: number
}

export interface WhiteBalanceShadingCorrectionResult {
  /**
   * Skia Drawable frame processor that applies the calibration map.
   */
  frameProcessor: DrawableFrameProcessor
  /**
   * Attach this to `onAutoWhiteBalanceCalibrated`.
   * It will trigger a one-shot calibration capture.
   */
  onAutoWhiteBalanceCalibrated: () => void
  /**
   * Clears the current calibration map.
   */
  reset: () => void
}

const SHADER_SOURCE = `
uniform shader image;
uniform shader gain;
uniform float gainScale;

half4 main(float2 xy) {
  half4 c = image.eval(xy);
  half4 g = gain.eval(xy);
  half3 rgb = clamp(c.rgb * (g.rgb * gainScale), 0.0, 1.0);
  return half4(rgb, c.a);
}
`

function clamp(value: number, min: number, max: number): number {
  'worklet'
  return Math.min(Math.max(value, min), max)
}

interface GainMapResult {
  image: SkImage
  gains: number[]
}

function computeGainMap(
  frame: Frame,
  isIOS: boolean,
  mapWidth: number,
  mapHeight: number,
  sampleStep: number,
  minGain: number,
  maxGain: number,
  minLuma: number,
  forceWhite: boolean,
  targetLumaPercentile: number,
): GainMapResult | null {
  'worklet'
  const Skia = SkiaProxy.Skia

  if (frame.pixelFormat !== 'rgb') return null

  const width = frame.width
  const height = frame.height
  const bytesPerRow = frame.bytesPerRow
  const buffer = frame.toArrayBuffer()
  const data = new Uint8Array(buffer)

  const gains = new Uint8Array(mapWidth * mapHeight * 4)
  const gainFloats = new Array<number>(mapWidth * mapHeight * 3)
  const rOffset = isIOS ? 2 : 0
  const gOffset = 1
  const bOffset = isIOS ? 0 : 2

  const cellWidth = width / mapWidth
  const cellHeight = height / mapHeight
  const step = Math.max(1, sampleStep)
  const eps = 1.0

  let targetLuma = 0
  if (forceWhite) {
    const percentile = clamp(targetLumaPercentile, 0.5, 0.99)
    const lumas: number[] = []
    for (let y = 0; y < height; y += step) {
      const row = y * bytesPerRow
      for (let x = 0; x < width; x += step) {
        const idx = row + x * 4
        const r = data[idx + rOffset]
        const g = data[idx + gOffset]
        const b = data[idx + bOffset]
        lumas.push((r + g + b) / 3)
      }
    }

    if (lumas.length > 0) {
      lumas.sort((a, b) => a - b)
      const targetIndex = Math.min(lumas.length - 1, Math.floor(lumas.length * percentile))
      targetLuma = lumas[targetIndex]
    }
  }

  for (let my = 0; my < mapHeight; my++) {
    const yStart = Math.floor(my * cellHeight)
    const yEnd = Math.floor((my + 1) * cellHeight)
    for (let mx = 0; mx < mapWidth; mx++) {
      const xStart = Math.floor(mx * cellWidth)
      const xEnd = Math.floor((mx + 1) * cellWidth)

      let sumR = 0
      let sumG = 0
      let sumB = 0
      let count = 0

      for (let y = yStart; y < yEnd; y += step) {
        const row = y * bytesPerRow
        for (let x = xStart; x < xEnd; x += step) {
          const idx = row + x * 4
          const r = data[idx + rOffset]
          const g = data[idx + gOffset]
          const b = data[idx + bOffset]
          sumR += r
          sumG += g
          sumB += b
          count++
        }
      }

      let gainR = 1.0
      let gainG = 1.0
      let gainB = 1.0

      if (count > 0) {
        const r = sumR / count
        const g = sumG / count
        const b = sumB / count
        const avg = (r + g + b) / 3

        if (forceWhite && targetLuma > 0) {
          gainR = clamp(targetLuma / (r + eps), minGain, maxGain)
          gainG = clamp(targetLuma / (g + eps), minGain, maxGain)
          gainB = clamp(targetLuma / (b + eps), minGain, maxGain)
        } else if (avg >= minLuma) {
          gainR = clamp(avg / (r + eps), minGain, maxGain)
          gainG = clamp(avg / (g + eps), minGain, maxGain)
          gainB = clamp(avg / (b + eps), minGain, maxGain)
        }
      }

      const out = (my * mapWidth + mx) * 4
      const outFloat = (my * mapWidth + mx) * 3
      gains[out + 0] = Math.round((gainR / maxGain) * 255)
      gains[out + 1] = Math.round((gainG / maxGain) * 255)
      gains[out + 2] = Math.round((gainB / maxGain) * 255)
      gains[out + 3] = 255
      gainFloats[outFloat + 0] = gainR
      gainFloats[outFloat + 1] = gainG
      gainFloats[outFloat + 2] = gainB
    }
  }

  const imageInfo = {
    width: mapWidth,
    height: mapHeight,
    colorType: Skia.ColorType.RGBA_8888,
    alphaType: Skia.AlphaType.Unpremul,
  }
  const skData = Skia.Data.fromBytes(gains)
  const image = Skia.Image.MakeImage(imageInfo, skData, mapWidth * 4)
  if (image == null) return null
  return { image, gains: gainFloats }
}

function sampleCorrectedColor(
  frame: Frame,
  isIOS: boolean,
  mapWidth: number,
  mapHeight: number,
  gains: number[] | null,
  samplePointX: number,
  samplePointY: number,
  sampleRadius: number,
): WhiteBalanceSample | null {
  'worklet'
  if (frame.pixelFormat !== 'rgb') return null
  if (mapWidth <= 0 || mapHeight <= 0) return null

  const width = frame.width
  const height = frame.height
  const bytesPerRow = frame.bytesPerRow
  if (width <= 0 || height <= 0) return null

  const buffer = frame.toArrayBuffer()
  const data = new Uint8Array(buffer)
  const rOffset = isIOS ? 2 : 0
  const gOffset = 1
  const bOffset = isIOS ? 0 : 2

  const safeX = clamp(samplePointX, 0, 1)
  const safeY = clamp(samplePointY, 0, 1)
  const centerX = Math.round(safeX * (width - 1))
  const centerY = Math.round(safeY * (height - 1))
  const radius = Math.max(0, Math.floor(sampleRadius))
  const startX = Math.max(0, centerX - radius)
  const endX = Math.min(width - 1, centerX + radius)
  const startY = Math.max(0, centerY - radius)
  const endY = Math.min(height - 1, centerY + radius)

  const cellWidth = width / mapWidth
  const cellHeight = height / mapHeight

  let sumR = 0
  let sumG = 0
  let sumB = 0
  let count = 0

  for (let y = startY; y <= endY; y++) {
    const row = y * bytesPerRow
    const cellY = Math.min(mapHeight - 1, Math.floor(y / cellHeight))
    for (let x = startX; x <= endX; x++) {
      const idx = row + x * 4
      const r = data[idx + rOffset]
      const g = data[idx + gOffset]
      const b = data[idx + bOffset]

      let gainR = 1.0
      let gainG = 1.0
      let gainB = 1.0
      if (gains != null) {
        const cellX = Math.min(mapWidth - 1, Math.floor(x / cellWidth))
        const gainIdx = (cellY * mapWidth + cellX) * 3
        gainR = gains[gainIdx + 0] ?? 1.0
        gainG = gains[gainIdx + 1] ?? 1.0
        gainB = gains[gainIdx + 2] ?? 1.0
      }

      sumR += r * gainR
      sumG += g * gainG
      sumB += b * gainB
      count++
    }
  }

  if (count === 0) return null

  return {
    r: clamp(Math.round(sumR / count), 0, 255),
    g: clamp(Math.round(sumG / count), 0, 255),
    b: clamp(Math.round(sumB / count), 0, 255),
  }
}

export function useWhiteBalanceShadingCorrection(
  options: WhiteBalanceShadingCorrectionOptions = {},
): WhiteBalanceShadingCorrectionResult {
  const mapSize = options.mapSize ?? { width: 32, height: 24 }
  const mapWidth = mapSize.width
  const mapHeight = mapSize.height
  const sampleStep = options.sampleStep ?? 4
  const minGain = options.minGain ?? 0.5
  const maxGain = options.maxGain ?? 2.0
  const minLuma = options.minLuma ?? 8
  const forceWhite = options.forceWhite ?? false
  const targetLumaPercentile = options.targetLumaPercentile ?? 0.9
  const samplePoint = options.samplePoint ?? { x: 0.5, y: 0.5 }
  const samplePointX = samplePoint.x ?? 0.5
  const samplePointY = samplePoint.y ?? 0.5
  const sampleRadius = options.sampleRadius ?? 2
  const sampleIntervalMs = options.sampleIntervalMs ?? 120
  const isIOS = Platform.OS === 'ios'

  const shouldCapture = WorkletsProxy.useSharedValue(false)
  const gainMap = WorkletsProxy.useSharedValue<SkImage | null>(null)
  const gainMapGains = WorkletsProxy.useSharedValue<number[] | null>(null)
  const gainMapFrameSize = WorkletsProxy.useSharedValue<{ width: number; height: number } | null>(null)
  const lastSampleTime = WorkletsProxy.useSharedValue(0)

  const runOnSample = useMemo(() => {
    if (options.onSample == null) return undefined
    const Worklets = WorkletsProxy.Worklets
    return Worklets.createRunOnJS(options.onSample)
  }, [options.onSample])

  const onAutoWhiteBalanceCalibrated = useCallback(() => {
    shouldCapture.value = true
  }, [shouldCapture])

  const reset = useCallback(() => {
    shouldCapture.value = false
    VisionCameraProxy.workletContext?.runAsync(() => {
      'worklet'
      if (gainMap.value != null) {
        gainMap.value.dispose()
        gainMap.value = null
      }
      gainMapGains.value = null
      gainMapFrameSize.value = null
      lastSampleTime.value = 0
    })
  }, [gainMap, gainMapFrameSize, gainMapGains, lastSampleTime, shouldCapture])

  useEffect(() => {
    return () => {
      VisionCameraProxy.workletContext?.runAsync(() => {
        'worklet'
        if (gainMap.value != null) {
          gainMap.value.dispose()
          gainMap.value = null
        }
        gainMapGains.value = null
        gainMapFrameSize.value = null
        shouldCapture.value = false
        lastSampleTime.value = 0
      })
    }
  }, [gainMap, gainMapFrameSize, gainMapGains, lastSampleTime, shouldCapture])

  const frameProcessor = useSkiaFrameProcessor(
    (frame) => {
      'worklet'
      const Skia = SkiaProxy.Skia
      const cache = globalThis as unknown as { __visionCameraWbRuntimeEffect?: unknown }
      let runtimeEffect = cache.__visionCameraWbRuntimeEffect
      if (runtimeEffect == null) {
        runtimeEffect = Skia.RuntimeEffect.Make(SHADER_SOURCE)
        cache.__visionCameraWbRuntimeEffect = runtimeEffect
      }
      if (runtimeEffect == null) {
        frame.render()
        return
      }

      if (shouldCapture.value) {
        shouldCapture.value = false
        const result = computeGainMap(
          frame,
          isIOS,
          mapWidth,
          mapHeight,
          sampleStep,
          minGain,
          maxGain,
          minLuma,
          forceWhite,
          targetLumaPercentile,
        )
        if (result != null) {
          if (gainMap.value != null) gainMap.value.dispose()
          gainMap.value = result.image
          gainMapGains.value = result.gains
          gainMapFrameSize.value = { width: frame.width, height: frame.height }
        }
      }

      const map = gainMap.value
      const size = gainMapFrameSize.value
      const gains =
        size != null && size.width === frame.width && size.height === frame.height ? gainMapGains.value : null

      if (runOnSample != null) {
        const now = performance.now()
        if (sampleIntervalMs <= 0 || now - lastSampleTime.value >= sampleIntervalMs) {
          lastSampleTime.value = now
          const color = sampleCorrectedColor(
            frame,
            isIOS,
            mapWidth,
            mapHeight,
            gains,
            samplePointX,
            samplePointY,
            sampleRadius,
          )
          if (color != null) runOnSample(color)
        }
      }

      if (map == null || size == null || size.width !== frame.width || size.height !== frame.height) {
        frame.render()
        return
      }

      const frameShader = frame.__skImage.makeShaderOptions(
        Skia.TileMode.Clamp,
        Skia.TileMode.Clamp,
        Skia.FilterMode.Linear,
        Skia.MipmapMode.None,
      )

      const scaleX = map.width() / frame.width
      const scaleY = map.height() / frame.height
      const matrix = Skia.Matrix()
      matrix.scale(scaleX, scaleY)
      const gainShader = map.makeShaderOptions(
        Skia.TileMode.Clamp,
        Skia.TileMode.Clamp,
        Skia.FilterMode.Linear,
        Skia.MipmapMode.None,
        matrix,
      )

      const shader = (runtimeEffect as { makeShaderWithChildren: (u: number[], c: unknown[]) => unknown }).makeShaderWithChildren(
        [maxGain],
        [frameShader, gainShader],
      )

      const paint = Skia.Paint()
      paint.setShader(shader as never)
      frame.drawRect(Skia.XYWHRect(0, 0, frame.width, frame.height), paint)

      ;(shader as { dispose: () => void }).dispose()
      ;(frameShader as { dispose: () => void }).dispose()
      ;(gainShader as { dispose: () => void }).dispose()
      paint.dispose()
    },
    [
      isIOS,
      mapWidth,
      mapHeight,
      sampleStep,
      minGain,
      maxGain,
      minLuma,
      forceWhite,
      targetLumaPercentile,
      samplePointX,
      samplePointY,
      sampleRadius,
      sampleIntervalMs,
      runOnSample,
    ],
  )

  return { frameProcessor, onAutoWhiteBalanceCalibrated, reset }
}
