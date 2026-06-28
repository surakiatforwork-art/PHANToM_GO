package com.phantom.dashcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * A simple footage timeline: shows the overall recorded span with filled bars where clips exist,
 * and two draggable handles to pick any sub-range. Reports the chosen [start, end] in epoch millis.
 */
class DashcamTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private enum class Handle { NONE, START, END }

    private var spanStartMs = 0L
    private var spanEndMs = 0L
    private var selectionStartMs = 0L
    private var selectionEndMs = 0L
    private val coveredIntervals = mutableListOf<LongRange>()
    private var draggingHandle = Handle.NONE

    /** Minimum selectable length so the two handles can't collapse onto each other. */
    var minSelectionMs = 1_000L

    var onSelectionChanged: ((startMs: Long, endMs: Long) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val handleRadiusPx = 11f * density
    private val trackHeightPx = 12f * density
    private val touchSlopPx = 24f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card_stroke)
    }
    private val coveredPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted)
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_red)
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_red)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    fun setData(
        spanStartMs: Long,
        spanEndMs: Long,
        intervals: List<LongRange>,
        initialStartMs: Long,
        initialEndMs: Long,
    ) {
        this.spanStartMs = spanStartMs
        this.spanEndMs = maxOf(spanEndMs, spanStartMs + 1L)
        coveredIntervals.clear()
        coveredIntervals.addAll(intervals)
        selectionStartMs = initialStartMs.coerceIn(this.spanStartMs, this.spanEndMs)
        selectionEndMs = initialEndMs.coerceIn(selectionStartMs + minSelectionMs, this.spanEndMs)
        invalidate()
        onSelectionChanged?.invoke(selectionStartMs, selectionEndMs)
    }

    fun getSelectionStartMs(): Long = selectionStartMs
    fun getSelectionEndMs(): Long = selectionEndMs

    private fun trackLeft(): Float = paddingLeft + handleRadiusPx
    private fun trackRight(): Float = width - paddingRight - handleRadiusPx
    private fun trackCenterY(): Float = height / 2f

    private fun timeToX(timeMs: Long): Float {
        val fraction = (timeMs - spanStartMs).toFloat() / (spanEndMs - spanStartMs).toFloat()
        return trackLeft() + fraction.coerceIn(0f, 1f) * (trackRight() - trackLeft())
    }

    private fun xToTime(x: Float): Long {
        val left = trackLeft()
        val right = trackRight()
        if (right <= left) return spanStartMs
        val fraction = ((x - left) / (right - left)).coerceIn(0f, 1f)
        return spanStartMs + (fraction * (spanEndMs - spanStartMs)).toLong()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerY = trackCenterY()
        val top = centerY - trackHeightPx / 2f
        val bottom = centerY + trackHeightPx / 2f
        val radius = trackHeightPx / 2f

        // Base track.
        canvas.drawRoundRect(RectF(trackLeft(), top, trackRight(), bottom), radius, radius, trackPaint)

        // Recorded coverage.
        coveredIntervals.forEach { interval ->
            val l = timeToX(interval.first)
            val r = timeToX(interval.last)
            if (r > l) canvas.drawRoundRect(RectF(l, top, r, bottom), radius, radius, coveredPaint)
        }

        // Selected band + handles.
        val startX = timeToX(selectionStartMs)
        val endX = timeToX(selectionEndMs)
        canvas.drawRoundRect(RectF(startX, top, endX, bottom), radius, radius, selectionPaint)
        drawHandle(canvas, startX, centerY)
        drawHandle(canvas, endX, centerY)
    }

    private fun drawHandle(canvas: Canvas, x: Float, centerY: Float) {
        canvas.drawCircle(x, centerY, handleRadiusPx, handleFillPaint)
        canvas.drawCircle(x, centerY, handleRadiusPx, handleStrokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingHandle = nearestHandle(event.x)
                if (draggingHandle != Handle.NONE) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    updateHandle(event.x)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingHandle != Handle.NONE) {
                    updateHandle(event.x)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingHandle = Handle.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(event)
    }

    private fun nearestHandle(x: Float): Handle {
        val dStart = abs(x - timeToX(selectionStartMs))
        val dEnd = abs(x - timeToX(selectionEndMs))
        return when {
            dStart > touchSlopPx && dEnd > touchSlopPx -> Handle.NONE
            dStart <= dEnd -> Handle.START
            else -> Handle.END
        }
    }

    private fun updateHandle(x: Float) {
        val time = xToTime(x)
        when (draggingHandle) {
            Handle.START -> selectionStartMs = time.coerceIn(spanStartMs, selectionEndMs - minSelectionMs)
            Handle.END -> selectionEndMs = time.coerceIn(selectionStartMs + minSelectionMs, spanEndMs)
            Handle.NONE -> return
        }
        invalidate()
        onSelectionChanged?.invoke(selectionStartMs, selectionEndMs)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (56f * density).toInt() + paddingTop + paddingBottom
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), height)
    }
}
