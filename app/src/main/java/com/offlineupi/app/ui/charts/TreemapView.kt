package com.offlineupi.app.ui.charts

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.offlineupi.app.R
import kotlin.math.max
import kotlin.math.min

/**
 * Squarified treemap with drill-down zoom. Tap a group (or pinch out over it)
 * to zoom in; pinch in or [up] to zoom back out. Rect area is proportional to
 * INR value so buckets share one scale; labels show native amounts.
 */
class TreemapView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    data class Node(
        val id: String,             // "bucket:India" | "sector:IT" | isin
        val label: String,
        val amount: String,         // preformatted, native currency
        val value: Double,          // INR — the area unit
        val color: Int,
        val children: List<Node> = emptyList(),
    )

    /** Fires on drill/up with the path from root ([] = top level). */
    var onLevelChange: (path: List<Node>) -> Unit = {}
    /** Fires when a leaf (a holding) is tapped. */
    var onLeafTap: (Node) -> Unit = {}

    private var roots: List<Node> = emptyList()
    private val path = mutableListOf<Node>()          // drill stack
    private var nodes: List<Node> = emptyList()       // current level
    private var rects: List<RectF> = emptyList()

    // zoom-in animation: new level scales up from the tapped rect
    private var animFrom: RectF? = null
    private var animT = 1f
    private var animator: ValueAnimator? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; isFakeBoldText = true
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
    }
    private val bg = ContextCompat.getColor(context, R.color.bg_dark)

    fun set(roots: List<Node>) {
        this.roots = roots
        path.clear()
        enterLevel(roots, null)
    }

    fun currentPath(): List<Node> = path.toList()

    fun drillInto(node: Node) {
        if (node.children.isEmpty()) { onLeafTap(node); return }
        val from = nodes.indexOfFirst { it.id == node.id }.let { rects.getOrNull(it) }
        path.add(node)
        enterLevel(node.children, from)
    }

    /** @return false when already at the top (caller should finish/ignore). */
    fun up(): Boolean {
        if (path.isEmpty()) return false
        path.removeAt(path.size - 1)
        enterLevel(path.lastOrNull()?.children ?: roots, null)
        return true
    }

    private fun enterLevel(level: List<Node>, from: RectF?) {
        nodes = level.sortedByDescending { it.value }
        layoutRects()
        onLevelChange(path.toList())
        animator?.cancel()
        if (from != null && width > 0) {
            animFrom = RectF(from)
            animT = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 240
                interpolator = DecelerateInterpolator()
                addUpdateListener { animT = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            animFrom = null; animT = 1f
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutRects()
    }

    private fun layoutRects() {
        if (width == 0 || height == 0 || nodes.isEmpty()) { rects = emptyList(); return }
        rects = squarify(nodes.map { it.value }, RectF(0f, 0f, width.toFloat(), height.toFloat()))
    }

    /** Classic squarify: greedy rows that minimise the worst aspect ratio. */
    private fun squarify(values: List<Double>, bounds: RectF): List<RectF> {
        val out = MutableList(values.size) { RectF() }
        val total = values.sum().takeIf { it > 0 } ?: return out
        val scaleAll = bounds.width() * bounds.height() / total
        val rect = RectF(bounds)
        var start = 0
        while (start < values.size) {
            val short = min(rect.width(), rect.height()).coerceAtLeast(1f)
            var end = start
            var rowSum = 0.0
            var bestWorst = Double.MAX_VALUE
            while (end < values.size) {
                val newSum = rowSum + values[end]
                val thick = newSum * scaleAll / short
                var worst = 1.0
                for (j in start..end) {
                    val len = values[j] * scaleAll / thick
                    worst = max(worst, max(thick / len, len / thick))
                }
                if (end == start || worst <= bestWorst) { bestWorst = worst; rowSum = newSum; end++ }
                else break
            }
            val thick = (rowSum * scaleAll / short).toFloat()
            var offset = 0f
            val wide = rect.width() >= rect.height()
            for (j in start until end) {
                val len = (values[j] * scaleAll / thick).toFloat()
                out[j] = if (wide)
                    RectF(rect.left, rect.top + offset, rect.left + thick, rect.top + offset + len)
                else
                    RectF(rect.left + offset, rect.top, rect.left + offset + len, rect.top + thick)
                offset += len
            }
            if (wide) rect.left += thick else rect.top += thick
            start = end
        }
        return out
    }

    override fun onDraw(canvas: Canvas) {
        if (nodes.isEmpty()) return
        val save = canvas.save()
        animFrom?.let { f ->
            if (animT < 1f) {
                val t = animT
                val l = f.left * (1 - t); val tp = f.top * (1 - t)
                val sx = (f.width() / width) * (1 - t) + t
                val sy = (f.height() / height) * (1 - t) + t
                canvas.translate(l, tp)
                canvas.scale(sx, sy)
            }
        }
        val total = nodes.sumOf { it.value }.takeIf { it > 0 } ?: 1.0
        val gap = dp(1f)
        for (i in nodes.indices) {
            val r = rects.getOrNull(i) ?: continue
            val n = nodes[i]
            fill.color = n.color
            canvas.drawRoundRect(
                r.left + gap, r.top + gap, r.right - gap, r.bottom - gap, dp(3f), dp(3f), fill
            )
            drawLabels(canvas, r, n, total)
        }
        canvas.restoreToCount(save)
    }

    private fun drawLabels(canvas: Canvas, r: RectF, n: Node, total: Double) {
        val w = r.width(); val h = r.height()
        val pctTxt = "%.1f%%".format(n.value / total * 100)
        when {
            w > dp(96f) && h > dp(58f) -> {
                labelPaint.textSize = dp(12.5f)
                subPaint.textSize = dp(10.5f)
                canvas.drawText(ellipsize(n.label, labelPaint, w - dp(16f)), r.left + dp(8f), r.top + dp(18f), labelPaint)
                canvas.drawText(pctTxt, r.left + dp(8f), r.top + dp(33f), subPaint)
                canvas.drawText(n.amount, r.left + dp(8f), r.bottom - dp(8f), subPaint)
            }
            w > dp(52f) && h > dp(30f) -> {
                labelPaint.textSize = dp(10.5f)
                subPaint.textSize = dp(9.5f)
                canvas.drawText(ellipsize(n.label, labelPaint, w - dp(10f)), r.left + dp(5f), r.top + dp(14f), labelPaint)
                canvas.drawText(pctTxt, r.left + dp(5f), r.top + dp(26f), subPaint)
            }
            w > dp(30f) && h > dp(16f) -> {
                labelPaint.textSize = dp(9f)
                canvas.drawText(ellipsize(n.label, labelPaint, w - dp(6f)), r.left + dp(3f), r.top + dp(11f), labelPaint)
            }
        }
    }

    private fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var cut = s.length
        while (cut > 1 && p.measureText(s.substring(0, cut) + "…") > maxW) cut--
        return s.substring(0, cut) + "…"
    }

    // ---- gestures: tap drills, pinch out drills, pinch in goes up ----
    private var scaling = false

    private val tapDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                nodeAt(e.x, e.y)?.let { drillInto(it) }
                return true
            }
        })

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var focalX = 0f; private var focalY = 0f
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                scaling = true; focalX = d.focusX; focalY = d.focusY
                return true
            }
            override fun onScaleEnd(d: ScaleGestureDetector) {
                scaling = false
                if (d.scaleFactor > 1.15f) nodeAt(focalX, focalY)
                    ?.takeIf { it.children.isNotEmpty() }?.let { drillInto(it) }
                else if (d.scaleFactor < 0.87f) up()
            }
        })

    private fun nodeAt(x: Float, y: Float): Node? {
        for (i in rects.indices) if (rects[i].contains(x, y)) return nodes[i]
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaling) tapDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    companion object {
        /** Shade a base color toward the surface for depth-ranked children. */
        fun shade(base: Int, rank: Int, count: Int): Int {
            if (count <= 1) return base
            val f = 0.38f * rank / (count - 1)      // 0 → 38% toward dark surface
            val surface = 0xFF101713.toInt()
            fun ch(shift: Int) = (((base shr shift and 0xFF) * (1 - f)) +
                ((surface shr shift and 0xFF) * f)).toInt()
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
