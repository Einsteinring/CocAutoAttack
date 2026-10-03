package com.kotbaton.cocbot

import kotlin.math.sqrt

/**
 * Чтение чисел «Доступной добычи» на экране выбора базы.
 *
 * Цифры белые с тёмной обводкой. Кадр приводится к высоте 540, ряд режется на глифы по белой маске,
 * каждый глиф сравнивается с усреднёнными образцами (см. [LootDigitData]) по нормированной корреляции
 * с допуском в один пиксель по обеим осям. Работает без OpenCV, поэтому проверяется юнит-тестами.
 */
internal object LootDigits {

    /** Левая и правая граница области цифр при высоте кадра 540. */
    private const val X1 = 96
    private const val X2 = 190

    /** Ниже этой похожести глиф не считается цифрой. */
    private const val MIN_SCORE = 0.5

    /** Промежуток между тысячными группами «1 388 737»: измерено 6–7 пикселей, внутри группы 1–3. */
    private const val GROUP_GAP = 5

    /** С такого промежутка начинается уже не число: на кадрах мусор после числа отстоял на 11. */
    private const val JUNK_GAP = 9

    private const val CW = LootDigitData.WIDTH
    private const val CH = LootDigitData.HEIGHT

    private val protos: List<Pair<Char, DoubleArray>> by lazy {
        LootDigitData.PROTOS.map { (d, rows) ->
            val a = DoubleArray(CW * CH)
            rows.forEachIndexed { y, r -> r.forEachIndexed { x, c -> a[y * CW + x] = (c - '0') / 9.0 } }
            d to a
        }
    }

    private class Glyph(val x0: Int, val x1: Int, val top: Int)

    /**
     * Число в ряду [row]: 0 золото, 1 эликсир, 2 чёрный эликсир.
     * [argb] — кадр высотой ровно [Vision.WORK_HEIGHT] пикселей.
     * null, если в ряду нет цифр, хоть одну из них не удалось уверенно распознать или число
     * собралось не целиком.
     *
     * Целостность проверяется по разрядам: игра пишет «1 388 737», внутри тысячной группы между
     * цифрами 1–3 пикселя, между группами 6–7. Первая группа из 1–3 цифр, остальные ровно по 3.
     * Раньше неуверенные цифры в конце просто отбрасывались, и «1 058 4…» превращалось в «10584»:
     * база с миллионом считалась бедной. Теперь такое число не читается, а нечитаемое не мешает атаке.
     */
    fun read(argb: IntArray, width: Int, height: Int, row: Int): Int? {
        if (height != Vision.WORK_HEIGHT || row !in LootDigitData.ROW_TOP.indices) return null
        val glyphs = segment(argb, width, row)
        if (glyphs.isEmpty()) return null
        // Всё, что отстоит дальше обычного промежутка между группами, уже не число (иконка, рамка).
        var count = glyphs.size
        for (i in 1 until glyphs.size) {
            if (glyphs[i].x0 - glyphs[i - 1].x1 >= JUNK_GAP) {
                count = i
                break
            }
        }
        val digits = glyphs.take(count)
        val parts = digits.map { classify(argb, width, it) }
        if (parts.any { it.second < MIN_SCORE }) return null
        if (!wellGrouped(digits)) return null
        val text = parts.joinToString("") { it.first.toString() }
        return text.take(9).toIntOrNull()
    }

    /** Группы разрядов: первая из 1–3 цифр, каждая следующая ровно из 3. */
    private fun wellGrouped(digits: List<Glyph>): Boolean {
        val sizes = ArrayList<Int>()
        var current = 1
        for (i in 1 until digits.size) {
            if (digits[i].x0 - digits[i - 1].x1 >= GROUP_GAP) {
                sizes += current
                current = 1
            } else {
                current++
            }
        }
        sizes += current
        if (sizes.first() !in 1..3) return false
        return sizes.drop(1).all { it == 3 }
    }

    /** Белая маска: малая насыщенность и высокая яркость, как в HSV OpenCV (0..255). */
    private fun isWhite(p: Int): Boolean {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        if (mx <= 200) return false
        return (mx - mn) * 255 < 80 * mx
    }

    private fun whiteness(p: Int): Double {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val v = mx / 255.0
        val s = if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
        return (v * (1 - s) * 1.2).coerceIn(0.0, 1.0)
    }

    private fun segment(argb: IntArray, w: Int, row: Int): List<Glyph> {
        val top = LootDigitData.ROW_TOP[row]
        val y1 = top - 7
        val y2 = top + 19
        val x2 = minOf(X2, w)
        val out = ArrayList<Glyph>()
        var prevEnd = -1
        var x = X1
        while (x < x2) {
            if (!columnHasWhite(argb, w, x, y1, y2)) {
                x++
                continue
            }
            val a = x
            while (x < x2 && columnHasWhite(argb, w, x, y1, y2)) x++
            val b = x
            var ymin = Int.MAX_VALUE
            var ymax = Int.MIN_VALUE
            for (y in y1 until y2) {
                for (xx in a until b) {
                    if (isWhite(argb[y * w + xx])) {
                        if (y < ymin) ymin = y
                        if (y > ymax) ymax = y
                        break
                    }
                }
            }
            val h = ymax - ymin + 1
            if (h < 9 || h > 14) continue
            if (b - a < 3 && h < 10) continue
            if (prevEnd >= 0 && a - prevEnd > 11) break
            out += Glyph(a, b, ymin)
            prevEnd = b
        }
        return out
    }

    private fun columnHasWhite(argb: IntArray, w: Int, x: Int, y1: Int, y2: Int): Boolean {
        for (y in y1 until y2) if (isWhite(argb[y * w + x])) return true
        return false
    }

    /** Лучшая цифра для глифа и её похожесть. */
    private fun classify(argb: IntArray, w: Int, g: Glyph): Pair<Char, Double> {
        var best = -2.0
        var bestDigit = '?'
        val cell = DoubleArray(CW * CH)
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (!fill(argb, w, g.x0 - 1 + dx, g.top - 1 + dy, cell)) continue
                for ((d, p) in protos) {
                    val s = ncc(cell, p)
                    if (s > best) {
                        best = s
                        bestDigit = d
                    }
                }
            }
        }
        return bestDigit to best
    }

    /** Кладёт белизну окна 12×13 с левым верхним углом (x0, y0) в [out]. false, если окно вышло за кадр. */
    private fun fill(argb: IntArray, w: Int, x0: Int, y0: Int, out: DoubleArray): Boolean {
        if (x0 < 0 || y0 < 0 || x0 + CW > w || (y0 + CH) * w > argb.size) return false
        for (y in 0 until CH) {
            for (x in 0 until CW) out[y * CW + x] = whiteness(argb[(y0 + y) * w + x0 + x])
        }
        return true
    }

    private fun ncc(a: DoubleArray, b: DoubleArray): Double {
        var ma = 0.0
        var mb = 0.0
        for (i in a.indices) {
            ma += a[i]
            mb += b[i]
        }
        ma /= a.size
        mb /= b.size
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in a.indices) {
            val x = a[i] - ma
            val y = b[i] - mb
            num += x * y
            da += x * x
            db += y * y
        }
        val d = sqrt(da * db)
        return if (d > 1e-6) num / d else -1.0
    }
}
