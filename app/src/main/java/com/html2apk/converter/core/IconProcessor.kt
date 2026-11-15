package com.html2apk.converter.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Готовит PNG-иконки для всех mipmap-плотностей из выбранной пользователем картинки.
 */
internal object IconProcessor {

    val DENSITIES: Map<String, Int> = linkedMapOf(
        "mdpi" to 48,
        "hdpi" to 72,
        "xhdpi" to 96,
        "xxhdpi" to 144,
        "xxxhdpi" to 192
    )

    fun decode(file: File, maxSize: Int = 1024): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Не удалось прочитать изображение" }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSize * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IllegalStateException("Не удалось декодировать изображение")
    }

    fun build(source: Bitmap): Map<String, ByteArray> {
        val side = min(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        val square = Bitmap.createBitmap(source, left, top, side, side)

        val result = LinkedHashMap<String, ByteArray>()
        DENSITIES.forEach { (qualifier, pixels) ->
            val scaled = Bitmap.createScaledBitmap(square, pixels, pixels, true)
            val rounded = roundedCorners(scaled)
            val output = ByteArrayOutputStream()
            rounded.compress(Bitmap.CompressFormat.PNG, 100, output)
            result[qualifier] = output.toByteArray()
            if (rounded !== scaled) rounded.recycle()
            if (scaled !== square) scaled.recycle()
        }
        if (square !== source) square.recycle()
        return result
    }

    fun buildFromPalette(): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        DENSITIES.forEach { (qualifier, pixels) ->
            val bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = android.graphics.LinearGradient(
                0f, 0f, pixels.toFloat(), pixels.toFloat(),
                intArrayOf(0xFF395AF5.toInt(), 0xFF8A2BE2.toInt()),
                null,
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(
                RectF(0f, 0f, pixels.toFloat(), pixels.toFloat()),
                pixels * 0.26f, pixels * 0.26f, paint
            )
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            result[qualifier] = output.toByteArray()
            bitmap.recycle()
        }
        return result
    }

    private fun roundedCorners(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val path = Path().apply {
            addRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                width * 0.18f, height * 0.18f, Path.Direction.CW
            )
        }
        canvas.clipPath(path)
        canvas.drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return output
    }
}
