package com.kotbaton.cocbot

import java.util.Locale

/** Числа в нормированных координатах пишем через точку, разделитель между ними — запятая. */
internal fun fmtN(v: Float): String = String.format(Locale.US, "%.3f", v)

internal fun numsOf(s: String): List<Float> =
    s.split(',', ';', ' ', '\t')
        .filter { it.isNotBlank() }
        .mapNotNull { it.trim().toFloatOrNull() }

/** Точка в долях экрана (0..1). */
data class PointN(val x: Float, val y: Float) {
    override fun toString(): String = "${fmtN(x)},${fmtN(y)}"

    companion object {
        fun parse(s: String): PointN? {
            val p = numsOf(s)
            return if (p.size >= 2) PointN(p[0], p[1]) else null
        }
    }
}

/** Прямоугольник в долях экрана (0..1). */
data class RectN(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    val left get() = minOf(x1, x2)
    val top get() = minOf(y1, y2)
    val right get() = maxOf(x1, x2)
    val bottom get() = maxOf(y1, y2)
    val isEmpty get() = right - left < 0.01f || bottom - top < 0.01f

    override fun toString(): String = "${fmtN(x1)},${fmtN(y1)},${fmtN(x2)},${fmtN(y2)}"

    companion object {
        val NONE = RectN(0f, 0f, 0f, 0f)

        fun parse(s: String): RectN? {
            val p = numsOf(s)
            return if (p.size >= 4) RectN(p[0], p[1], p[2], p[3]) else null
        }
    }
}

/** Отрезок, вдоль которого высаживаются войска. */
data class Edge(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    override fun toString(): String = "${fmtN(x1)},${fmtN(y1)},${fmtN(x2)},${fmtN(y2)}"

    /** Точка на отрезке: t = 0 начало, t = 1 конец. */
    fun at(t: Float): PointN = PointN(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t)

    companion object {
        fun parse(s: String): Edge? {
            val p = numsOf(s)
            return if (p.size >= 4) Edge(p[0], p[1], p[2], p[3]) else null
        }
    }
}
