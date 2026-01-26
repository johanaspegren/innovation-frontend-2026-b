package com.aei.innovision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

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

    private var detections: List<PostItDetector.Detection> = emptyList()
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0

    fun setDetections(
        detections: List<PostItDetector.Detection>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        this.detections = detections
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageWidth == 0 || imageHeight == 0) return

        val scaleX = width / imageWidth.toFloat()
        val scaleY = height / imageHeight.toFloat()

        detections.forEach { det ->

            // Scale rect to view coordinates
            val rect = RectF(
                det.rect.left * scaleX,
                det.rect.top * scaleY,
                det.rect.right * scaleX,
                det.rect.bottom * scaleY
            )

            // Draw bounding box - green if stored, yellow if pending
            val boxPaint = if (det.uploaded) storedBoxPaint else pendingBoxPaint

            canvas.drawRect(rect, boxPaint)

            val label = det.label
            val conf = String.format("%.2f", det.score)
            val statusTag = if (det.uploaded) " [STORED]" else ""

            val line1 = "$label $conf$statusTag"
            // Show OCR text if available (truncate if too long)
            val ocrText = if (det.ocrText.isNotBlank()) {
                if (det.ocrText.length > 30) det.ocrText.take(30) + "..." else det.ocrText
            } else ""

            val textPadding = 8f
            val lineHeight = textPaint.textSize + 6f
            val numLines = if (ocrText.isNotBlank()) 2 else 1

            val labelPaint = if (det.uploaded) storedTextPaint else textPaint

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
