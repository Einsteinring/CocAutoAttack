package com.kotbaton.cocbot

import android.graphics.Bitmap

/** Добыча, показанная на экране выбора базы. -1 означает «не удалось прочитать». */
data class Loot(val gold: Int, val elixir: Int, val dark: Int) {
    override fun toString(): String = "золото $gold, эликсир $elixir, чэ $dark"
}

/**
 * Читает «Доступную добычу» с экрана чужой базы: золото, эликсир и чёрный эликсир.
 * Разметка и шаблоны не нужны, цифры читает [LootDigits].
 */
class LootReader(private val vision: Vision, private val cfg: BotConfig) {

    fun read(screen: Bitmap): Loot {
        val (px, w, h) = vision.workPixels(screen) ?: return Loot(-1, -1, -1)
        return Loot(
            gold = LootDigits.read(px, w, h, 0) ?: -1,
            elixir = LootDigits.read(px, w, h, 1) ?: -1,
            dark = LootDigits.read(px, w, h, 2) ?: -1,
        )
    }

    /** Подходит ли база под пороги. Нераспознанное значение (-1) не блокирует атаку. */
    fun isGoodEnough(loot: Loot): Boolean = loot.passes(cfg)
}

/** Правило фильтра: добыча не ниже порогов. Чёрный эликсир проверяется, только если порог задан. */
fun Loot.passes(cfg: BotConfig): Boolean {
    val goldOk = gold < 0 || gold >= cfg.minGold
    val elixirOk = elixir < 0 || elixir >= cfg.minElixir
    val darkOk = cfg.minDark <= 0 || dark < 0 || dark >= cfg.minDark
    return goldOk && elixirOk && darkOk
}
