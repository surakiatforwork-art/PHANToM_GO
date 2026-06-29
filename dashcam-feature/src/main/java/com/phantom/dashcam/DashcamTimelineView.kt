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
 * Footage range picker with two strips:
 *  - top "overview": the whole recorded span with filled bars where clips exist and two draggable
 *    handles that frame the export window (handles sit at the extremes by default = full span).
 *  - bottom "detail": the footage inside the frame, zoomed to fill the width — so narrowing the
 *    handles zooms in and widening them zooms back out (full span = fully zoomed out).
 *
 * Reports the chosen [start, end] in epoch millis.
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

    /** Minimum selectable length so the handles can't collapse onto each other. */
    var minSelectionMs = 1_000L

    var onSelectionChanged: ((startMs: Long, endMs: Long) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val handleRadiusPx = 11f * density
    private val trackHeightPx = 12f * density
    private val detailHeightPx = 16f * density
    private val stripGapPx = 26f * density
    private val touchSlopPx = 26f * density

    private val trackPaint = paint(R.color.card_stroke)
    private val coveredPaint = paint(R.color.text_muted)
    private val selectionPaint = paint(R.color.accent_red)
    private val detailCoveredPaint = paint(R.color.text_secondary)
    private val handleFillPaint = paint(R.color.text_primary)
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_red)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private fun paint(colorRes: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, colorRes)
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
    private fun overviewCenterY(): Float = paddingTop + handleRadiusPx
    private fun detailCenterY(): Float = overviewCenterY() + handleRadiusPx + stripGapPx + detailHeightPx / 2f

    private fun mapX(timeMs: Long, fromMs: Long, toMs: Long): Float {
        val span = (toMs - fromMs).coerceAtLeast(1L)
        val fraction = ((timeMs - fromMs).toFloat() / span).coerceIn(0f, 1f)
        return trackLeft() + fraction * (trackRight() - trackLeft())
    }

    private fun overviewXToTime(x: Float): Long {
        val left = trackLeft()
        val right = trackRight()
        if (right <= left) return spanStartMs
        val fraction = ((x - left) / (right - left)).coerceIn(0f, 1f)
        return spanStartMs + (fraction * (spanEndMs - spanStartMs)).toLong()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawOverview(canvas)
        drawDetail(canvas)
    }

    private fun drawOverview(canvas: Canvas) {
        val cy = overviewCenterY()
        val top = cy - trackHeightPx / 2f
        val bottom = cy + trackHeightPx / 2f
        val radius = trackHeightPx / 2f
        canvas.drawRoundRect(RectF(trackLeft(), top, trackRight(), bottom), radius, radius, trackPaint)
        coveredIntervals.forEach { interval ->
            val l = mapX(interval.first, spanStartMs, spanEndMs)
            val r = mapX(interval.last, spanStartMs, spanEndMs)
            if (r > l) canvas.drawRoundRect(RectF(l, top, r, bottom), radius, radius, coveredPaint)
        }
        val startX = mapX(selectionStartMs, spanStartMs, spanEndMs)
        val endX = mapX(selectionEndMs, spanStartMs, spanEndMs)
        canvas.drawRoundRect(RectF(startX, top, endX, bottom), radius, radius, selectionPaint)
        drawHandle(canvas, startX, cy)
        drawHandle(canvas, endX, cy)
    }

    private fun drawDetail(canvas: Canvas) {
        val cy = detailCenterY()
        val top = cy - detailHeightPx / 2f
        val bottom = cy + detailHeightPx / 2f
        val radius = detailHeightPx / 2f
        canvas.drawRoundRect(RectF(trackLeft(), top, trackRight(), bottom), radius, radius, trackPaint)
        // Footage inside the frame, clamped to the selection and stretched across the width.
        coveredIntervals.forEach { interval ->
            val clampedStart = maxOf(interval.first, selectionStartMs)
            val clampedEnd = minOf(interval.last, selectionEndMs)
            if (clampedEnd <= clampedStart) return@forEach
            val l = mapX(clampedStart, selectionStartMs, selectionEndMs)
            val r = mapX(clampedEnd, selectionStartMs, selectionEndMs)
            if (r > l) canvas.drawRoundRect(RectF(l, top, r, bottom), radius, radius, detailCoveredPaint)
        }
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
        val dStart = abs(x - mapX(selectionStartMs, spanStartMs, spanEndMs))
        val dEnd = abs(x - mapX(selectionEndMs, spanStartMs, spanEndMs))
        return when {
            dStart > touchSlopPx && dEnd > touchSlopPx -> Handle.NONE
            dStart <= dEnd -> Handle.START
            else -> Handle.END
        }
    }

    private fun updateHandle(x: Float) {
        val time = overviewXToTime(x)
        when (draggingHandle) {
            Handle.START -> selectionStartMs = time.coerceIn(spanStartMs, selectionEndMs - minSelectionMs)
            Handle.END -> selectionEndMs = time.coerceIn(selectionStartMs + minSelectionMs, spanEndMs)
            Handle.NONE -> return
        }
        invalidate()
        onSelectionChanged?.invoke(selectionStartMs, selectionEndMs)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (paddingTop + handleRadiusPx * 2 + stripGapPx + detailHeightPx + paddingBottom).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), height)
    }
}
