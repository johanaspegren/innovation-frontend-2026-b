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
import kotlin.math.max

private data class Letterbox(
    val scale: Float,
    val dx: Float,
    val dy: Float,
    val resizedW: Int,
    val resizedH: Int
)


class PostItDetector(context: Context) {

    data class Detection(val rect: RectF, val label: String, val score: Float, val trackId: Int? = null)

    // Internal tracked box (stable across frames)
    private data class Track(
        val id: Int,
        var rect: RectF,
        var label: String,
        var score: Float,
        var age: Int = 0,          // frames since created
        var missed: Int = 0        // frames since last matched
    )

    private val interpreter: Interpreter
    private val gpuDelegate: GpuDelegate?
    private val labels: List<String>
    private val inputShape: IntArray

    // YOLOv8 single-class output assumed: [1, 5, 8400]
    // If yours differs later, we can make this dynamic.
    private val outputBuffer = Array(1) { Array(5) { FloatArray(8400) } }

    // Build preprocessors once (no per-frame allocations)
    private val baseProcessor: ImageProcessor
    private val rotProcessors: Map<Int, ImageProcessor>

    // Tracking/smoothing config
    private var nextTrackId = 1
    private val tracks = mutableListOf<Track>()

    private val confThreshold = 0.10f          // raise for stability
    private val nmsIouThreshold = 0.45f

    // Tracking params
    private val matchIouThreshold = 0.30f      // how strict matching is
    private val maxMissedFrames = 6            // keep track alive this many frames
    private val maxTracks = 30                 // sanity cap
    private val alpha = 0.65f                  // EMA weight for new box (higher = snappier, lower = smoother)

    init {
        val model: MappedByteBuffer = FileUtil.loadMappedFile(context, "best_float16.tflite")
        val options = Interpreter.Options()

        gpuDelegate = try {
            GpuDelegate().also { options.addDelegate(it) }
        } catch (e: Exception) {
            Log.w("PostItDetector", "GPU delegate failed, using CPU", e)
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
        val modelHeight = inputShape[1]
        val modelWidth = inputShape[2]
        Log.i("PostItDetector", "Model Input Shape: ${inputShape.contentToString()}")

        // IMPORTANT: YOLO expects /255 normalization (0..1), not [-1..1]
        baseProcessor = ImageProcessor.Builder()
            .add(ResizeOp(modelHeight, modelWidth, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(0.0f, 255.0f))
            .build()

        // Cache rotation processors (only 4 possible)
        rotProcessors = mapOf(
            0 to ImageProcessor.Builder().build(),
            90 to ImageProcessor.Builder().add(Rot90Op(-1)).build(),
            180 to ImageProcessor.Builder().add(Rot90Op(-2)).build(),
            270 to ImageProcessor.Builder().add(Rot90Op(-3)).build()
        )
    }

    private fun letterbox(bitmap: Bitmap, dstSize: Int): Pair<Bitmap, Letterbox> {
        val srcW = bitmap.width
        val srcH = bitmap.height

        val scale = minOf(
            dstSize.toFloat() / srcW,
            dstSize.toFloat() / srcH
        )

        val resizedW = (srcW * scale).toInt()
        val resizedH = (srcH * scale).toInt()

        val dx = (dstSize - resizedW) / 2f
        val dy = (dstSize - resizedH) / 2f

        val resized = Bitmap.createScaledBitmap(bitmap, resizedW, resizedH, true)
        val output = Bitmap.createBitmap(dstSize, dstSize, Bitmap.Config.ARGB_8888)

        val canvas = android.graphics.Canvas(output)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(resized, dx, dy, null)

        return output to Letterbox(scale, dx, dy, resizedW, resizedH)
    }

    fun detect(bitmap: Bitmap, rotationDegrees: Int = 0): List<Detection> {
        val modelHeight = inputShape[1]
        val modelWidth = inputShape[2]

        // 1) Preprocess (rotate then resize+normalize)
        //var tensorImage = TensorImage(DataType.FLOAT32)
        //tensorImage.load(bitmap)

        val rot = ((rotationDegrees % 360) + 360) % 360
        val rotProc = rotProcessors[rot] ?: rotProcessors[0]!!
        //tensorImage = rotProc.process(tensorImage)
        //tensorImage = baseProcessor.process(tensorImage)

        val (lbBitmap, lb) = letterbox(bitmap, modelWidth)

        var tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(lbBitmap)
        val imageProcessor = ImageProcessor.Builder()
            .add(NormalizeOp(0f, 255f))   // scale to 0..1
            .build()

        tensorImage = imageProcessor.process(tensorImage)


        // 2) Inference
        interpreter.run(tensorImage.buffer, outputBuffer)

        // 3) Decode -> detections (in ORIGINAL bitmap coordinates)
        val detections = mutableListOf<Detection>()

        val isRotated90or270 = rot == 90 || rot == 270
        val rotatedWidth = if (isRotated90or270) bitmap.height else bitmap.width
        val rotatedHeight = if (isRotated90or270) bitmap.width else bitmap.height

        //val scaleX = rotatedWidth.toFloat() / modelWidth
        //val scaleY = rotatedHeight.toFloat() / modelHeight

        for (i in 0 until 8400) {
            val score = outputBuffer[0][4][i]
            if (score < confThreshold) continue

            var xCenter = outputBuffer[0][0][i]
            var yCenter = outputBuffer[0][1][i]
            var w = outputBuffer[0][2][i]
            var h = outputBuffer[0][3][i]

            // Normalized 0..1 -> pixels
            if (xCenter <= 1.1f) {
                xCenter *= modelWidth
                yCenter *= modelHeight
                w *= modelWidth
                h *= modelHeight
            }

            // Box in rotated space
            val xCenterPx = xCenter
            val yCenterPx = yCenter
            val wPx = w
            val hPx = h

// ---- UN-LETTERBOX ----
            val x = (xCenterPx - lb.dx) / lb.scale
            val y = (yCenterPx - lb.dy) / lb.scale
            val boxW = wPx / lb.scale
            val boxH = hPx / lb.scale

            val left = x - boxW / 2f
            val top = y - boxH / 2f
            val right = x + boxW / 2f
            val bottom = y + boxH / 2f

// Clamp to bitmap bounds (important for stability)
            val clamped = RectF(
                left.coerceIn(0f, bitmap.width.toFloat()),
                top.coerceIn(0f, bitmap.height.toFloat()),
                right.coerceIn(0f, bitmap.width.toFloat()),
                bottom.coerceIn(0f, bitmap.height.toFloat())
            )

            detections.add(
                Detection(
                    rect = clamped,
                    label = labels.firstOrNull() ?: "postit",
                    score = score
                )
            )
        }

        // 4) NMS
        val nmsDetections = applyNMS(detections, nmsIouThreshold)

        // 5) Track + smooth (this is what makes UI feel “sticky” and responsive)
        val tracked = updateTracks(nmsDetections)

        // 6) Return as detections with stable track IDs
        return tracked.map {
            Detection(it.rect, it.label, it.score, it.id)
        }
    }

    private fun updateTracks(current: List<Detection>): List<Track> {
        // Age all tracks, mark as missed initially
        tracks.forEach { t ->
            t.age += 1
            t.missed += 1
        }

        // Greedy matching: highest score detections match best IoU track
        val detectionsSorted = current.sortedByDescending { it.score }
        val matchedTracks = BooleanArray(tracks.size)
        val unmatchedDetections = mutableListOf<Detection>()

        for (det in detectionsSorted) {
            var bestIdx = -1
            var bestIou = 0f

            for (i in tracks.indices) {
                if (matchedTracks[i]) continue
                val iou = calculateIoU(det.rect, tracks[i].rect)
                if (iou > bestIou) {
                    bestIou = iou
                    bestIdx = i
                }
            }

            if (bestIdx != -1 && bestIou >= matchIouThreshold) {
                // Match found: update track with EMA smoothing
                val t = tracks[bestIdx]
                matchedTracks[bestIdx] = true
                t.missed = 0

                t.rect = emaRect(t.rect, det.rect, alpha)
                t.score = max(t.score * 0.6f, det.score) // keep stable confidence feel
                t.label = det.label
            } else {
                unmatchedDetections.add(det)
            }
        }

        // Add new tracks for unmatched detections
        for (det in unmatchedDetections) {
            if (tracks.size < maxTracks) {
                tracks.add(
                    Track(
                        id = nextTrackId++,
                        rect = RectF(det.rect),
                        label = det.label,
                        score = det.score,
                        age = 0,
                        missed = 0
                    )
                )
            }
        }

        // Drop stale tracks
        tracks.removeAll { it.missed > maxMissedFrames }

        // Sort for UI (stable, highest confidence first)
        return tracks.sortedByDescending { it.score }
    }

    private fun emaRect(prev: RectF, cur: RectF, alpha: Float): RectF {
        val inv = 1f - alpha
        return RectF(
            alpha * cur.left + inv * prev.left,
            alpha * cur.top + inv * prev.top,
            alpha * cur.right + inv * prev.right,
            alpha * cur.bottom + inv * prev.bottom
        )
    }

    private fun applyNMS(detections: List<Detection>, iouThreshold: Float): List<Detection> {
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
                if (calculateIoU(a.rect, b.rect) > iouThreshold) {
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
        val union = rect1.width() * rect1.height() + rect2.width() * rect2.height() - intersectionArea
        return if (union <= 0f) 0f else intersectionArea / union
    }

    fun close() {
        interpreter.close()
        gpuDelegate?.close()
    }
}
