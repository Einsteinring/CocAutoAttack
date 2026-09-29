package com.kotbaton.cocbot

import android.graphics.Bitmap

/** Добыча, показанная на экране выбора базы. */
data class Loot(val gold: Int, val elixir: Int, val dark: Int) {
    override fun toString(): String = "золото $gold, эликсир $elixir, чэ $dark"
}

/**
 * Читает числа добычи шаблонами цифр `d0.png`..`d9.png`.
 * Внутри области ищутся все цифры, потом сортируются слева направо.
 */
class LootReader(private val vision: Vision, private val cfg: BotConfig) {

    private data class Digit(val value: Int, val x: Int, val w: Int, val score: Double)

    fun ready(): Boolean = Vision.DIGITS.all { vision.has(it) }

    /** Число в области или null, если область не задана или цифр не видно. */
    fun readNumber(screen: Bitmap, rect: RectN): Int? {
        if (rect.isEmpty) return null
        val found = ArrayList<Digit>(12)
        for (d in 0..9) {
            for (m in vision.findAllIn(screen, rect, "d$d", maxCount = 9, minScore = DIGIT_SCORE)) {
                found += Digit(d, m.x, m.w, m.score)
            }
        }
        if (found.isEmpty()) return null
        // Две цифры не могут стоять на одном месте: оставляем ту, что распозналась увереннее.
        val sorted = found.sortedByDescending { it.score }
        val kept = ArrayList<Digit>(12)
        for (d in sorted) {
            if (kept.none { kotlin.math.abs(it.x - d.x) < it.w * 0.6 }) kept += d
        }
        val text = kept.sortedBy { it.x }.joinToString("") { it.value.toString() }
        return text.take(9).toIntOrNull()
    }

    fun read(screen: Bitmap): Loot = Loot(
        gold = readNumber(screen, cfg.goldRect) ?: -1,
        elixir = readNumber(screen, cfg.elixirRect) ?: -1,
        dark = readNumber(screen, cfg.darkRect) ?: -1,
    )

    /** Подходит ли база под пороги. Нераспознанное значение (-1) не блокирует атаку. */
    fun isGoodEnough(loot: Loot): Boolean {
        val goldOk = loot.gold < 0 || loot.gold >= cfg.minGold
        val elixirOk = loot.elixir < 0 || loot.elixir >= cfg.minElixir
        val darkOk = cfg.minDark <= 0 || loot.dark < 0 || loot.dark >= cfg.minDark
        return goldOk && elixirOk && darkOk
    }

    companion object {
        /** Цифры мелкие, порог для них отдельный и чуть мягче общего. */
        private const val DIGIT_SCORE = 0.75
    }
}
