package com.kotbaton.cocbot

import kotlinx.coroutines.flow.MutableStateFlow

/** Счётчики текущей сессии: показываются в приложении и в уведомлении. */
object BotStats {
    val text = MutableStateFlow("")

    var attacks = 0
        private set
    var skipped = 0
        private set
    var upgrades = 0
        private set
    var collected = 0
        private set
    var surrenders = 0
        private set
    var gold = 0L
        private set
    var elixir = 0L
        private set
    var dark = 0L
        private set

    private var startedAt = 0L

    fun reset() {
        attacks = 0
        skipped = 0
        upgrades = 0
        collected = 0
        surrenders = 0
        gold = 0
        elixir = 0
        dark = 0
        startedAt = System.currentTimeMillis()
        publish()
    }

    fun attack() {
        attacks++
        publish()
    }

    fun skip() {
        skipped++
        publish()
    }

    fun upgrade() {
        upgrades++
        publish()
    }

    fun collect() {
        collected++
        publish()
    }

    fun surrender() {
        surrenders++
        publish()
    }

    /** Добыча базы, которую бот решил атаковать (оценка по фильтру, а не реальный трофей). */
    fun loot(g: Int, e: Int, d: Int) {
        gold += g
        elixir += e
        dark += d
        publish()
    }

    /** Минут с начала запуска; ноль, пока бот не стартовал. */
    fun minutes(): Long {
        if (startedAt == 0L) return 0
        return (System.currentTimeMillis() - startedAt) / 60_000L
    }

    fun publish() {
        val parts = mutableListOf(
            "Атак: $attacks",
            "пропущено баз: $skipped",
            "улучшений: $upgrades",
        )
        if (collected > 0) parts += "сборов: $collected"
        if (surrenders > 0) parts += "сдач: $surrenders"
        if (gold > 0 || elixir > 0 || dark > 0) {
            parts += "добыча ≈ ${money(gold)} зол / ${money(elixir)} элик" +
                if (dark > 0) " / ${money(dark)} чэ" else ""
        }
        parts += "время: ${minutes()} мин"
        text.value = parts.joinToString(" · ")
    }

    private fun money(v: Long): String = when {
        v >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", v / 1_000_000.0)
        v >= 1_000 -> "${v / 1000}K"
        else -> v.toString()
    }
}
