package com.hiennv.flutter_callkit_incoming

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/**
 * The blurred backdrop of the incoming call screen, matching the Figma frame
 * (blur sigma about 50dp, fading into the ground at the edges).
 *
 * The photo is shrunk to a thumbnail of the screen (1/8 of its size in dp),
 * padded on every side with the ground color, blurred with a small radius and
 * cropped back. At that size a radius of 14 is as wide as sigma 50 on the full
 * screen, and the padding makes the edges fade into the ground instead of
 * repeating the border pixels.
 *
 * Pure bitmap work with no Android view or context, so it can run on any thread.
 */
object BackdropBlur {
    private const val SCALE = 8
    private const val PADDING = 20
    private const val RADIUS = 14
    private const val GROUND = 0xFF131414.toInt()

    fun create(source: Bitmap, screenWidthPx: Int, screenHeightPx: Int, density: Float): Bitmap {
        val safeDensity = if (density > 0f) density else 1f
        val width = maxOf(1, Math.round(screenWidthPx / safeDensity / SCALE))
        val height = maxOf(1, Math.round(screenHeightPx / safeDensity / SCALE))
        val paddedWidth = width + 2 * PADDING
        val paddedHeight = height + 2 * PADDING

        val padded = Bitmap.createBitmap(paddedWidth, paddedHeight, Bitmap.Config.ARGB_8888)
        Canvas(padded).apply {
            drawColor(GROUND)
            drawBitmap(
                source,
                centerCrop(source.width, source.height, width, height),
                Rect(PADDING, PADDING, PADDING + width, PADDING + height),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }

        val pixels = IntArray(paddedWidth * paddedHeight)
        padded.getPixels(pixels, 0, paddedWidth, 0, 0, paddedWidth, paddedHeight)
        padded.recycle()
        blur(pixels, paddedWidth, paddedHeight, RADIUS)

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, PADDING * paddedWidth + PADDING, paddedWidth, 0, 0, width, height)
        return result
    }

    /** The part of the source that fills the target when scaled to cover it. */
    internal fun centerCrop(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): Rect {
        if (srcWidth <= 0 || srcHeight <= 0) return Rect(0, 0, 1, 1)
        val srcAspect = srcWidth.toFloat() / srcHeight
        val dstAspect = dstWidth.toFloat() / dstHeight
        return if (srcAspect > dstAspect) {
            val w = maxOf(1, Math.round(srcHeight * dstAspect))
            val x = (srcWidth - w) / 2
            Rect(x, 0, x + w, srcHeight)
        } else {
            val h = maxOf(1, Math.round(srcWidth / dstAspect))
            val y = (srcHeight - h) / 2
            Rect(0, y, srcWidth, y + h)
        }
    }

    /** Separable triangular-kernel blur (what a stack blur computes), edges clamped. */
    internal fun blur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        val temp = IntArray(pixels.size)
        pass(pixels, temp, width, height, radius, horizontal = true)
        pass(temp, pixels, width, height, radius, horizontal = false)
    }

    private fun pass(
        src: IntArray,
        dst: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean,
    ) {
        val length = if (horizontal) width else height
        val lines = if (horizontal) height else width
        val step = if (horizontal) 1 else width
        val total = (radius + 1) * (radius + 1)
        for (line in 0 until lines) {
            val base = if (horizontal) line * width else line
            for (i in 0 until length) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (k in -radius..radius) {
                    val weight = radius + 1 - Math.abs(k)
                    val index = base + (i + k).coerceIn(0, length - 1) * step
                    val p = src[index]
                    a += weight * (p ushr 24)
                    r += weight * ((p shr 16) and 0xFF)
                    g += weight * ((p shr 8) and 0xFF)
                    b += weight * (p and 0xFF)
                }
                dst[base + i * step] =
                    ((a / total) shl 24) or ((r / total) shl 16) or ((g / total) shl 8) or (b / total)
            }
        }
    }
}
