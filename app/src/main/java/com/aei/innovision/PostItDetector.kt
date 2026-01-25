package com.aei.innovision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.Rot90Op
import java.nio.MappedByteBuffer

class PostItDetector(context: Context) {

    data class Detection(val rect: RectF, val label: String, val score: Float)

    private val interpreter: Interpreter
    private val gpuDelegate: GpuDelegate?
    private val labels: List<String>
    private val inputShape: IntArray
    
    // YOLOv8 output shape is [1, 5, 8400]
    private var outputBuffer = Array(1) { Array(5) { FloatArray(8400) } }

    init {
        val model: MappedByteBuffer = FileUtil.loadMappedFile(context, "best_float16.tflite")
        val options = Interpreter.Options()
        
        gpuDelegate = try {
            GpuDelegate().also { options.addDelegate(it) }
        } catch (e: Exception) {
            Log.e("PostItDetector", "GPU Delegate failed to load, falling back to CPU", e)
            options.setNumThreads(4)
            null
        }

        interpreter = Interpreter(model, options)
        
        labels = try {
            FileUtil.loadLabels(context, "labels.txt")
        } catch (e: Exception) {
            listOf("postit")
        }

        inputShape = interpreter.getInputTensor(0).shape()
        Log.i("PostItDetector", "Model Input Shape: ${inputShape.contentToString()}")
    }

    fun detect(bitmap: Bitmap, rotationDegrees: Int = 0): List<Detection> {
        val modelHeight = inputShape[1]
        val modelWidth = inputShape[2]

        // 1. Prepare image
        val imageProcessor = ImageProcessor.Builder()
            .add(Rot90Op(-rotationDegrees / 90))
            .add(ResizeOp(modelHeight, modelWidth, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(127.5f, 127.5f)) 
            .build()

        var tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        // 2. Inference
        interpreter.run(tensorImage.buffer, outputBuffer)

        val detections = mutableListOf<Detection>()
        val isRotated = rotationDegrees == 90 || rotationDegrees == 270
        val uprightWidth = if (isRotated) bitmap.height else bitmap.width
        val uprightHeight = if (isRotated) bitmap.height else bitmap.width // WAIT: this should be bitmap.width if rotated? No, let's fix the logic below.

        // Correct dimensions of the UPRIGHT image the model sees
        val modelSpaceWidth = if (isRotated) bitmap.height else bitmap.width
        val modelSpaceHeight = if (isRotated) bitmap.width else bitmap.height

        for (i in 0 until 8400) {
            val score = outputBuffer[0][4][i]
            if (score > 0.15f) { 
                var xCenter = outputBuffer[0][0][i]
                var yCenter = outputBuffer[0][1][i]
                var w = outputBuffer[0][2][i]
                var h = outputBuffer[0][3][i]

                // If values are normalized 0..1, scale to model pixels (640)
                if (xCenter <= 1.1f) {
                    xCenter *= modelWidth
                    yCenter *= modelHeight
                    w *= modelWidth
                    h *= modelHeight
                }

                // Scale from model space (640x640) to original LANDSCAPE bitmap space
                // Camera frames are usually landscape. We need to map model boxes back to that landscape frame.
                val scaleX = bitmap.width.toFloat() / modelWidth
                val scaleY = bitmap.height.toFloat() / modelHeight

                // Important: If we rotated the image by 90 deg, X in model space maps to Y in bitmap space!
                // Let's simplify: map to the coordinates of the bitmap *after* rotation, then rotate the rect back.
                
                // For now, let's just log everything to debug the "off-centered" issue
                val left = (xCenter - w / 2f) * scaleX
                val top = (yCenter - h / 2f) * scaleY
                val right = (xCenter + w / 2f) * scaleX
                val bottom = (yCenter + h / 2f) * scaleY

                Log.d("PostItDetector", "MATCH! Score: $score, xCenter: $xCenter, yCenter: $yCenter, w: $w, h: $h")
                
                detections.add(Detection(RectF(left, top, right, bottom), labels[0], score))
            }
        }

        return applyNMS(detections)
    }

    private fun applyNMS(detections: List<Detection>): List<Detection> {
        val sorted = detections.sortedByDescending { it.score }
        val result = mutableListOf<Detection>()
        val ignored = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (ignored[i]) continue
            val a = sorted[i]
            result.add(a)
            for (j in i + 1 until sorted.size) {
                if (ignored[j]) continue
                val b = sorted[j]
                if (calculateIoU(a.rect, b.rect) > 0.45f) {
                    ignored[j] = true
                }
            }
        }
        return result
    }

    private fun calculateIoU(rect1: RectF, rect2: RectF): Float {
        val intersection = RectF()
        if (!intersection.setIntersect(rect1, rect2)) return 0f
        val intersectionArea = intersection.width() * intersection.height()
        return intersectionArea / (rect1.width() * rect1.height() + rect2.width() * rect2.height() - intersectionArea)
    }

    fun close() {
        interpreter.close()
        gpuDelegate?.close()
    }
}
