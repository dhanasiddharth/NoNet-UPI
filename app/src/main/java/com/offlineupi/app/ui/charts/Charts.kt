package com.offlineupi.app.ui.charts

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.offlineupi.app.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

private fun View.dp(v: Float) = v * resources.displayMetrics.density

/** Tiny trend line — no axes, no labels. */
class SparkView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.6f)
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private var data: DoubleArray = DoubleArray(0)

    fun set(values: DoubleArray, color: Int) {
        data = values.filter { it > 0 }.toDoubleArray()
        paint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (data.size < 2) return
        val lo = data.min(); val hi = data.max()
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val p = Path()
        for (i in data.indices) {
            val x = width * i / (data.size - 1f)
            val y = (height - dp(2f)) - ((data[i] - lo) / span * (height - dp(4f))).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        canvas.drawPath(p, paint)
    }
}

/**
 * Multi-series line chart, interactive: callers pass FULL aligned series plus
 * a window size (from the range chips). Horizontal drag pans the window
 * through history; a tap (or drag when fully zoomed out) sets a crosshair
 * whose label shows date + value at that point. With [set] rebase=true the
 * series are cumulative-% levels re-baselined to the visible window's start,
 * so panning a performance chart re-anchors 0% correctly.
 */
class LineChartView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    data class Series(val values: DoubleArray, val color: Int,
                      val widthDp: Float = 2.2f, val dashed: Boolean = false,
                      val area: Boolean = false)

    /** A marker on series[0] — e.g. a trade. [label] (a short value) is drawn
     *  only when few markers are in view, so a dense window stays readable. */
    data class Dot(val idx: Int, val label: String? = null, val up: Boolean = true)

    var yFormatter: (Double) -> String = { "%.0f".format(it) }
    /** false (default): values ≤ 0 are "no data" gaps. true: only NaN is a gap. */
    var allowNegative = false
    /** Crosshair label: absolute index + epoch day + displayed series[0] value. */
    var scrubFormatter: ((idx: Int, day: Long, value: Double) -> String)? = null
    /** Fires as the user pans; screens update their stats to the visible window. */
    var onViewportChange: ((startIdx: Int, endIdx: Int) -> Unit)? = null
    /** Overrides x-axis labels; the raw longs in [set]'s dates are passed through
     *  (epoch days by default, epoch seconds for intraday). */
    var xLabelFormatter: ((Long) -> String)? = null

    private var series: List<Series> = emptyList()
    private var dates: LongArray = LongArray(0)
    private var dots: List<Dot> = emptyList()
    private var rebase = false
    private var n = 0
    private var winSize = 0
    private var winEnd = 0
    private var scrubIdx = -1
    private var panAccum = 0f

    private val gridPaint = Paint().apply { color = 0xFF1B2420.toInt(); strokeWidth = dp(1f) }
    private val zeroPaint = Paint().apply { color = 0xFF33403A.toInt(); strokeWidth = dp(1f) }
    private val hairPaint = Paint().apply { color = 0xFF4A5A52.toInt(); strokeWidth = dp(1.1f) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary); textSize = dp(9.5f)
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = dp(11f); isFakeBoldText = true
    }
    private val markerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(9.5f); isFakeBoldText = true
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF5192420.toInt() }
    private val labelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f)
        color = ContextCompat.getColor(context, R.color.card_stroke)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * @param windowSize points initially visible (≤1 or >n → all)
     * @param rebase values are cumulative %; re-anchor each series to the window start
     *
     * Same calendar + same window size (e.g. flipping Performance ↔ Value, or
     * toggling an overlay) keeps the panned window and crosshair in place;
     * a new range or new data resets to the most recent window.
     */
    fun set(series: List<Series>, dates: LongArray = LongArray(0),
            dots: List<Dot> = emptyList(), windowSize: Int = 0, rebase: Boolean = false) {
        val newN = series.firstOrNull()?.values?.size ?: 0
        val newWin = if (windowSize <= 1 || windowSize > newN) newN else windowSize
        val keepViewport = newN == n && newWin == winSize && winEnd in newWin..newN
        this.series = series.map { s -> s.copy(values = s.values.copyOf()) }
        n = newN
        this.dates = dates
        this.dots = dots
        this.rebase = rebase
        winSize = newWin
        if (!keepViewport) {
            winEnd = n
            scrubIdx = -1
            panAccum = 0f
        }
        invalidate()
    }

    private fun ok(v: Double) = if (allowNegative) !v.isNaN() else !v.isNaN() && v > 0

    /** Currently visible [start, end] absolute indices. */
    fun viewport(): Pair<Int, Int> =
        if (n == 0) 0 to 0 else (winEnd - winSize) to (winEnd - 1)

    private val winStart get() = winEnd - winSize
    private fun plotW() = width - dp(44f)
    private fun x(k: Int) = plotW() * (k - winStart) / (winSize - 1f)

    private fun baseline(si: Int): Double {
        val vals = series[si].values
        for (k in winStart until winEnd) if (ok(vals[k])) return vals[k]
        return Double.NaN
    }

    /** Displayed value: raw, or window-rebased cumulative %. */
    private fun disp(si: Int, k: Int, base: Double): Double {
        val v = series[si].values[k]
        if (!ok(v)) return Double.NaN
        if (!rebase) return v
        if (base.isNaN()) return Double.NaN
        return ((1 + v / 100) / (1 + base / 100) - 1) * 100
    }

    override fun onDraw(canvas: Canvas) {
        if (n < 2 || winSize < 2) return
        val bases = series.indices.map { baseline(it) }

        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (si in series.indices) for (k in winStart until winEnd) {
            val v = disp(si, k, bases[si])
            if (!v.isNaN()) { if (v < lo) lo = v; if (v > hi) hi = v }
        }
        if (lo > hi) return
        val span = (hi - lo).takeIf { it > 0 } ?: (kotlin.math.abs(hi) * 0.1 + 1)
        lo -= span * 0.06; hi += span * 0.06

        val padB = dp(16f); val padT = dp(4f)
        val w = plotW(); val h = height - padB - padT
        fun y(v: Double) = padT + h * (1 - ((v - lo) / (hi - lo))).toFloat()

        for (g in 1..3) {
            val gv = lo + (hi - lo) * g / 4
            canvas.drawLine(0f, y(gv), w + dp(4f), y(gv), gridPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(yFormatter(gv), width.toFloat() - dp(2f), y(gv) + dp(3f), textPaint)
        }
        if (allowNegative && lo < 0 && hi > 0) {
            canvas.drawLine(0f, y(0.0), w + dp(4f), y(0.0), zeroPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(yFormatter(0.0), width.toFloat() - dp(2f), y(0.0) + dp(3f), textPaint)
        }

        for (si in series.indices) {
            val s = series[si]
            val path = Path(); var started = false
            var lastOk = -1
            for (k in winStart until winEnd) {
                val v = disp(si, k, bases[si])
                if (v.isNaN()) continue
                if (!started) { path.moveTo(x(k), y(v)); started = true } else path.lineTo(x(k), y(v))
                lastOk = k
            }
            if (!started) continue
            if (s.area) {
                val area = Path(path)
                area.lineTo(x(lastOk), padT + h); area.lineTo(0f, padT + h); area.close()
                areaPaint.shader = LinearGradient(0f, padT, 0f, padT + h,
                    (s.color and 0x00FFFFFF) or 0x38000000, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawPath(area, areaPaint)
            }
            linePaint.color = s.color
            linePaint.strokeWidth = dp(s.widthDp)
            linePaint.pathEffect = if (s.dashed) DashPathEffect(floatArrayOf(dp(2.5f), dp(5f)), 0f) else null
            canvas.drawPath(path, linePaint)
            if (si == 0 && lastOk >= 0 && scrubIdx < 0) {
                dotPaint.color = s.color
                canvas.drawCircle(x(lastOk), y(disp(0, lastOk, bases[0])), dp(3.4f), dotPaint)
            }
        }

        // trade markers ride series[0]; label them only when few are in view
        val inWindow = dots.filter { it.idx in winStart until winEnd && !disp(0, it.idx, bases[0]).isNaN() }
        val showLabels = inWindow.count { it.label != null } in 1..8
        for (d in inWindow) {
            val v = disp(0, d.idx, bases[0])
            val cx = x(d.idx); val cy = y(v)
            dotPaint.color = 0xFF0A0F0D.toInt()
            canvas.drawCircle(cx, cy, dp(4.2f), dotPaint)
            dotPaint.color = if (d.up) 0xFF34D88F.toInt() else 0xFFF26A5B.toInt()
            canvas.drawCircle(cx, cy, dp(2.8f), dotPaint)
            if (showLabels && d.label != null) {
                markerText.color = dotPaint.color
                val tw = markerText.measureText(d.label)
                val tx = (cx - tw / 2).coerceIn(0f, plotW() - tw)
                canvas.drawText(d.label, tx, (cy - dp(7f)).coerceAtLeast(dp(9f)), markerText)
            }
        }

        // x labels from dates
        if (dates.size >= n) {
            val fmt = DateTimeFormatter.ofPattern(if (winSize > 400) "MMM yy" else "d MMM")
            fun lbl(k: Int) = xLabelFormatter?.invoke(dates[k])
                ?: LocalDate.ofEpochDay(dates[k]).format(fmt)
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(lbl(winStart), 0f, height - dp(3f), textPaint)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(lbl(winStart + winSize / 2), w / 2, height - dp(3f), textPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(lbl(winEnd - 1), w, height - dp(3f), textPaint)
        }

        // crosshair + readout
        if (scrubIdx in winStart until winEnd) {
            val v = disp(0, scrubIdx, bases[0])
            if (!v.isNaN()) {
                val cx = x(scrubIdx)
                canvas.drawLine(cx, padT, cx, padT + h, hairPaint)
                dotPaint.color = 0xFF0A0F0D.toInt()
                canvas.drawCircle(cx, y(v), dp(5f), dotPaint)
                dotPaint.color = series[0].color
                canvas.drawCircle(cx, y(v), dp(3.2f), dotPaint)
                val day = if (dates.size >= n) dates[scrubIdx] else 0L
                val txt = scrubFormatter?.invoke(scrubIdx, day, v) ?: yFormatter(v)
                val tw = labelText.measureText(txt)
                val bw = tw + dp(16f); val bh = dp(22f)
                val bx = (cx - bw / 2).coerceIn(0f, (width - bw))
                canvas.drawRoundRect(bx, 0f, bx + bw, bh, dp(6f), dp(6f), labelBg)
                canvas.drawRoundRect(bx, 0f, bx + bw, bh, dp(6f), dp(6f), labelStroke)
                labelText.textAlign = Paint.Align.CENTER
                canvas.drawText(txt, bx + bw / 2, bh - dp(7f), labelText)
            }
        }
    }

    // ---- gestures: tap = crosshair; drag pans the window (or scrubs when zoomed out) ----
    private fun idxAt(px: Float): Int {
        val raw = winStart + (px / plotW() * (winSize - 1)).toInt()
        var k = raw.coerceIn(winStart, winEnd - 1)
        if (series.isNotEmpty()) {
            var f = k; var b = k
            while (f < winEnd || b >= winStart) {
                if (f < winEnd && ok(series[0].values[f])) { k = f; break }
                if (b >= winStart && ok(series[0].values[b])) { k = b; break }
                f++; b--
            }
        }
        return k
    }

    private val gestures = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val idx = idxAt(e.x)
                scrubIdx = if (idx == scrubIdx) -1 else idx
                invalidate()
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent,
                                  dx: Float, dy: Float): Boolean {
                if (kotlin.math.abs(dx) < kotlin.math.abs(dy) && scrubIdx < 0) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                if (winSize >= n || scrubIdx >= 0) {
                    // fully zoomed out (or already scrubbing): drag moves the crosshair
                    scrubIdx = idxAt(e2.x)
                    invalidate()
                    return true
                }
                panAccum += dx
                val pxPerPt = plotW() / (winSize - 1f)
                val shift = (panAccum / pxPerPt).toInt()
                if (shift != 0) {
                    panAccum -= shift * pxPerPt
                    val moved = (winEnd + shift).coerceIn(winSize, n)
                    if (moved != winEnd) {
                        winEnd = moved
                        onViewportChange?.invoke(winStart, winEnd - 1)
                    }
                    invalidate()
                }
                return true
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/** You / index / inflation on one shared XIRR axis — one row per bucket. */
class DotPlotView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    data class Row(val label: String, val sub: String,
                   val you: Double?, val index: Double?, val inflation: Double?,
                   val color: Int)

    private var rows: List<Row> = emptyList()
    private val rowH = dp(46f)

    private val axisPaint = Paint().apply { color = 0xFF1B2420.toInt(); strokeWidth = dp(2f) }
    private val zeroPaint = Paint().apply { color = 0xFF33403A.toInt(); strokeWidth = dp(1f) }
    private val sepPaint = Paint().apply { color = 0xFF24302A.toInt(); strokeWidth = dp(1f) }
    private val inflPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_amber); strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val idxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2f); color = 0xFF7A8A82.toInt()
    }
    private val idxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card_bg)
    }
    private val youPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = dp(12.5f); isFakeBoldText = true
    }
    private val subLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary); textSize = dp(9.5f)
    }
    private val valLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(12.5f); isFakeBoldText = true; textAlign = Paint.Align.RIGHT
    }

    fun set(rows: List<Row>) {
        this.rows = rows
        requestLayout(); invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, (rowH * rows.size).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (rows.isEmpty()) return
        val all = rows.flatMap { listOfNotNull(it.you, it.index, it.inflation) }
        if (all.isEmpty()) return
        var loV = min(all.min(), 0.0); var hiV = all.max()
        val span = (hiV - loV).takeIf { it > 0 } ?: 1.0
        loV -= span * 0.06; hiV += span * 0.10

        val left = dp(74f); val right = width - dp(64f)
        fun x(v: Double) = left + ((v - loV) / (hiV - loV) * (right - left)).toFloat()

        rows.forEachIndexed { i, r ->
            val cy = rowH * i + rowH / 2
            canvas.drawText(r.label, 0f, cy - dp(2f), label)
            canvas.drawText(r.sub, 0f, cy + dp(10f), subLabel)
            canvas.drawLine(left, cy, right, cy, axisPaint)
            if (loV < 0) canvas.drawLine(x(0.0), cy - dp(7f), x(0.0), cy + dp(7f), zeroPaint)
            r.inflation?.let { canvas.drawLine(x(it), cy - dp(6.5f), x(it), cy + dp(6.5f), inflPaint) }
            r.index?.let {
                canvas.drawCircle(x(it), cy, dp(4.5f), idxFill)
                canvas.drawCircle(x(it), cy, dp(4.5f), idxPaint)
            }
            r.you?.let {
                youPaint.color = r.color
                canvas.drawCircle(x(it), cy, dp(5.5f), youPaint)
            }
            r.you?.let {
                valLabel.color = ContextCompat.getColor(context,
                    if (it >= (r.index ?: 0.0)) R.color.accent_green else R.color.accent_red)
                canvas.drawText(
                    (if (it >= 0) "+" else "") + "%.1f%%".format(it * 100),
                    width.toFloat(), cy + dp(4f), valLabel
                )
            }
            if (i < rows.size - 1)
                canvas.drawLine(0f, rowH * (i + 1), width.toFloat(), rowH * (i + 1), sepPaint)
        }
    }
}
