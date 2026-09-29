package com.kotbaton.cocbot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/** Что именно выбирают на скриншоте. */
enum class PickMode { POINTS, PAIRS, RECT, SINGLE }

/**
 * Скриншот игры во весь экран: тап ставит точку, перетаскивание двигает её,
 * а под пальцем показывается увеличенный кусок картинки для точного попадания.
 */
class ScreenshotPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var mode: PickMode = PickMode.POINTS
    var maxPoints: Int = 64
    var onChanged: ((List<PointN>) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private val points = ArrayList<PointN>()
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f
    private var dragIndex = -1
    private var fingerX = 0f
    private var fingerY = 0f
    private var dragging = false

    private val dst = RectF()
    private val src = Rect()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.MAGENTA
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 214, 0)
        style = Paint.Style.FILL
    }
    private val dotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }
    private val loupeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        strokeWidth = 3f
    }

    fun setBitmap(b: Bitmap) {
        bitmap = b
        requestLayout()
        invalidate()
    }

    fun setPoints(list: List<PointN>) {
        points.clear()
        points.addAll(list.take(maxPoints))
        notifyChanged()
        invalidate()
    }

    fun getPoints(): List<PointN> = points.toList()

    fun undo() {
        if (points.isNotEmpty()) {
            points.removeAt(points.size - 1)
            notifyChanged()
            invalidate()
        }
    }

    fun clearPoints() {
        points.clear()
        notifyChanged()
        invalidate()
    }

    private fun notifyChanged() = onChanged?.invoke(points.toList())

    private fun layoutBitmap() {
        val b = bitmap ?: return
        scale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        offX = (width - b.width * scale) / 2f
        offY = (height - b.height * scale) / 2f
    }

    private fun toNorm(x: Float, y: Float): PointN {
        val b = bitmap ?: return PointN(0f, 0f)
        val nx = ((x - offX) / (b.width * scale)).coerceIn(0f, 1f)
        val ny = ((y - offY) / (b.height * scale)).coerceIn(0f, 1f)
        return PointN(nx, ny)
    }

    private fun toViewX(p: PointN): Float = offX + p.x * (bitmap?.width ?: 1) * scale
    private fun toViewY(p: PointN): Float = offY + p.y * (bitmap?.height ?: 1) * scale

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val b = bitmap ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingerX = event.x
                fingerY = event.y
                dragging = true
                val near = points.indexOfFirst {
                    hypot(toViewX(it) - event.x, toViewY(it) - event.y) < 44f
                }
                if (near >= 0) {
                    dragIndex = near
                } else {
                    val limit = if (mode == PickMode.SINGLE) 1 else if (mode == PickMode.RECT) 2 else maxPoints
                    if (points.size >= limit) {
                        if (mode == PickMode.SINGLE || mode == PickMode.RECT) points.clear() else return true
                    }
                    points.add(toNorm(event.x, event.y))
                    dragIndex = points.size - 1
                    notifyChanged()
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                fingerX = event.x
                fingerY = event.y
                if (dragIndex in points.indices) {
                    points[dragIndex] = toNorm(event.x, event.y)
                    notifyChanged()
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                dragIndex = -1
                invalidate()
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap ?: return
        layoutBitmap()
        dst.set(offX, offY, offX + b.width * scale, offY + b.height * scale)
        canvas.drawBitmap(b, null, dst, bmpPaint)

        when (mode) {
            PickMode.PAIRS -> {
                for (i in 0 until points.size - 1 step 2) {
                    canvas.drawLine(
                        toViewX(points[i]), toViewY(points[i]),
                        toViewX(points[i + 1]), toViewY(points[i + 1]), linePaint
                    )
                }
            }
            PickMode.RECT -> {
                if (points.size == 2) {
                    val x1 = toViewX(points[0])
                    val y1 = toViewY(points[0])
                    val x2 = toViewX(points[1])
                    val y2 = toViewY(points[1])
                    canvas.drawRect(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2), rectPaint)
                }
            }
            else -> Unit
        }

        points.forEachIndexed { i, p ->
            val x = toViewX(p)
            val y = toViewY(p)
            canvas.drawCircle(x, y, 14f, dotFill)
            canvas.drawCircle(x, y, 14f, dotStroke)
            val label = when (mode) {
                PickMode.PAIRS -> "${i / 2 + 1}${if (i % 2 == 0) "a" else "b"}"
                else -> "${i + 1}"
            }
            canvas.drawText(label, x + 18f, y - 10f, labelPaint)
        }

        if (dragging) drawLoupe(canvas, b)
    }

    /** Лупа: показывает область под пальцем в увеличении, чтобы попасть в мелкую кнопку. */
    private fun drawLoupe(canvas: Canvas, b: Bitmap) {
        val radius = 130f
        val zoom = 3.5f
        val cx = if (fingerX > width / 2f) radius + 24f else width - radius - 24f
        val cy = radius + 24f
        val bx = (fingerX - offX) / scale
        val by = (fingerY - offY) / scale
        val half = radius / (scale * zoom)
        src.set(
            (bx - half).toInt().coerceIn(0, b.width - 1),
            (by - half).toInt().coerceIn(0, b.height - 1),
            (bx + half).toInt().coerceIn(1, b.width),
            (by + half).toInt().coerceIn(1, b.height),
        )
        if (src.width() < 2 || src.height() < 2) return
        val path = Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(cx - radius, cy - radius, cx + radius, cy + radius, bmpPaint.apply { color = Color.BLACK })
        canvas.drawBitmap(b, src, RectF(cx - radius, cy - radius, cx + radius, cy + radius), bmpPaint)
        canvas.restore()
        canvas.drawCircle(cx, cy, radius, loupeBorder)
        canvas.drawLine(cx - 18f, cy, cx + 18f, cy, crossPaint)
        canvas.drawLine(cx, cy - 18f, cx, cy + 18f, crossPaint)
        val p = toNorm(fingerX, fingerY)
        canvas.drawText(p.toString(), cx - radius, cy + radius + 36f, labelPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        layoutBitmap()
    }

    /** Точки, собранные в отрезки (для режима PAIRS). */
    fun asEdges(): List<Edge> {
        val out = ArrayList<Edge>()
        for (i in 0 until points.size - 1 step 2) {
            val a = points[i]
            val c = points[i + 1]
            if (abs(a.x - c.x) > 0.001f || abs(a.y - c.y) > 0.001f) out += Edge(a.x, a.y, c.x, c.y)
        }
        return out
    }

    /** Прямоугольник из двух точек (для режима RECT). */
    fun asRect(): RectN? {
        if (points.size < 2) return null
        return RectN(points[0].x, points[0].y, points[1].x, points[1].y)
    }

    /** Матрица не нужна снаружи, но полезна при отладке раскладки. */
    fun debugMatrix(): Matrix = Matrix().apply {
        setScale(scale, scale)
        postTranslate(offX, offY)
    }
}
