package com.aei.innovision

import android.content.Context
import android.graphics.Bitmap
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptC
import android.renderscript.Type
import androidx.camera.core.ImageProxy
import android.media.Image
import java.nio.ByteBuffer

/**
 * Optimized YUV to RGB converter using direct byte access.
 */
class YuvToRgbConverter(context: Context) {
    private val rs = RenderScript.create(context)
    private val scriptIntrinsicYuvToRGB = android.renderscript.ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))

    fun yuvToRgb(image: Image, output: Bitmap) {
        val yuvBytes = yuv420ToNv21(image)
        
        val yuvType = Type.Builder(rs, Element.U8(rs)).setX(yuvBytes.size)
        val inAlloc = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT)
        val outAlloc = Allocation.createFromBitmap(rs, output)
        
        inAlloc.copyFrom(yuvBytes)
        scriptIntrinsicYuvToRGB.setInput(inAlloc)
        scriptIntrinsicYuvToRGB.forEach(outAlloc)
        outAlloc.copyTo(output)
        
        inAlloc.destroy()
        outAlloc.destroy()
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + ySize / 2) // NV21 is 1.5x Y size

        // Y plane
        yBuffer.get(nv21, 0, ySize)

        // UV planes (interleaved for NV21: V, U, V, U...)
        val vRemaining = vBuffer.remaining()
        vBuffer.get(nv21, ySize, vRemaining)
        
        // Note: This is a simplified conversion. For production, 
        // proper chroma subsampling handling is recommended.
        return nv21
    }
}
