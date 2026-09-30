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
    private const val LEFT_FROM_EDGE = 249

    /** Строки внутри полос золота, эликсира и чёрного эликсира, ниже цифр и выше нижней рамки. */
    private val ROWS = intArrayOf(40, 90, 140)

    /** Просвет в заливке, который ещё считается заливкой (блик, цифры). */
    private const val MAX_GAP = 3

    fun read(argb: IntArray, w: Int, h: Int): Fill? {
        if (h != Vision.WORK_HEIGHT || w < 400) return null
        val right = w - RIGHT_FROM_EDGE
        val left = w - LEFT_FROM_EDGE
        val fills = DoubleArray(3) { kind -> fraction(argb, w, ROWS[kind], kind, left, right) }
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

    private fun isFill(p: Int, kind: Int): Boolean {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return when (kind) {
            0 -> r > 190 && g > 140 && b < 120 && r - b > 110
            1 -> r > 150 && b > 150 && g < 90
            else -> g < 32 && b - g > 18 && b > 34 && r < 90 && r - g > 10
        }
    }

    /** Полное хранилище: заливка занимает не меньше 97% полосы. */
    const val FULL = 0.97
}
