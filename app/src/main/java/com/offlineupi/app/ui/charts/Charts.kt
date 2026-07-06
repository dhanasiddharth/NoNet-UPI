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
import android.view.View
import androidx.core.content.ContextCompat
import com.offlineupi.app.R
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
 * Multi-series line chart with a recessive grid and right-inside y-labels.
 * Series share one axis (same unit). The fragment slices arrays per range.
 */
class LineChartView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    data class Series(val values: DoubleArray, val color: Int,
                      val widthDp: Float = 2.2f, val dashed: Boolean = false,
                      val area: Boolean = false)

    var yFormatter: (Double) -> String = { "%.0f".format(it) }
    private var series: List<Series> = emptyList()
    private var xLabels: List<String> = emptyList()

    private val gridPaint = Paint().apply { color = 0xFF1B2420.toInt(); strokeWidth = dp(1f) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary); textSize = dp(9.5f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(series: List<Series>, xLabels: List<String>) {
        this.series = series.map { s -> s.copy(values = s.values.copyOf()) }
        this.xLabels = xLabels
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val valid = series.flatMap { s -> s.values.filter { it > 0 } }
        if (valid.isEmpty()) return
        var lo = valid.min(); var hi = valid.max()
        val span = (hi - lo).takeIf { it > 0 } ?: (hi * 0.1 + 1)
        lo -= span * 0.06; hi += span * 0.06

        val padB = dp(16f); val padT = dp(4f); val padR = dp(44f)
        val w = width - padR; val h = height - padB - padT
        fun y(v: Double) = padT + h * (1 - ((v - lo) / (hi - lo))).toFloat()

        for (g in 1..3) {
            val gv = lo + (hi - lo) * g / 4
            canvas.drawLine(0f, y(gv), w + dp(4f), y(gv), gridPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(yFormatter(gv), width.toFloat() - dp(2f), y(gv) + dp(3f), textPaint)
        }

        for (s in series) {
            val vals = s.values
            if (vals.size < 2) continue
            fun x(i: Int) = w * i / (vals.size - 1f)
            val path = Path(); var started = false
            for (i in vals.indices) {
                if (vals[i] <= 0) continue
                if (!started) { path.moveTo(x(i), y(vals[i])); started = true }
                else path.lineTo(x(i), y(vals[i]))
            }
            if (s.area && started) {
                val area = Path(path)
                area.lineTo(x(vals.size - 1), padT + h); area.lineTo(0f, padT + h); area.close()
                areaPaint.shader = LinearGradient(0f, padT, 0f, padT + h,
                    (s.color and 0x00FFFFFF) or 0x38000000, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawPath(area, areaPaint)
            }
            linePaint.color = s.color
            linePaint.strokeWidth = dp(s.widthDp)
            linePaint.pathEffect = if (s.dashed) DashPathEffect(floatArrayOf(dp(2.5f), dp(5f)), 0f) else null
            canvas.drawPath(path, linePaint)
            // endpoint dot on the primary (first) series
            if (s === series.first()) {
                var lastIdx = vals.size - 1
                while (lastIdx > 0 && vals[lastIdx] <= 0) lastIdx--
                dotPaint.color = s.color
                canvas.drawCircle(x(lastIdx), y(vals[lastIdx]), dp(3.4f), dotPaint)
            }
        }

        textPaint.textAlign = Paint.Align.LEFT
        if (xLabels.isNotEmpty()) canvas.drawText(xLabels.first(), 0f, height - dp(3f), textPaint)
        if (xLabels.size > 2) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(xLabels[1], w / 2, height - dp(3f), textPaint)
        }
        if (xLabels.size > 1) {
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(xLabels.last(), w, height - dp(3f), textPaint)
        }
    }
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
