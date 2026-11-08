package io.github.revocx35.camspoof.camera

import android.graphics.Bitmap

/**
 * Converts an ARGB_8888 [Bitmap] to NV21 (BT.601 full→video range, Y plane followed by interleaved
 * V,U). Buffers are caller-owned and reused to avoid per-frame allocation. This CPU loop is the
 * simple first cut; moving it to RenderScript-replacement/GL or libyuv is the planned optimization
 * (see ARCHITECTURE.md "Performance").
 */
class YuvConvert(private val width: Int, private val height: Int) {
    private val pixels = IntArray(width * height)

    /** @param out must be at least `width*height*3/2` bytes. */
    fun toNv21(bitmap: Bitmap, out: ByteArray) {
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize
        var p = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                val argb = pixels[p]
                val r = (argb shr 16) and 0xff
                val g = (argb shr 8) and 0xff
                val b = argb and 0xff

                val y = (66 * r + 129 * g + 25 * b + 128 shr 8) + 16
                out[yIndex++] = clamp(y)

                // Subsample chroma at even rows and columns (NV21: V then U).
                if (j and 1 == 0 && i and 1 == 0) {
                    val u = (-38 * r - 74 * g + 112 * b + 128 shr 8) + 128
                    val v = (112 * r - 94 * g - 18 * b + 128 shr 8) + 128
                    out[uvIndex++] = clamp(v)
                    out[uvIndex++] = clamp(u)
                }
                p++
            }
        }
    }

    private fun clamp(v: Int): Byte = when {
        v < 0 -> 0
        v > 255 -> 255.toByte()
        else -> v.toByte()
    }
}
