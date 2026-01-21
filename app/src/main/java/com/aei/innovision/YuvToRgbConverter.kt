package com.aei.innovision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

class YuvToRgbConverter(private val context: Context) {
    fun yuvToRgb(image: android.media.Image, output: Bitmap) {
        val yuvBytes = yuv420ToNv21(image)
        val yuvImage = YuvImage(yuvBytes, ImageFormat.NV21, image.width, image.height, null)
        val outputStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, outputStream)
        val jpegBytes = outputStream.toByteArray()
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        val canvas = android.graphics.Canvas(output)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    private fun yuv420ToNv21(image: android.media.Image): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        return nv21
    }
}
