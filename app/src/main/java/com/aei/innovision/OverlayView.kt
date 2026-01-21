package com.aei.innovision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.YELLOW
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        style = Paint.Style.FILL
    }

    private var detections: List<PostItDetector.Detection> = emptyList()
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0

    fun setDetections(detections: List<PostItDetector.Detection>, imageWidth: Int, imageHeight: Int) {
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

        detections.forEach { detection ->
            val rect = RectF(
                detection.rect.left * scaleX,
                detection.rect.top * scaleY,
                detection.rect.right * scaleX,
                detection.rect.bottom * scaleY,
            )
            canvas.drawRect(rect, paint)
            canvas.drawText(detection.label, rect.left + 8f, rect.top + 36f, textPaint)
        }
    }
}
