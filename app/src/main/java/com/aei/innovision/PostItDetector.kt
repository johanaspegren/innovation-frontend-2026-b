package com.aei.innovision

import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.Color

class PostItDetector {
    data class Detection(val rect: RectF, val label: String)

    fun detect(bitmap: Bitmap, step: Int = 10): List<Detection> {
        val detections = mutableListOf<Detection>()
        val ranges = listOf(
            ColorRange("Yellow", 40f, 75f, 0.2f, 1f, 0.6f, 1f),
            ColorRange("Pink", 300f, 355f, 0.2f, 1f, 0.5f, 1f),
            ColorRange("Blue", 180f, 250f, 0.2f, 1f, 0.3f, 1f),
            ColorRange("Green", 80f, 160f, 0.2f, 1f, 0.3f, 1f),
        )

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        ranges.forEach { range ->
            var minX = width
            var minY = height
            var maxX = 0
            var maxY = 0
            var count = 0

            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    val color = pixels[y * width + x]
                    if (range.matches(color)) {
                        if (x < minX) minX = x
                        if (y < minY) minY = y
                        if (x > maxX) maxX = x
                        if (y > maxY) maxY = y
                        count++
                    }
                    x += step
                }
                y += step
            }

            if (count > 50 && minX < maxX && minY < maxY) {
                detections.add(
                    Detection(
                        RectF(minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat()),
                        range.label,
                    ),
                )
            }
        }

        return detections
    }

    private data class ColorRange(
        val label: String,
        val hueMin: Float,
        val hueMax: Float,
        val satMin: Float,
        val satMax: Float,
        val valMin: Float,
        val valMax: Float,
    ) {
        fun matches(colorInt: Int): Boolean {
            val hsv = FloatArray(3)
            Color.colorToHSV(colorInt, hsv)
            val hue = hsv[0]
            val sat = hsv[1]
            val value = hsv[2]

            val hueMatches = if (hueMin <= hueMax) {
                hue in hueMin..hueMax
            } else {
                hue >= hueMin || hue <= hueMax
            }

            return hueMatches &&
                sat in satMin..satMax &&
                value in valMin..valMax
        }
    }
}
