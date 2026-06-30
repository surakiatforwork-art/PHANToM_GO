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
 * Zoomable + pannable footage range picker with two strips that share one "view window" (the
 * currently visible time range), so they always stay aligned:
 *
 *  - top "overview": the selection editor. Two draggable handles frame the export window. The
 *    handles grow taller the more the timeline is zoomed in. Dragging a handle to the strip edge
 *    zooms the view OUT so you can reach times outside the current window; releasing a handle zooms
 *    the view IN to frame the current selection (with padding) for finer adjustment next time.
 *  - bottom "detail": the selected export window itself, magnified to the full strip width. Drag it
 *    left/right like a film strip to move the whole export window without changing its duration.
 *
 * Reports the chosen [start, end] in epoch millis. The top strip still controls the duration; the
 * bottom strip moves that same duration across the available footage for precise placement.
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
    // Visible (zoom) window shared by both strips. Defaults to the whole span (fully zoomed out).
    private var viewStartMs = 0L
    private var viewEndMs = 0L
    private val coveredIntervals = mutableListOf<LongRange>()

    private var draggingHandle = Handle.NONE
    private var scrubbingSelection = false
    private var lastPanX = 0f

    /** Minimum selectable length so the handles can't collapse onto each other. */
    var minSelectionMs = 1_000L

    var onSelectionChanged: ((startMs: Long, endMs: Long) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val handleWidthPx = 12f * density
    private val baseHandleHeightPx = 26f * density
    private val maxHandleHeightPx = 52f * density
    private val trackHeightPx = 12f * density
    private val detailHeightPx = 24f * density
    private val stripGapPx = 30f * density
    private val touchSlopPx = 28f * density
    private val edgePullPx = 28f * density

    private val trackPaint = paint(R.color.card_stroke)
    private val coveredPaint = paint(R.color.text_muted)
    private val selectionPaint = paint(R.color.accent_red)
    private val detailCoveredPaint = paint(R.color.text_secondary)
    private val detailSelectionPaint = paint(R.color.accent_red).apply { alpha = 90 }
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
        // Start fully zoomed out (whole span visible), matching the previous look on open.
        viewStartMs = this.spanStartMs
        viewEndMs = this.spanEndMs
        invalidate()
        onSelectionChanged?.invoke(selectionStartMs, selectionEndMs)
    }

    fun getSelectionStartMs(): Long = selectionStartMs
    fun getSelectionEndMs(): Long = selectionEndMs

    private fun spanWidthMs(): Long = (spanEndMs - spanStartMs).coerceAtLeast(1L)
    private fun viewWidthMs(): Long = (viewEndMs - viewStartMs).coerceAtLeast(1L)
    private fun selectionWidthMs(): Long = (selectionEndMs - selectionStartMs).coerceAtLeast(1L)
    private fun isZoomedIn(): Boolean = viewWidthMs() < spanWidthMs()

    private fun trackLeft(): Float = paddingLeft + handleWidthPx / 2f
    private fun trackRight(): Float = width - paddingRight - handleWidthPx / 2f
    private fun overviewCenterY(): Float = paddingTop + maxHandleHeightPx / 2f
    private fun detailCenterY(): Float =
        paddingTop + maxHandleHeightPx + stripGapPx + detailHeightPx / 2f

    /** Current handle height — grows with zoom so a deep zoom gives a chunkier, easier-to-grab handle. */
    private fun handleHeightPx(): Float {
        val zoom = spanWidthMs().toFloat() / viewWidthMs().toFloat()
        val t = ((zoom - 1f) / (ZOOM_FOR_MAX_HANDLE - 1f)).coerceIn(0f, 1f)
        return baseHandleHeightPx + (maxHandleHeightPx - baseHandleHeightPx) * t
    }

    private fun mapX(timeMs: Long, fromMs: Long, toMs: Long): Float {
        val span = (toMs - fromMs).coerceAtLeast(1L)
        val fraction = ((timeMs - fromMs).toFloat() / span).coerceIn(0f, 1f)
        return trackLeft() + fraction * (trackRight() - trackLeft())
    }

    private fun viewXToTime(x: Float): Long {
        val left = trackLeft()
        val right = trackRight()
        if (right <= left) return viewStartMs
        val fraction = ((x - left) / (right - left)).coerceIn(0f, 1f)
        return viewStartMs + (fraction.toDouble() * viewWidthMs()).toLong()
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

        // Footage that falls inside the view window.
        coveredIntervals.forEach { interval ->
            val l = clampedX(interval.first)
            val r = clampedX(interval.last)
            if (r > l) canvas.drawRoundRect(RectF(l, top, r, bottom), radius, radius, coveredPaint)
        }

        val startX = clampedX(selectionStartMs)
        val endX = clampedX(selectionEndMs)
        if (endX > startX) {
            canvas.drawRoundRect(RectF(startX, top, endX, bottom), radius, radius, selectionPaint)
        }
        drawHandle(canvas, startX, cy)
        drawHandle(canvas, endX, cy)
    }

    private fun drawDetail(canvas: Canvas) {
        val cy = detailCenterY()
        val top = cy - detailHeightPx / 2f
        val bottom = cy + detailHeightPx / 2f
        val radius = detailHeightPx / 2f
        canvas.drawRoundRect(RectF(trackLeft(), top, trackRight(), bottom), radius, radius, trackPaint)
        canvas.drawRoundRect(RectF(trackLeft(), top, trackRight(), bottom), radius, radius, detailSelectionPaint)

        coveredIntervals.forEach { interval ->
            val clampedStart = maxOf(interval.first, selectionStartMs)
            val clampedEnd = minOf(interval.last, selectionEndMs)
            if (clampedEnd <= clampedStart) return@forEach
            val l = mapX(clampedStart, selectionStartMs, selectionEndMs)
            val r = mapX(clampedEnd, selectionStartMs, selectionEndMs)
            if (r > l) canvas.drawRoundRect(RectF(l, top, r, bottom), radius, radius, detailCoveredPaint)
        }
    }

    /** Selection/footage time mapped into the view, with the on-screen X clamped to the track. */
    private fun clampedX(timeMs: Long): Float {
        return mapX(timeMs.coerceIn(viewStartMs, viewEndMs), viewStartMs, viewEndMs)
    }

    private fun drawHandle(canvas: Canvas, x: Float, centerY: Float) {
        val h = handleHeightPx()
        val half = handleWidthPx / 2f
        val rect = RectF(x - half, centerY - h / 2f, x + half, centerY + h / 2f)
        val r = half
        canvas.drawRoundRect(rect, r, r, handleFillPaint)
        canvas.drawRoundRect(rect, r, r, handleStrokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isInOverviewRegion(event.y)) {
                    draggingHandle = nearestHandle(event.x)
                    if (draggingHandle != Handle.NONE) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        updateHandle(event.x)
                        return true
                    }
                } else if (isInDetailRegion(event.y) && canScrubSelection()) {
                    scrubbingSelection = true
                    lastPanX = event.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingHandle != Handle.NONE) {
                    updateHandle(event.x)
                    return true
                }
                if (scrubbingSelection) {
                    scrubSelectionBy(event.x - lastPanX)
                    lastPanX = event.x
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingHandle != Handle.NONE) {
                    // Releasing a handle frames the selection (zoom in with padding) for finer edits.
                    refitViewToSelection()
                    invalidate()
                }
                draggingHandle = Handle.NONE
                scrubbingSelection = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(event)
    }

    private fun isInOverviewRegion(y: Float): Boolean {
        val mid = (overviewCenterY() + maxHandleHeightPx / 2f + detailCenterY() - detailHeightPx / 2f) / 2f
        return y <= mid
    }

    private fun isInDetailRegion(y: Float): Boolean = !isInOverviewRegion(y)

    private fun nearestHandle(x: Float): Handle {
        val dStart = abs(x - clampedX(selectionStartMs))
        val dEnd = abs(x - clampedX(selectionEndMs))
        return when {
            dStart > touchSlopPx && dEnd > touchSlopPx -> Handle.NONE
            dStart <= dEnd -> Handle.START
            else -> Handle.END
        }
    }

    private fun updateHandle(x: Float) {
        // Pulling a handle against an edge zooms the view OUT in that direction so you can extend the
        // selection past what is currently visible.
        if (draggingHandle == Handle.START && x <= trackLeft() + edgePullPx && viewStartMs > spanStartMs) {
            viewStartMs = (viewStartMs - (viewWidthMs() * EDGE_ZOOM_STEP).toLong()).coerceAtLeast(spanStartMs)
        } else if (draggingHandle == Handle.END && x >= trackRight() - edgePullPx && viewEndMs < spanEndMs) {
            viewEndMs = (viewEndMs + (viewWidthMs() * EDGE_ZOOM_STEP).toLong()).coerceAtMost(spanEndMs)
        }

        val time = viewXToTime(x)
        when (draggingHandle) {
            Handle.START -> selectionStartMs = time.coerceIn(spanStartMs, selectionEndMs - minSelectionMs)
            Handle.END -> selectionEndMs = time.coerceIn(selectionStartMs + minSelectionMs, spanEndMs)
            Handle.NONE -> return
        }
        invalidate()
        onSelectionChanged?.invoke(selectionStartMs, selectionEndMs)
    }

    private fun canScrubSelection(): Boolean = selectionWidthMs() < spanWidthMs()

    private fun scrubSelectionBy(dxPx: Float) {
        val trackWidth = (trackRight() - trackLeft())
        if (trackWidth <= 0f) return
        val duration = selectionWidthMs()
        if (duration >= spanWidthMs()) return
        val msPerPx = duration.toDouble() / trackWidth
        val dtMs = (dxPx * msPerPx).toLong()
        if (dtMs == 0L) return

        // Drag right like a film strip -> reveal earlier footage. Drag left -> move later.
        val maxStart = (spanEndMs - duration).coerceAtLeast(spanStartMs)
        val nextStart = (selectionStartMs - dtMs).coerceIn(spanStartMs, maxStart)
        if (nextStart == selectionStartMs) return

        selectionStartMs = nextStart
        selectionEndMs = nextStart + duration
        keepSelectionVisibleInView()
        invalidate()
        onSelectionChanged?.invoke(selectionStartMs, selectionEndMs)
    }

    private fun keepSelectionVisibleInView() {
        if (selectionStartMs >= viewStartMs && selectionEndMs <= viewEndMs) return

        val width = viewWidthMs().coerceAtMost(spanWidthMs())
        if (selectionWidthMs() >= width) {
            refitViewToSelection()
            return
        }

        var vs = viewStartMs
        var ve = viewEndMs
        if (selectionStartMs < vs) {
            vs = selectionStartMs
            ve = vs + width
        } else if (selectionEndMs > ve) {
            ve = selectionEndMs
            vs = ve - width
        }
        if (vs < spanStartMs) {
            vs = spanStartMs
            ve = (vs + width).coerceAtMost(spanEndMs)
        }
        if (ve > spanEndMs) {
            ve = spanEndMs
            vs = (ve - width).coerceAtLeast(spanStartMs)
        }
        viewStartMs = vs
        viewEndMs = ve
    }

    private fun refitViewToSelection() {
        val selWidth = (selectionEndMs - selectionStartMs).coerceAtLeast(1L)
        val pad = (selWidth * SELECTION_VIEW_PADDING).toLong()
        var vs = selectionStartMs - pad
        var ve = selectionEndMs + pad
        // Never zoom in past the minimum readable window.
        if (ve - vs < MIN_VIEW_MS) {
            val extra = (MIN_VIEW_MS - (ve - vs)) / 2L
            vs -= extra
            ve += extra
        }
        // Clamp to the recorded span without shrinking below the selection.
        if (vs < spanStartMs) vs = spanStartMs
        if (ve > spanEndMs) ve = spanEndMs
        if (ve <= vs) {
            vs = spanStartMs
            ve = spanEndMs
        }
        viewStartMs = vs
        viewEndMs = ve
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (
            paddingTop + maxHandleHeightPx + stripGapPx + detailHeightPx + paddingBottom
            ).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), height)
    }

    companion object {
        private const val ZOOM_FOR_MAX_HANDLE = 8f
        private const val EDGE_ZOOM_STEP = 0.06
        private const val SELECTION_VIEW_PADDING = 0.35
        private const val MIN_VIEW_MS = 2_000L
    }
}
