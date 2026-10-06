package com.logiclick.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.logiclick.app.model.NormalizedPoint
import com.logiclick.app.model.NormalizedRect
import kotlin.math.max
import kotlin.math.min

class SetupOverlayView(context: Context) : View(context) {
    enum class Mode { OCR_REGION, INPUT_POINT, SUBMIT_POINT }

    var screenshot: Bitmap? = null
        set(value) { field = value; invalidate() }
    var mode = Mode.OCR_REGION
    var ocrRegion: NormalizedRect? = null
    var inputPoint: NormalizedPoint? = null
    var submitPoint: NormalizedPoint? = null

    private val shade = Paint().apply { color = 0x553B82F6; style = Paint.Style.FILL }
    private val stroke = Paint().apply { color = Color.WHITE; strokeWidth = 5f; style = Paint.Style.STROKE }
    private val inputPaint = Paint().apply { color = 0xFF22C55E.toInt(); strokeWidth = 16f; style = Paint.Style.STROKE }
    private val submitPaint = Paint().apply { color = 0xFFEF4444.toInt(); strokeWidth = 16f; style = Paint.Style.STROKE }
    private var startX = 0f
    private var startY = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        screenshot?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), null) }
        ocrRegion?.let {
            val rect = RectF(it.left * width, it.top * height, it.right * width, it.bottom * height)
            canvas.drawRect(rect, shade)
            canvas.drawRect(rect, stroke)
        }
        inputPoint?.let { canvas.drawCircle(it.x * width, it.y * height, 22f, inputPaint) }
        submitPoint?.let { canvas.drawCircle(it.x * width, it.y * height, 22f, submitPaint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { startX = x; startY = y; return true }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.OCR_REGION -> ocrRegion = NormalizedRect(
                        min(startX, x) / width, min(startY, y) / height,
                        max(startX, x) / width, max(startY, y) / height
                    )
                    Mode.INPUT_POINT -> if (event.action == MotionEvent.ACTION_UP) {
                        inputPoint = NormalizedPoint(x / width, y / height)
                    }
                    Mode.SUBMIT_POINT -> if (event.action == MotionEvent.ACTION_UP) {
                        submitPoint = NormalizedPoint(x / width, y / height)
                    }
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
