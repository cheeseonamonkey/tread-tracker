package dev.treadtracker.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import dev.treadtracker.app.R

/**
 * Lightweight bar chart for hourly / weekly / monthly step totals.
 * Replaces the original app's custom GraphView (decompiled class ni2/zt2).
 */
class GraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var values = FloatArray(0)
    private var labels: Array<String> = emptyArray()
    private var labelStride = 1
    private var highlight = -1

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.graph_track)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = dp(10f)
        textAlign = Paint.Align.CENTER
    }

    private val rect = RectF()

    private fun dp(v: Float): Float =
        v * resources.displayMetrics.density

    fun setData(
        values: FloatArray,
        labels: Array<String>,
        labelStride: Int,
        highlight: Int
    ) {
        this.values = values
        this.labels = labels
        this.labelStride = labelStride.coerceAtLeast(1)
        this.highlight = highlight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = values.size
        if (n == 0 || width == 0 || height == 0) return

        val labelH = dp(16f)
        val topPad = dp(4f)
        val graphBottom = height - labelH
        val graphTop = topPad

        var max = 0f
        for (v in values) if (v > max) max = v

        val slot = width.toFloat() / n
        val barW = (slot * 0.55f).coerceAtMost(dp(20f))
        val radius = dp(3f)

        if (max <= 0f) {
            // Empty state: a slim baseline so the chart does not look broken.
            canvas.drawRect(0f, graphBottom - dp(2f), width.toFloat(), graphBottom, emptyPaint)
        } else {
            val scale = (graphBottom - graphTop) / max
            for (i in 0 until n) {
                val v = values[i]
                if (v <= 0f) continue
                val h = (v * scale).coerceAtLeast(dp(2f))
                val left = i * slot + (slot - barW) / 2f
                rect.set(left, graphBottom - h, left + barW, graphBottom)
                barPaint.alpha = if (i == highlight) 255 else 150
                canvas.drawRoundRect(rect, radius, radius, barPaint)
            }
        }

        // X labels.
        for (i in 0 until n step labelStride) {
            if (i >= labels.size) break
            val cx = i * slot + slot / 2f
            val text = labels[i]
            val w = labelPaint.measureText(text)
            if (w < slot * labelStride * 1.1f) {
                canvas.drawText(text, cx, height - dp(4f), labelPaint)
            }
        }
    }
}
