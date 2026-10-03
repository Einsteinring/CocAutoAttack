package com.kotbaton.cocbot

/**
 * Насколько заполнены хранилища, по трём полосам ресурсов справа вверху экрана базы.
 *
 * Заливка растёт от иконки ресурса влево, полное хранилище это заливка до левого края полосы.
 * Измерено на кадрах игры: положение начала заливки линейно зависит от суммы, поэтому доля
 * заполнения полосы точно показывает, сколько ещё влезет. Работает без OpenCV, поэтому проверяется
 * юнит-тестами. Кадр должен быть в рабочем масштабе высотой [Vision.WORK_HEIGHT].
 */
internal object StorageBars {

    /** Доля заполнения от 0 до 1 для каждого ресурса. */
    data class Fill(val gold: Double, val elixir: Double, val dark: Double) {
        override fun toString(): String =
            "золото ${pct(gold)}, эликсир ${pct(elixir)}, чёрный эликсир ${pct(dark)}"

        private fun pct(v: Double) = "${Math.round(v * 100)}%"
    }

    /** Расстояния от правого края кадра: правый и левый край внутренности полосы. */
    private const val RIGHT_FROM_EDGE = 103
    /**
     * Левый край внутренности полосы для золота, эликсира и чёрного эликсира. Полоса чёрного
     * эликсира короче двух других: измерено на кадре с полными хранилищами, она начинается в 201
     * пикселе от правой кромки, а не в 249. Раньше все три считались одной ширины, и полное
     * хранилище чёрного эликсира читалось как 68%, поэтому бот его не дожидался.
     */
    private val LEFT_FROM_EDGE = intArrayOf(249, 249, 201)

    /** Строки внутри полос золота, эликсира и чёрного эликсира, ниже цифр и выше нижней рамки. */
    private val ROWS = intArrayOf(40, 90, 140)

    /** Просвет в заливке, который ещё считается заливкой (блик, цифры). */
    private const val MAX_GAP = 3

    fun read(argb: IntArray, w: Int, h: Int): Fill? {
        if (h != Vision.WORK_HEIGHT || w < 400) return null
        val right = w - RIGHT_FROM_EDGE
        val fills = DoubleArray(3) { kind -> fraction(argb, w, ROWS[kind], kind, w - LEFT_FROM_EDGE[kind], right) }
        return Fill(fills[0], fills[1], fills[2])
    }

    private fun fraction(argb: IntArray, w: Int, y: Int, kind: Int, left: Int, right: Int): Double {
        var x = right
        var start = right + 1
        var gap = 0
        while (x >= left) {
            if (isFill(avg(argb, w, x, y), kind)) {
                start = x
                gap = 0
            } else if (++gap > MAX_GAP) {
                break
            }
            x--
        }
        val width = right - start + 1
        return (width.toDouble() / (right - left + 1)).coerceIn(0.0, 1.0)
    }

    /** Среднее по трём строкам вокруг [y], чтобы убрать шум сжатия. */
    private fun avg(argb: IntArray, w: Int, x: Int, y: Int): Int {
        var r = 0
        var g = 0
        var b = 0
        for (dy in -1..1) {
            val p = argb[(y + dy) * w + x]
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        return ((r / 3) shl 16) or ((g / 3) shl 8) or (b / 3)
    }

    /**
     * Пиксель заливки определяется по оттенку и насыщенности, а не по яркости: поверх базы бывает
     * открыто окно, игра затемняет весь экран, и яркостный порог давал 0% у всех трёх полос.
     * Оттенок при затемнении не меняется. Золото 35–60°, эликсир 285–325°, чёрный эликсир 255–300°
     * и тёмный.
     */
    internal fun isFill(p: Int, kind: Int): Boolean {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        if (mx == 0) return false
        val d = mx - mn
        val s = d.toFloat() / mx
        val v = mx / 255f
        if (d == 0) return false
        val h = when (mx) {
            r -> 60f * (((g - b).toFloat() / d) % 6f)
            g -> 60f * ((b - r).toFloat() / d + 2f)
            else -> 60f * ((r - g).toFloat() / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return when (kind) {
            0 -> h in 35f..60f && s >= 0.55f && v >= 0.25f
            1 -> h in 285f..325f && s >= 0.50f && v >= 0.25f
            else -> h in 255f..300f && s >= 0.40f && v in 0.08f..0.45f
        }
    }

    /** Полное хранилище: заливка занимает не меньше 97% полосы. */
    const val FULL = 0.97
}
