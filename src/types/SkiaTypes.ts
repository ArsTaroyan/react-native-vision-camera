// Minimal type placeholders for optional Skia dependency.
// These avoid hard TypeScript coupling to @shopify/react-native-skia.
import type { ComponentType } from 'react'

export interface SkShader {
  dispose(): void
}

export interface SkPaint {
  setShader(shader: SkShader): void
  dispose(): void
}

export interface SkImage {
  width(): number
  height(): number
  makeShaderOptions(
    tileModeX: unknown,
    tileModeY: unknown,
    filterMode: unknown,
    mipmapMode: unknown,
    localMatrix?: unknown,
  ): SkShader
  makeNonTextureImage(): SkImage
  dispose(): void
}

export interface SkCanvas {
  clear(color: unknown): void
  drawImage(image: SkImage, x: number, y: number, paint?: SkPaint): void
  drawRect(rect: unknown, paint: SkPaint): void
  save(): void
  restore(): void
  translate(x: number, y: number): void
  rotate(deg: number, px: number, py: number): void
  scale(sx: number, sy: number): void
}

export interface SkSurface {
  getCanvas(): SkCanvas
  makeImageSnapshot(): SkImage
  flush(): void
  dispose(): void
}

export interface SkData {}

export interface SkRuntimeEffect {
  makeShaderWithChildren(uniforms: number[], children: SkShader[]): SkShader
}

export interface SkMatrix {
  scale(x: number, y: number): void
}

export interface SkiaModule {
  Surface: {
    MakeOffscreen(width: number, height: number): SkSurface | null
  }
  Image: {
    MakeImageFromNativeBuffer(pointer: number | bigint): SkImage
    MakeImage(info: { width: number; height: number; colorType: unknown; alphaType: unknown }, data: SkData, rowBytes: number): SkImage | null
  }
  Data: {
    fromBytes(bytes: Uint8Array): SkData
  }
  RuntimeEffect: {
    Make(source: string): SkRuntimeEffect | null
  }
  Paint: () => SkPaint
  Matrix: () => SkMatrix
  XYWHRect: (x: number, y: number, width: number, height: number) => unknown
  Color: (color: string) => unknown
  ColorType: { RGBA_8888: unknown }
  AlphaType: { Unpremul: unknown }
  TileMode: { Clamp: unknown }
  FilterMode: { Linear: unknown }
  MipmapMode: { None: unknown }
}

export interface SkiaProxyModule {
  Skia: SkiaModule
  Canvas: ComponentType<any>
  Image: ComponentType<any>
}
