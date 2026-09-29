package com.kotbaton.cocbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * Поиск карточек войск на настоящих кадрах игры. В файлах лежит только нижняя полоса экрана
 * (PPM, сжатый gzip), верх кадра дорисовывается чёрным: в репозиторий не попадают ник и клан.
 */
class TroopBarTest {

    private fun readToken(input: DataInputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) break
            if (c.toChar().isWhitespace()) {
                if (sb.isEmpty()) continue else break
            }
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    /** Кадр высотой 540: полоса из файла внизу, выше чёрный фон. */
    private fun load(name: String): Triple<IntArray, Int, Int> {
        val stream = javaClass.classLoader!!.getResourceAsStream(name) ?: error("нет $name")
        DataInputStream(GZIPInputStream(stream).buffered()).use { input ->
            check(readToken(input) == "P6")
            val w = readToken(input).toInt()
            val bandH = readToken(input).toInt()
            check(readToken(input) == "255")
            val h = Vision.WORK_HEIGHT
            val px = IntArray(w * h) { 0xFF000000.toInt() }
            val rgb = ByteArray(w * bandH * 3)
            input.readFully(rgb)
            val top = h - bandH
            for (i in 0 until w * bandH) {
                val r = rgb[i * 3].toInt() and 0xFF
                val g = rgb[i * 3 + 1].toInt() and 0xFF
                val b = rgb[i * 3 + 2].toInt() and 0xFF
                px[top * w + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            return Triple(px, w, h)
        }
    }

    /** Центры карточек, отмеченные вручную на тех же кадрах (в пикселях кадра высотой 540). */
    private val truth = listOf(224, 296, 368, 453, 530, 601, 673, 745, 828)

    private fun check(name: String) {
        val (px, w, h) = load(name)
        val cards = TroopBar.detectPixels(px, w, h)
        assertNotNull("карточки не найдены на $name", cards)
        val xs = cards!!.centers.map { it.x * w }
        assertEquals("число карточек на $name: $xs", truth.size, xs.size)
        truth.zip(xs).forEach { (t, x) ->
            assertTrue("карточка у $t найдена в $x на $name", abs(t - x) <= 4f)
        }
        cards.centers.forEach { assertTrue("y карточки ${it.y}", it.y in 0.85f..0.95f) }
    }

    @Test
    fun `находит все девять карточек до начала боя`() = check("bar_s06.ppm.gz")

    @Test
    fun `находит карточки и когда одна поднята как выбранная`() = check("bar_s07.ppm.gz")

    @Test
    fun `на своей базе карточек войск нет`() {
        val (px, w, h) = load("bar_s01.ppm.gz")
        val cards = TroopBar.detectPixels(px, w, h)
        val count = cards?.centers?.size ?: 0
        assertTrue("на базе нашлось $count карточек", count < 2)
    }

    @Test
    fun `цвет рамки карточки распознаётся`() {
        assertTrue(TroopBar.isCardColor(0xFF4A8FD8.toInt()))
        assertTrue(TroopBar.isCardColor(0xFF6B3FD0.toInt()))
        assertTrue(!TroopBar.isCardColor(0xFF5A9E2F.toInt()))
        assertTrue(!TroopBar.isCardColor(0xFF202020.toInt()))
        assertTrue(!TroopBar.isCardColor(0xFFE0E0E0.toInt()))
    }
}
