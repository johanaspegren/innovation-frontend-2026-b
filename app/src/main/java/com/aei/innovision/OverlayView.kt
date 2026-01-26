package com.aei.innovision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val pendingBoxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.YELLOW
        isAntiAlias = true
    }

    private val focusedBoxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.parseColor("#4285F4") // Google/Link Blue
        isAntiAlias = true
    }

    private val lockedBoxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.CYAN
        isAntiAlias = true
    }

    private val storedBoxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.GREEN
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val focusedTextPaint = Paint().apply {
        color = Color.parseColor("#4285F4")
        textSize = 32f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val lockedTextPaint = Paint().apply {
        color = Color.CYAN
        textSize = 32f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val storedTextPaint = Paint().apply {
        color = Color.GREEN
        textSize = 32f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val bgPaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0) // translucent black
        style = Paint.Style.FILL
    }

    private val reticlePaint = Paint().apply {
        color = Color.WHITE
        alpha = 128 // subtle transparency
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private var detections: List<PostItDetector.Detection> = emptyList()
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0

    // The detection currently in the center of the view
    private var focusedDetection: PostItDetector.Detection? = null

    // Callback when a detection box is tapped (or selected via center-focus)
    var onDetectionTapped: ((PostItDetector.Detection) -> Unit)? = null

    fun setDetections(
        detections: List<PostItDetector.Detection>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        this.detections = detections
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        updateFocusedDetection()
        invalidate()
    }

    private fun updateFocusedDetection() {
        if (imageWidth == 0 || imageHeight == 0 || detections.isEmpty()) {
            focusedDetection = null
            return
        }

        val centerX = imageWidth / 2f
        val centerY = imageHeight / 2f

        // Find detection whose center is closest to image center
        focusedDetection = detections.minByOrNull { det ->
            val detCenterX = det.rect.centerX()
            val detCenterY = det.rect.centerY()
            val dx = detCenterX - centerX
            val dy = detCenterY - centerY
            sqrt((dx * dx + dy * dy).toDouble())
        }
    }

    fun getFocusedDetection(): PostItDetector.Detection? = focusedDetection

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return super.onTouchEvent(event)
        if (imageWidth == 0 || imageHeight == 0) return super.onTouchEvent(event)

        val scaleX = width / imageWidth.toFloat()
        val scaleY = height / imageHeight.toFloat()
        val touchX = event.x
        val touchY = event.y

        // Find which detection box was tapped
        val tapped = detections.firstOrNull { det ->
            val rect = RectF(
                det.rect.left * scaleX,
                det.rect.top * scaleY,
                det.rect.right * scaleX,
                det.rect.bottom * scaleY
            )
            rect.contains(touchX, touchY)
        }

        if (tapped != null) {
            onDetectionTapped?.invoke(tapped)
            return true
        }

        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageWidth == 0 || imageHeight == 0) return

        val scaleX = width / imageWidth.toFloat()
        val scaleY = height / imageHeight.toFloat()

        // Draw a subtle small reticle in the center for Glass users
        val viewCenterX = width / 2f
        val viewCenterY = height / 2f
        val reticleSize = 12f // Reduced size
        canvas.drawLine(viewCenterX - reticleSize, viewCenterY, viewCenterX + reticleSize, viewCenterY, reticlePaint)
        canvas.drawLine(viewCenterX, viewCenterY - reticleSize, viewCenterX, viewCenterY + reticleSize, reticlePaint)

        detections.forEach { det ->

            // Scale rect to view coordinates
            val rect = RectF(
                det.rect.left * scaleX,
                det.rect.top * scaleY,
                det.rect.right * scaleX,
                det.rect.bottom * scaleY
            )

            // Draw bounding box: focus=blue, green=stored, cyan=locked, yellow=pending
            val boxPaint = when {
                det == focusedDetection && !det.locked && !det.uploaded -> focusedBoxPaint
                det.uploaded -> storedBoxPaint
                det.locked -> lockedBoxPaint
                else -> pendingBoxPaint
            }

            canvas.drawRect(rect, boxPaint)

            val label = det.label
            val conf = String.format("%.2f", det.score)
            val statusTag = when {
                det.uploaded -> " [STORED]"
                det.locked -> " [LOCKED]"
                det == focusedDetection -> " [FOCUS]"
                else -> ""
            }

            val line1 = "$conf$statusTag"
            // Show OCR text if available (truncate if too long)
            val ocrText = if (det.ocrText.isNotBlank()) {
                if (det.ocrText.length > 30) det.ocrText.take(30) + "..." else det.ocrText
            } else ""

            val textPadding = 8f
            val lineHeight = textPaint.textSize + 6f
            val numLines = if (ocrText.isNotBlank()) 2 else 1

            val labelPaint = when {
                det.uploaded -> storedTextPaint
                det.locked -> lockedTextPaint
                det == focusedDetection -> focusedTextPaint
                else -> textPaint
            }

            val textWidth = maxOf(
                labelPaint.measureText(line1),
                if (ocrText.isNotBlank()) textPaint.measureText(ocrText) else 0f
            )

            val bgRect = RectF(
                rect.left,
                rect.top - lineHeight * numLines - textPadding * 2,
                rect.left + textWidth + textPadding * 2,
                rect.top
            )

            // Background for readability
            canvas.drawRect(bgRect, bgPaint)

            // Draw label line
            canvas.drawText(
                line1,
                bgRect.left + textPadding,
                bgRect.top + lineHeight,
                labelPaint
            )

            // Draw OCR text if available
            if (ocrText.isNotBlank()) {
                canvas.drawText(
                    ocrText,
                    bgRect.left + textPadding,
                    bgRect.top + lineHeight * 2,
                    textPaint
                )
            }
        }
    }
}
