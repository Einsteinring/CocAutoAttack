package com.kotbaton.cocbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.util.zip.GZIPInputStream

/**
 * Чтение заполнения хранилищ на настоящих кадрах (только полосы ресурсов справа вверху)
 * и на нарисованных полосах, где известна точная доля.
 */
class StorageBarsTest {

    private val frameW = 1200
    private val frameH = Vision.WORK_HEIGHT

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

    /** Кадр 1200×540: фрагмент с полосами прижат к правому краю сверху. */
    private fun real(name: String): IntArray {
        val stream = javaClass.classLoader!!.getResourceAsStream("hud_$name.ppm.gz") ?: error("нет $name")
        DataInputStream(GZIPInputStream(stream).buffered()).use { input ->
            check(token(input) == "P6")
            val w = token(input).toInt()
            val h = token(input).toInt()
            check(token(input) == "255")
            val rgb = ByteArray(w * h * 3)
            input.readFully(rgb)
            val px = IntArray(frameW * frameH) { 0xFF000000.toInt() }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = (y * w + x) * 3
                    val r = rgb[i].toInt() and 0xFF
                    val g = rgb[i + 1].toInt() and 0xFF
                    val b = rgb[i + 2].toInt() and 0xFF
                    px[y * frameW + (frameW - w + x)] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return px
        }
    }

    private val goldColor = (232 shl 16) or (192 shl 8) or 17
    private val elixirColor = (197 shl 16) or (34 shl 8) or 192
    private val darkColor = (38 shl 16) or (14 shl 8) or 57

    /** Нарисованные полосы с заданной долей заливки, слева от заливки серая пустота, как в игре. */
    private fun drawn(gold: Double, elixir: Double, dark: Double, gapAt: Int = -1): IntArray {
        val px = IntArray(frameW * frameH) { 0xFF2A2A2A.toInt() }
        val right = frameW - 103
        val lefts = intArrayOf(frameW - 249, frameW - 249, frameW - 201)

        val rows = intArrayOf(40, 90, 140)
        val colors = intArrayOf(goldColor, elixirColor, darkColor)
        val parts = doubleArrayOf(gold, elixir, dark)
        for (k in 0..2) {
            val n = Math.round(parts[k] * (right - lefts[k] + 1)).toInt()
            for (dy in -3..3) {
                for (i in 0 until n) {
                    val x = right - i
                    if (gapAt >= 0 && x in gapAt..gapAt + 1) continue
                    px[(rows[k] + dy) * frameW + x] = 0xFF000000.toInt() or colors[k]
                }
            }
        }
        return px
    }

    private fun assertFill(px: IntArray, gold: Double, elixir: Double, dark: Double, tol: Double = 0.03) {
        val f = StorageBars.read(px, frameW, frameH)
        assertNotNull(f)
        assertEquals("золото", gold, f!!.gold, tol)
        assertEquals("эликсир", elixir, f.elixir, tol)
        assertEquals("чёрный эликсир", dark, f.dark, tol)
    }

    @Test
    fun `пустые хранилища на кадре с малыми запасами`() = assertFill(real("s01"), 0.075, 0.088, 0.68)

    @Test
    fun `заполнены наполовину`() = assertFill(real("s13"), 0.578, 0.544, 0.80)

    @Test
    fun `почти полные, но полными не считаются`() {
        val px = real("s21")
        assertFill(px, 0.816, 0.789, 1.0)
        val f = StorageBars.read(px, frameW, frameH)!!
        assertTrue(f.gold < StorageBars.FULL && f.elixir < StorageBars.FULL)
    }

    @Test
    fun `затемнённый кадр под открытым окном читается так же`() {
        val px = real("s21")
        val dim = IntArray(px.size) { i ->
            val p = px[i]
            val r = ((p shr 16) and 0xFF) / 2
            val g = ((p shr 8) and 0xFF) / 2
            val b = (p and 0xFF) / 2
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        assertFill(dim, 0.816, 0.789, 1.0)
    }

    @Test
    fun `настоящий кадр с полными хранилищами 21 500 000, 22 000 000 и 390 000`() {
        val f = StorageBars.read(real("s31"), frameW, frameH)!!
        assertTrue("золото ${f.gold}", f.gold >= StorageBars.FULL)
        assertTrue("эликсир ${f.elixir}", f.elixir >= StorageBars.FULL)
        assertTrue("чёрный эликсир ${f.dark}", f.dark >= StorageBars.FULL)
    }

    @Test
    fun `полная полоса определяется как полная`() {
        val f = StorageBars.read(drawn(1.0, 1.0, 1.0), frameW, frameH)!!
        assertTrue("золото ${f.gold}", f.gold >= StorageBars.FULL)
        assertTrue("эликсир ${f.elixir}", f.elixir >= StorageBars.FULL)
        assertTrue("чэ ${f.dark}", f.dark >= StorageBars.FULL)
    }

    @Test
    fun `полная полоса с просветом от блика остаётся полной`() {
        val gapAt = frameW - 103 - 60
        val f = StorageBars.read(drawn(1.0, 1.0, 1.0, gapAt), frameW, frameH)!!
        assertTrue("золото ${f.gold}", f.gold >= StorageBars.FULL)
    }

    @Test
    fun `нарисованные доли читаются точно`() {
        for (v in listOf(0.0, 0.1, 0.25, 0.5, 0.75, 0.9)) assertFill(drawn(v, v, v), v, v, v, 0.02)
    }

    @Test
    fun `ресурсы читаются независимо друг от друга`() = assertFill(drawn(1.0, 0.3, 0.6), 1.0, 0.3, 0.6, 0.02)

    @Test
    fun `кадр не той высоты не читается`() {
        assertNull(StorageBars.read(IntArray(1200 * 500), 1200, 500))
    }
}
