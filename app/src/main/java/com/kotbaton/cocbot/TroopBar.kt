package com.kotbaton.cocbot

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Находит карточки войск на панели внизу экрана боя без всякой разметки.
 *
 * У каждой карточки (войска, машины, герои, заклинания) сине-фиолетовая рамка одинакового размера,
 * а сквозь просветы между карточками видна зелёная карта. Поэтому ищем прямоугольники известного
 * размера, у которых все четыре стороны «карточного» цвета. Пустые слоты с пунктиром не находятся.
 */
object TroopBar {

    /** Найденные карточки слева направо, в долях экрана. */
    data class Cards(val centers: List<PointN>, val scores: List<Double>)

    private const val H = Vision.WORK_HEIGHT
    private const val MIN_SCORE = 0.55

    /** Кадр с устройства: уменьшаем до рабочей высоты и ищем. */
    fun detect(frame: Bitmap): Cards? {
        val scale = H.toDouble() / frame.height
        val w = max(1, (frame.width * scale).roundToInt())
        val small = if (abs(scale - 1.0) < 0.01) frame else Bitmap.createScaledBitmap(frame, w, H, true)
        try {
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            return detectPixels(pixels, small.width, small.height)
        } finally {
            if (small !== frame) small.recycle()
        }
    }

    /**
     * Пиксель похож на рамку карточки: оттенок от голубого до фиолетового (170°–330°),
     * насыщенность от 0.235 и яркость от 0.275. Считаем HSV сами, чтобы код проверялся на ПК.
     */
    internal fun isCardColor(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        if (mx < 70) return false
        val d = mx - mn
        if (d * 1000 < 235 * mx) return false
        val hue = when (mx) {
            r -> 60f * (((g - b).toFloat() / d) % 6f)
            g -> 60f * ((b - r).toFloat() / d + 2f)
            else -> 60f * ((r - g).toFloat() / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return hue in 170f..330f
    }

    /** Поиск по пикселям кадра высотой [Vision.WORK_HEIGHT]. */
    internal fun detectPixels(argb: IntArray, w: Int, height: Int): Cards? {
        if (height != H || w < 100) return null
        val bandTop = (0.78 * H).toInt()
        val bandH = H - bandTop

        // Интегральное изображение маски: сумма по любому прямоугольнику за O(1).
        val ii = IntArray((w + 1) * (bandH + 1))
        for (y in 0 until bandH) {
            var rowSum = 0
            val src = (bandTop + y) * w
            for (x in 0 until w) {
                if (isCardColor(argb[src + x])) rowSum++
                ii[(y + 1) * (w + 1) + (x + 1)] = ii[y * (w + 1) + (x + 1)] + rowSum
            }
        }
        fun frac(x1: Int, y1: Int, x2: Int, y2: Int): Double {
            val a = x1.coerceIn(0, w)
            val b = x2.coerceIn(0, w)
            val c = (y1 - bandTop).coerceIn(0, bandH)
            val d = (y2 - bandTop).coerceIn(0, bandH)
            val area = (b - a) * (d - c)
            if (area <= 0) return 0.0
            val sum = ii[d * (w + 1) + b] - ii[c * (w + 1) + b] - ii[d * (w + 1) + a] + ii[c * (w + 1) + a]
            return sum.toDouble() / area
        }

        var bestTotal = 0.0
        var bestXs: List<Int> = emptyList()
        var bestScores: List<Double> = emptyList()
        var bestCw = 0
        var bestTop = 0
        var bestCh = 0
        for (top in (0.80 * H).toInt()..(0.86 * H).toInt()) {
            for (cw in (0.115 * H).toInt()..(0.132 * H).toInt()) {
                val ch = (cw * 1.24).roundToInt()
                val bot = top + ch
                if (bot + 3 >= H) continue
                val n = w - cw
                if (n <= 0) continue
                val sc = DoubleArray(n)
                for (x in 0 until n) {
                    val l = frac(x, top + 6, x + 3, bot - 6)
                    val r = frac(x + cw - 3, top + 6, x + cw, bot - 6)
                    val t = frac(x + 5, top, x + cw - 5, top + 3)
                    val b = frac(x + 5, bot - 3, x + cw - 5, bot)
                    sc[x] = (l + r + t + b) / 4
                }
                val order = (0 until n).sortedByDescending { sc[it] }
                val picks = ArrayList<Int>()
                for (x in order) {
                    if (sc[x] < MIN_SCORE) break
                    if (picks.all { abs(it - x) >= cw - 2 }) picks += x
                }
                val total = picks.sumOf { sc[it] }
                if (total > bestTotal) {
                    bestTotal = total
                    bestXs = picks.sorted()
                    bestScores = bestXs.map { sc[it] }
                    bestCw = cw
                    bestTop = top
                    bestCh = ch
                }
            }
        }
        if (bestXs.isEmpty()) return null
        val cy = (bestTop + bestCh / 2f) / H
        return Cards(bestXs.map { PointN((it + bestCw / 2f) / w, cy) }, bestScores)
    }
}
