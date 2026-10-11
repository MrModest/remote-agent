package dev.remoteagent.app.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Readies images for agents, whose APIs reject large ones (Claude: 5 MB once
 * base64-encoded, 8000 px on a side), and decodes the ones shown.
 * Mirrors apple/Shared/ImageCoding.swift.
 */
object ImageCoding {
    /** The long side of an image sent with a prompt. */
    const val MAX_PIXELS = 2048
    /** Under 5 MB once base64-encoded. */
    const val MAX_BYTES = 3_500_000

    class Prepared(val data: ByteArray, val thumbnail: Bitmap)

    /**
     * Scales an image down to `MAX_PIXELS`, applies its orientation and
     * re-encodes it without metadata (no location): PNG when it was a PNG or
     * has transparency, so screenshots stay sharp, else JPEG.
     */
    fun prepare(bytes: ByteArray): Prepared? {
        var png = false
        val image = decode(bytes, MAX_PIXELS) { png = it == "image/png" } ?: return null
        val small = scaled(image, 240)
        if (png || image.hasAlpha()) {
            encode(image, Bitmap.CompressFormat.PNG, 100)?.takeIf { it.size <= MAX_BYTES }?.let { return Prepared(it, small) }
        }
        val opaque = if (image.hasAlpha()) flattened(image) else image
        for (quality in listOf(85, 60)) {
            encode(opaque, Bitmap.CompressFormat.JPEG, quality)?.takeIf { it.size <= MAX_BYTES }?.let { return Prepared(it, small) }
        }
        return null
    }

    /** The image at most `maxPixels` on its long side, upright. */
    fun thumbnail(bytes: ByteArray, maxPixels: Int): Bitmap? = decode(bytes, maxPixels)

    private fun decode(bytes: ByteArray, maxPixels: Int, onType: (String) -> Unit = {}): Bitmap? = try {
        // ImageDecoder applies the EXIF orientation itself.
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            onType(info.mimeType)
            val w = info.size.width
            val h = info.size.height
            val long = max(w, h)
            if (long > maxPixels) {
                val scale = maxPixels.toFloat() / long
                decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: Exception) {
        null
    }

    private fun scaled(image: Bitmap, maxPixels: Int): Bitmap {
        val long = max(image.width, image.height)
        if (long <= maxPixels) return image
        val scale = maxPixels.toFloat() / long
        return image.scale((image.width * scale).roundToInt().coerceAtLeast(1), (image.height * scale).roundToInt().coerceAtLeast(1))
    }

    private fun encode(image: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        return if (image.compress(format, quality, out)) out.toByteArray() else null
    }

    /** The image on white, for JPEG, which has no transparency. */
    private fun flattened(image: Bitmap): Bitmap {
        val out = createBitmap(image.width, image.height)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(image, 0f, 0f, null)
        }
        return out
    }
}
