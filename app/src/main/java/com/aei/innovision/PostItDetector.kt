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

    // Temporal smoothing: track detections across frames
    private var previousDetections: List<Detection> = emptyList()
    private var framesSinceDetection: Int = 0
    private val maxFramesToKeep = 5  // Keep previous detections for up to 5 frames
    private val iouMatchThreshold = 0.3f  // IoU threshold to consider same object

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

        // 1. Prepare image - rotate to upright orientation for model
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

        // The model sees the rotated image. After rotation:
        // - 90° or 270°: width and height are swapped
        // - 0° or 180°: dimensions stay the same
        val isRotated90or270 = rotationDegrees == 90 || rotationDegrees == 270
        val rotatedWidth = if (isRotated90or270) bitmap.height else bitmap.width
        val rotatedHeight = if (isRotated90or270) bitmap.width else bitmap.height

        for (i in 0 until 8400) {
            val score = outputBuffer[0][4][i]
            if (score > 0.25f) {  // Slightly higher threshold for more stable detections
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

                // Scale from model space (640x640) to rotated image space
                val scaleX = rotatedWidth.toFloat() / modelWidth
                val scaleY = rotatedHeight.toFloat() / modelHeight

                // Calculate box in rotated space
                val rotLeft = (xCenter - w / 2f) * scaleX
                val rotTop = (yCenter - h / 2f) * scaleY
                val rotRight = (xCenter + w / 2f) * scaleX
                val rotBottom = (yCenter + h / 2f) * scaleY

                // Transform coordinates back to original bitmap space based on rotation
                val (left, top, right, bottom) = when (rotationDegrees) {
                    90 -> {
                        // Rotated 90° CW: (x, y) in rotated -> (y, width - x) in original
                        arrayOf(
                            rotTop,
                            rotatedWidth - rotRight,
                            rotBottom,
                            rotatedWidth - rotLeft
                        )
                    }
                    180 -> {
                        // Rotated 180°: (x, y) -> (width - x, height - y)
                        arrayOf(
                            rotatedWidth - rotRight,
                            rotatedHeight - rotBottom,
                            rotatedWidth - rotLeft,
                            rotatedHeight - rotTop
                        )
                    }
                    270 -> {
                        // Rotated 270° CW (90° CCW): (x, y) -> (height - y, x)
                        arrayOf(
                            rotatedHeight - rotBottom,
                            rotLeft,
                            rotatedHeight - rotTop,
                            rotRight
                        )
                    }
                    else -> {
                        // No rotation
                        arrayOf(rotLeft, rotTop, rotRight, rotBottom)
                    }
                }

                Log.d("PostItDetector", "Detection: score=$score, rect=($left, $top, $right, $bottom)")
                detections.add(Detection(RectF(left, top, right, bottom), labels[0], score))
            }
        }

        val nmsDetections = applyNMS(detections)

        // Apply temporal smoothing
        return applyTemporalSmoothing(nmsDetections)
    }

    private fun applyTemporalSmoothing(currentDetections: List<Detection>): List<Detection> {
        if (currentDetections.isEmpty()) {
            // No detections this frame - keep previous detections for a few frames
            framesSinceDetection++
            return if (framesSinceDetection <= maxFramesToKeep) {
                // Return previous detections with decayed scores
                previousDetections.map { det ->
                    val decayFactor = 1f - (framesSinceDetection.toFloat() / (maxFramesToKeep + 1))
                    Detection(det.rect, det.label, det.score * decayFactor)
                }
            } else {
                previousDetections = emptyList()
                emptyList()
            }
        }

        // We have detections - reset counter and smooth positions
        framesSinceDetection = 0

        val smoothedDetections = currentDetections.map { current ->
            // Find matching previous detection
            val matchingPrev = previousDetections.find { prev ->
                calculateIoU(current.rect, prev.rect) > iouMatchThreshold
            }

            if (matchingPrev != null) {
                // Smooth the position with exponential moving average
                val alpha = 0.6f  // Weight for current detection (higher = less smoothing)
                val smoothedRect = RectF(
                    alpha * current.rect.left + (1 - alpha) * matchingPrev.rect.left,
                    alpha * current.rect.top + (1 - alpha) * matchingPrev.rect.top,
                    alpha * current.rect.right + (1 - alpha) * matchingPrev.rect.right,
                    alpha * current.rect.bottom + (1 - alpha) * matchingPrev.rect.bottom
                )
                Detection(smoothedRect, current.label, current.score)
            } else {
                current
            }
        }

        previousDetections = smoothedDetections
        return smoothedDetections
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
