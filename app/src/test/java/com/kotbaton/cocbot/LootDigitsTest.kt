package com.kotbaton.cocbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.DataInputStream
import java.util.zip.GZIPInputStream

/**
 * Чтение добычи на настоящих кадрах. В файлах лежит только область с числами (ники и кланы
 * не попадают в репозиторий), остальной кадр дорисовывается чёрным. Кадры s06 и s07 в образцах
 * цифр не участвовали, так что это независимая проверка.
 */
class LootDigitsTest {

    private val x0 = 90
    private val y0 = 60

    private fun token(input: DataInputStream): String {
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

    private fun load(name: String): Triple<IntArray, Int, Int> {
        val stream = javaClass.classLoader!!.getResourceAsStream("loot_$name.ppm.gz") ?: error("нет $name")
        DataInputStream(GZIPInputStream(stream).buffered()).use { input ->
            check(token(input) == "P6")
            val w = token(input).toInt()
            val h = token(input).toInt()
            check(token(input) == "255")
            val rgb = ByteArray(w * h * 3)
            input.readFully(rgb)
            val fw = 1200
            val fh = Vision.WORK_HEIGHT
            val px = IntArray(fw * fh) { 0xFF000000.toInt() }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = (y * w + x) * 3
                    val r = rgb[i].toInt() and 0xFF
                    val g = rgb[i + 1].toInt() and 0xFF
                    val b = rgb[i + 2].toInt() and 0xFF
                    px[(y0 + y) * fw + x0 + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return Triple(px, fw, fh)
        }
    }

    private fun check(name: String, gold: Int, elixir: Int, dark: Int) {
        val (px, w, h) = load(name)
        assertEquals("золото на $name", gold, LootDigits.read(px, w, h, 0))
        assertEquals("эликсир на $name", elixir, LootDigits.read(px, w, h, 1))
        assertEquals("чёрный эликсир на $name", dark, LootDigits.read(px, w, h, 2))
    }

    @Test
    fun `читает базу с малой добычей, снимок не участвовал в образцах`() = check("s06", 63881, 271936, 524)

    @Test
    fun `читает базу со средней добычей, снимок не участвовал в образцах`() = check("s07", 351561, 444968, 8884)

    @Test
    fun `читает числа с нулями, шестизначные и семизначные`() {
        check("s16", 214124, 104544, 7512)
        check("s17", 1388737, 1390128, 8330)
        check("s18", 109802, 790891, 2526)
    }

    @Test
    fun `на пустом кадре чисел нет`() {
        val w = 1200
        val px = IntArray(w * Vision.WORK_HEIGHT) { 0xFF203020.toInt() }
        for (row in 0..2) assertNull(LootDigits.read(px, w, Vision.WORK_HEIGHT, row))
    }

    @Test
    fun `кадр не той высоты не читается`() {
        val px = IntArray(1200 * 500)
        assertNull(LootDigits.read(px, 1200, 500, 0))
    }
}
