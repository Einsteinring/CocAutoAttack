package com.kotbaton.cocbot

import android.graphics.Bitmap
import android.graphics.PointF
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Логика бота под последовательность экранов Clash of Clans:
 * база «В бой!» → «Подбор» в обычном сражении → окно «Моя армия», «В бой!» →
 * чужая база («Закончить сражение», «Далее») → высадка → бой («Сдаться») → итоги «Домой».
 *
 * Правило безопасности: окна закрываются только красным крестиком. Зелёные кнопки нажимаются
 * лишь те, что названы явно: «В бой!», «Домой», «Подтвердить» в окне улучшения и «ОК» при сдаче.
 * Так бот не может купить ресурсы за кристаллы и не отменит идущую стройку.
 */
class BotEngine(
    private val cfg: BotConfig,
    private val preset: AttackPreset,
    private val capturer: ScreenCapturer,
    private val vision: Vision,
    private val accessibility: () -> BotAccessibilityService?,
    private val relaunchGame: () -> Boolean = { false },
) {
    private var w = 1
    private var h = 1
    private val rnd = Random(System.currentTimeMillis())
    private val loot = LootReader(vision, cfg)
    private var gotFirstFrame = false

    /** Карточки войск, найденные в текущем бою. null — не искали или не нашли. */
    private var cards: List<PointN>? = null

    private enum class UpgradeResult { DONE, NO_BUTTON, NOT_ENOUGH }

    private companion object {
        /** Сколько ждать первый кадр, прежде чем признать захват нерабочим. */
        const val NO_FRAME_TIMEOUT_MS = 25_000L

        /** Строки в меню строителей под заголовком «Можно улучшить:», в долях высоты экрана. */
        const val ROW_FIRST = 0.056f
        const val ROW_STEP = 0.053f
        const val ROWS_VISIBLE = 3
    }

    // --- Основа ---

    private fun log(msg: String) = BotState.log(msg)

    private fun status(msg: String) {
        BotState.status.value = msg
        log(msg)
    }

    private fun service(): BotAccessibilityService =
        accessibility() ?: throw IllegalStateException("Служба доступности не включена")

    /** Пауза; при включённой «человечности» слегка случайная. */
    private suspend fun pause(ms: Long) {
        if (ms <= 0) return
        val real = if (cfg.humanize) (ms * (0.85 + rnd.nextDouble() * 0.4)).toLong() else ms
        delay(real)
    }

    private suspend fun frame(): Bitmap {
        val started = System.currentTimeMillis()
        var warned = false
        while (true) {
            currentCoroutineContext().ensureActive()
            if (capturer.released) throw IllegalStateException("Захват экрана прекращён")
            val b = capturer.capture()
            if (b != null) {
                if (!gotFirstFrame) {
                    gotFirstFrame = true
                    log("Первый кадр получен: ${b.width}×${b.height}")
                }
                w = b.width
                h = b.height
                return b
            }
            val waited = System.currentTimeMillis() - started
            if (!warned && waited > 5_000) {
                warned = true
                log("Кадры с экрана не приходят, жду...")
            }
            // Бесконечно висеть нельзя: пользователь должен увидеть причину.
            if (waited > NO_FRAME_TIMEOUT_MS) {
                throw IllegalStateException(
                    "Нет кадров с экрана. При запросе доступа выберите «Весь экран», а не одно приложение"
                )
            }
            delay(200)
        }
    }

    /** Берёт кадр, отдаёт его блоку и сразу освобождает память. */
    private suspend fun <T> withScreen(block: (Bitmap) -> T): T {
        val b = frame()
        try {
            return block(b)
        } finally {
            if (!b.isRecycled) b.recycle()
        }
    }

    private fun jitter(amp: Float = 4f): Float = (rnd.nextFloat() * 2f - 1f) * amp

    private suspend fun tapPoint(p: PointN, amp: Float = 4f) {
        service().tap(p.x * w + jitter(amp), p.y * h + jitter(amp))
    }

    private suspend fun tapMatch(m: Match, settleMs: Long = 350) {
        service().tap(m.cx + jitter(), m.cy + jitter())
        pause(settleMs)
    }

    private suspend fun findAny(vararg names: String): Pair<String, Match>? = withScreen { s ->
        for (n in names) {
            val m = vision.find(s, n)
            if (m != null) return@withScreen n to m
        }
        null
    }

    private suspend fun waitFor(timeoutSec: Int, pollMs: Long, vararg names: String): Pair<String, Match>? {
        val end = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < end) {
            currentCoroutineContext().ensureActive()
            findAny(*names)?.let { return it }
            delay(pollMs)
        }
        return null
    }

    private suspend fun waitAndTap(name: String, timeoutSec: Int, pollMs: Long = 700): Boolean {
        val found = waitFor(timeoutSec, pollMs, name) ?: return false
        tapMatch(found.second)
        return true
    }

    /** Закрывает окна красным крестиком. Другие кнопки не трогает. Возвращает true, если что-то закрыл. */
    private suspend fun closeDialogs(rounds: Int = 4): Boolean {
        var tapped = false
        repeat(rounds) {
            val f = findAny("close") ?: return tapped
            log("Закрываю окно крестиком")
            tapMatch(f.second)
            tapped = true
            pause(700)
        }
        return tapped
    }

    // --- Своя база ---

    /** Добивается экрана своей базы с кнопкой «В бой!». */
    private suspend fun ensureHome(rounds: Int = 10): Match? {
        repeat(rounds) {
            currentCoroutineContext().ensureActive()
            // Один кадр на все проверки. Крестик первым: кнопка «В бой!» видна и из-под открытого окна.
            val found = withScreen<Pair<String, Match>?> { s ->
                vision.find(s, "close")?.let { return@withScreen "close" to it }
                vision.find(s, "return_home")?.let { return@withScreen "return_home" to it }
                vision.find(s, "attack")?.let { return@withScreen "attack" to it }
                null
            }
            when (found?.first) {
                "attack" -> return found.second
                "return_home" -> {
                    log("Нажимаю «Домой»")
                    tapMatch(found.second)
                    pause(3000)
                }
                "close" -> {
                    log("Закрываю окно крестиком")
                    tapMatch(found.second)
                    pause(700)
                }
                else -> pause(1500)
            }
        }
        return null
    }

    /** Собирает золото и эликсир со сборщиков: тапает все найденные пузыри. */
    private suspend fun collectResources() {
        if (!vision.has("collect")) return
        val bubbles = withScreen { s -> vision.findAll(s, "collect", maxCount = 12) }
        if (bubbles.isEmpty()) return
        status("Собираю ресурсы: ${bubbles.size}")
        for (m in bubbles) {
            currentCoroutineContext().ensureActive()
            tapMatch(m, 250)
            BotStats.collect()
        }
        pause(600)
    }

    /** Заказывает войска: армия → быстрая тренировка → шаблон армии. */
    private suspend fun trainTroops() {
        if (!vision.has("train_troops") || !vision.has("quick_train") || !vision.has("train_preset")) return
        status("Заказываю войска")
        if (!waitAndTap("train_troops", 6)) return
        pause(1200)
        if (!waitAndTap("quick_train", 6)) {
            closeDialogs(2)
            return
        }
        pause(900)
        if (waitAndTap("train_preset", 6)) {
            log("Тренировка заказана")
            pause(900)
        }
        closeDialogs(3)
        pause(600)
    }

    // --- Прокачка ---

    /**
     * Здание уже выбрано: «Улучшить» → окно «…улучшить до N уровня?» → «Подтвердить».
     * Если ресурсов не хватило, игра предлагает докупить их за кристаллы: такое окно закрываем крестиком.
     */
    private suspend fun upgradeSelected(): UpgradeResult {
        val up = waitFor(3, 500, "upgrade")?.second ?: return UpgradeResult.NO_BUTTON
        tapMatch(up, 1000)
        val confirm = waitFor(4, 500, "confirm")?.second
        if (confirm == null) {
            closeDialogs(2)
            return UpgradeResult.NO_BUTTON
        }
        tapMatch(confirm, 1200)
        if (waitFor(2, 400, "not_enough") != null) {
            log("Не хватает ресурсов, закрываю окно")
            closeDialogs(3)
            return UpgradeResult.NOT_ENOUGH
        }
        closeDialogs(2)
        return UpgradeResult.DONE
    }

    /** Открывает список у значка строителей. Возвращает заголовок «Можно улучшить:». */
    private suspend fun openBuilderMenu(): Match? {
        // Значков вверху два похожих: слева строители, правее помощники. Нужен левый.
        val icon = withScreen { s -> vision.findLeftmost(s, "builder") } ?: return null
        tapMatch(icon, 900)
        return waitFor(4, 500, "can_upgrade")?.second
    }

    private suspend fun closeBuilderMenu() {
        withScreen { s -> vision.findLeftmost(s, "builder") }?.let { tapMatch(it, 500) }
    }

    /**
     * Прокачка без разметки: меню строителей → строка из «Можно улучшить» → «Улучшить» → «Подтвердить».
     * Начинаем, только если в меню есть «Свободен!»: иначе игра предложила бы ускорить стройку за кристаллы.
     */
    private suspend fun upgradeViaBuilders() {
        val limit = max(1, cfg.upgradeMaxPerRun)
        var done = 0
        var poor = 0
        var row = 0
        var attempts = 0
        status("Прокачка через меню строителей")
        while (done < limit && attempts < limit + ROWS_VISIBLE + 2) {
            currentCoroutineContext().ensureActive()
            attempts++
            val label = openBuilderMenu()
            if (label == null) {
                log("Меню строителей не открылось")
                break
            }
            if (withScreen { s -> vision.find(s, "builder_free") } == null) {
                log("Свободных строителей нет")
                closeBuilderMenu()
                break
            }
            if (row >= ROWS_VISIBLE) {
                log("Доступные улучшения в списке закончились")
                closeBuilderMenu()
                break
            }
            val x = label.x + label.w * 0.5f
            val y = label.cy + h * (ROW_FIRST + ROW_STEP * row)
            service().tap(x + jitter(2f), y + jitter(2f))
            pause(1300)
            when (upgradeSelected()) {
                UpgradeResult.DONE -> {
                    done++
                    poor = 0
                    row = 0
                    BotStats.upgrade()
                    log("Улучшение запущено")
                }
                UpgradeResult.NOT_ENOUGH -> {
                    poor++
                    row++
                    if (poor >= 3) {
                        log("Ресурсов не хватает, прокачку прерываю")
                        break
                    }
                }
                UpgradeResult.NO_BUTTON -> {
                    log("Строка ${row + 1}: улучшить нельзя")
                    row++
                }
            }
        }
        status(if (done > 0) "Прокачка: запущено $done" else "Прокачка: ничего не запущено")
        ensureHome(3)
    }

    /** Прокачка по точкам, отмеченным на своей базе (старый способ, если цели заданы). */
    private suspend fun upgradeTargets() {
        val targets = cfg.upgradeTargets.take(max(1, cfg.upgradeMaxPerRun))
        status("Прокачка: ${targets.size} целей")
        var poorInARow = 0
        var done = 0
        for ((i, t) in targets.withIndex()) {
            currentCoroutineContext().ensureActive()
            status("Прокачка: цель ${i + 1}/${targets.size}")
            tapPoint(t, amp = 2f)
            pause(900)
            when (upgradeSelected()) {
                UpgradeResult.DONE -> {
                    done++
                    poorInARow = 0
                    BotStats.upgrade()
                    log("Улучшение запущено (цель ${i + 1})")
                }
                UpgradeResult.NOT_ENOUGH -> {
                    poorInARow++
                    if (poorInARow >= 3) {
                        log("Ресурсов не хватает, прокачку прерываю")
                        break
                    }
                }
                UpgradeResult.NO_BUTTON -> log("Цель ${i + 1}: кнопки «Улучшить» нет")
            }
            tapPoint(cfg.deselectPoint, amp = 2f)
            pause(400)
        }
        status(if (done > 0) "Прокачка: запущено $done" else "Прокачка: ничего не запущено")
        ensureHome(3)
    }

    private suspend fun runUpgrades() {
        val need = listOf("upgrade", "confirm", "not_enough", "close")
        val missing = need.filter { !vision.has(it) }
        if (missing.isNotEmpty()) {
            log("Прокачка пропущена: нет шаблонов ${missing.joinToString()}")
            return
        }
        if (cfg.upgradeTargets.isNotEmpty()) {
            upgradeTargets()
            return
        }
        if (!vision.has("builder") || !vision.has("builder_free") || !vision.has("can_upgrade")) {
            log("Прокачка пропущена: нет шаблонов меню строителей")
            return
        }
        upgradeViaBuilders()
    }

    // --- Поиск противника ---

    /** Жмёт «Подбор» обычного сражения: левый из двух, правый — ранговый бой. */
    private suspend fun tapFindMatch(): Boolean {
        val end = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < end) {
            currentCoroutineContext().ensureActive()
            val m = withScreen { s -> vision.findLeftmost(s, "find_match") }
            if (m != null) {
                tapMatch(m)
                return true
            }
            delay(700)
        }
        return false
    }

    /** После «Подбор» игра показывает «Моя армия» с кнопкой «В бой!». Если база загрузилась сразу, ничего не трогаем. */
    private suspend fun confirmArmy() {
        if (!vision.has("army_attack")) return
        val found = waitFor(12, 700, "next", "end_battle", "army_attack") ?: return
        if (found.first != "army_attack") return
        log("Окно «Моя армия»: жму «В бой!»")
        tapMatch(found.second)
        pause(1500)
    }

    /** Ждёт загрузки чужой базы. */
    private suspend fun waitForBase(): Boolean {
        val r = waitFor(60, 800, "next", "end_battle")
        if (r != null) pause(1200)
        return r != null
    }

    /** Пропускает базы, пока добыча меньше порогов. Возвращает добычу выбранной базы. */
    private suspend fun pickBase(): Loot? {
        if (!cfg.lootFilter) {
            repeat(cfg.skipBases) {
                status("Пропускаю базу ${it + 1}/${cfg.skipBases}")
                if (!waitAndTap("next", 10)) return null
                BotStats.skip()
                pause(1500)
                if (!waitForBase()) return null
            }
            return null
        }
        if (!loot.ready()) {
            log("Фильтр добычи включён, но нет шаблонов цифр d0..d9. Атакую без фильтра.")
            return null
        }
        var skips = 0
        while (skips <= cfg.maxSkips) {
            currentCoroutineContext().ensureActive()
            val l = withScreen { s -> loot.read(s) }
            if (loot.isGoodEnough(l)) {
                log("База подходит: $l")
                return l
            }
            status("База бедная ($l), ищу дальше [$skips/${cfg.maxSkips}]")
            if (!waitAndTap("next", 10)) {
                log("Кнопка «Далее» пропала")
                return null
            }
            skips++
            BotStats.skip()
            pause(1500)
            if (!waitForBase()) return null
        }
        log("Достигнут предел пропусков, атакую что есть")
        return null
    }

    // --- Высадка ---

    private fun edgePoint(edge: Edge, t: Float): PointF {
        val p = edge.at(t)
        return PointF(p.x * w + jitter(6f), p.y * h + jitter(6f))
    }

    /** Точка карточки: найденная на панели, а если панель не распознана — из настроек. */
    private fun cardPoint(index: Int): PointN? {
        val found = cards ?: return cfg.slotPoint(index)
        return found.getOrNull(index)
    }

    private suspend fun tapSlot(index: Int): Boolean {
        val p = cardPoint(index) ?: return false
        tapPoint(p, amp = 3f)
        pause(280)
        return true
    }

    private suspend fun waitUntil(timeMs: Long) {
        val left = timeMs - System.currentTimeMillis()
        if (left > 0) delay(left)
    }

    private suspend fun detectCards() {
        val found = withScreen { s -> TroopBar.detect(s) }
        cards = found?.centers?.takeIf { it.isNotEmpty() }
        if (cards == null) {
            log("Карточки войск не распознаны, беру точки из настроек")
        } else {
            log("Карточек войск на панели: ${cards!!.size}")
        }
    }

    private suspend fun deploy() {
        val svc = service()
        detectCards()
        val perEdge = preset.pointsPerEdge.coerceAtLeast(1)
        val edges = preset.effectiveEdges(w.toFloat() / h).ifEmpty { AttackPreset.mapEdges(w.toFloat() / h) }
        val startMs = System.currentTimeMillis()
        val cardCount = cards?.size

        for ((i, step) in preset.steps.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (cardCount != null && step.slot >= cardCount) continue
            status("Высадка: шаг ${i + 1}/${preset.steps.size} (карточка ${step.slot + 1} ×${step.count})")
            if (!tapSlot(step.slot)) continue
            val stepEdges = step.edges.mapNotNull { edges.getOrNull(it) }.ifEmpty { edges }
            val points = ArrayList<PointF>(step.count)
            for (k in 0 until step.count) {
                val edge = stepEdges[k % stepEdges.size]
                val idx = (k / stepEdges.size) % perEdge
                val t = if (perEdge <= 1) 0.5f else idx.toFloat() / (perEdge - 1)
                points += edgePoint(edge, t)
            }
            svc.tapSequence(points, preset.tapIntervalMs)
            pause(step.delayAfterMs)
        }
        val stepsDoneAt = System.currentTimeMillis()

        var heroesAt = 0L
        if (preset.heroSlots.isNotEmpty()) {
            status("Высадка героев")
            val heroEdges = preset.heroEdges.mapNotNull { edges.getOrNull(it) }.ifEmpty { edges }
            for ((i, slot) in preset.heroSlots.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (!tapSlot(slot)) continue
                val p = edgePoint(heroEdges[i % heroEdges.size], 0.5f)
                svc.tap(p.x, p.y)
                pause(400)
            }
            heroesAt = System.currentTimeMillis()
        }

        // Способности героев и заклинания срабатывают по времени от начала высадки.
        val events = ArrayList<Pair<Long, suspend () -> Unit>>(2)
        if (heroesAt > 0 && preset.useHeroAbilities) {
            events += (heroesAt + preset.heroAbilityDelaySec * 1000L) to {
                status("Способности героев")
                for (slot in preset.heroSlots) {
                    tapSlot(slot)
                    delay(200)
                }
            }
        }
        if (preset.heroSlots.isEmpty() && preset.useHeroAbilities && preset.abilityCards > 0) {
            // Где стоят герои, неизвестно: проходим по всем карточкам. Для героя это способность,
            // для обычного войска просто выбор карточки без высадки.
            val count = min(preset.abilityCards, cardCount ?: preset.abilityCards)
            events += (stepsDoneAt + preset.heroAbilityDelaySec * 1000L) to {
                status("Способности героев")
                for (slot in 0 until count) {
                    tapSlot(slot)
                    delay(120)
                }
            }
        }
        if (preset.spellSlots.isNotEmpty()) {
            events += (startMs + preset.spellDelaySec * 1000L) to {
                status("Заклинания")
                val pts = preset.spellPoints.ifEmpty { mutableListOf(PointN(0.5f, 0.45f)) }
                for ((i, slot) in preset.spellSlots.withIndex()) {
                    if (!tapSlot(slot)) continue
                    tapPoint(pts[i % pts.size], amp = 5f)
                    delay(350)
                }
            }
        }
        for ((at, action) in events.sortedBy { it.first }) {
            currentCoroutineContext().ensureActive()
            waitUntil(at)
            action()
        }
    }

    /** «Сдаться» во время боя (или «Закончить сражение», если высадки не было) и «ОК» в подтверждении. */
    private suspend fun surrender() {
        log("Бой затянулся, сдаюсь")
        val btn = waitFor(5, 600, "surrender", "end_battle") ?: return
        tapMatch(btn.second, 900)
        if (vision.has("okay")) waitAndTap("okay", 4)
        BotStats.surrender()
    }

    private suspend fun waitBattleEnd() {
        val deadline = System.currentTimeMillis() + cfg.battleTimeoutSec * 1000L
        var surrendered = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val f = findAny("return_home")
            if (f != null) {
                log("Бой окончен, возвращаюсь домой")
                tapMatch(f.second)
                pause(3000)
                closeDialogs(3)
                return
            }
            if (!surrendered && System.currentTimeMillis() > deadline) {
                surrender()
                surrendered = true
            }
            if (surrendered && System.currentTimeMillis() > deadline + 60_000L) {
                log("Не дождался конца боя, продолжаю")
                return
            }
            delay(2000)
        }
    }

    // --- Сценарии ---

    private fun checkTemplates(): Boolean {
        val missing = vision.missingFor(cfg)
        if (missing.isEmpty()) return true
        val core = missing.filter { it in Vision.CORE }
        if (core.isNotEmpty()) {
            log("Нет обязательных шаблонов: ${core.joinToString { "$it.png" }}")
            return false
        }
        log("Нет шаблонов для части функций: ${missing.joinToString { "$it.png" }}. Эти шаги будут пропущены")
        return true
    }

    /** Полный цикл автоатаки. */
    suspend fun run() {
        if (!Vision.ensureLoaded()) return
        if (!checkTemplates()) return
        if (preset.steps.isEmpty()) {
            status("В шаблоне «${preset.name}» нет шагов высадки")
            return
        }
        service()
        BotStats.reset()
        log("Шаблон атаки: ${preset.name} (${preset.summary()})")
        status("Переключитесь в игру, старт через 5 с")
        delay(5000)

        var done = 0
        var failures = 0
        var sinceUpgrade = 0
        while (done < cfg.maxAttacks) {
            currentCoroutineContext().ensureActive()

            status("Ищу экран своей базы")
            val attack = ensureHome()
            if (attack == null) {
                failures++
                val score = withScreen { s -> vision.best(s, "attack")?.score }
                val scoreText = if (score == null) {
                    "шаблон attack.png не читается"
                } else {
                    String.format(Locale.US, "лучшее совпадение %.2f при пороге %.2f", score, cfg.threshold)
                }
                log("Не вижу кнопку «В бой!» (попытка $failures, $scoreText)")
                if (cfg.restartGameOnStuck && failures == 3) {
                    log("Перезапускаю игру")
                    if (relaunchGame()) pause(20_000)
                }
                if (failures >= 5) {
                    status("Остановлен: не нахожу экран базы")
                    return
                }
                pause(5000)
                continue
            }
            failures = 0

            if (cfg.collectResources) collectResources()
            if (cfg.trainTroops) trainTroops()
            if (cfg.autoUpgrade && sinceUpgrade >= cfg.upgradeEveryAttacks - 1) {
                runUpgrades()
                sinceUpgrade = 0
            } else {
                sinceUpgrade++
            }

            val attackBtn = ensureHome(4)
            if (attackBtn == null) {
                log("После домашних дел не нашёл «В бой!», повторяю круг")
                continue
            }

            status("Атака ${done + 1}/${cfg.maxAttacks}: поиск противника")
            tapMatch(attackBtn, 900)
            if (!tapFindMatch()) {
                log("Не найдена кнопка «Подбор»")
                closeDialogs(2)
                continue
            }
            confirmArmy()
            if (!waitForBase()) {
                log("База не загрузилась")
                closeDialogs(2)
                continue
            }

            val picked = pickBase()
            if (picked != null) {
                BotStats.loot(
                    picked.gold.coerceAtLeast(0),
                    picked.elixir.coerceAtLeast(0),
                    picked.dark.coerceAtLeast(0),
                )
            }

            status("Высадка войск")
            deploy()

            status("Ожидание конца боя")
            waitBattleEnd()

            done++
            BotStats.attack()
            status("Атака $done/${cfg.maxAttacks} завершена")
            val lo = cfg.pauseBetweenMinSec.coerceAtLeast(0)
            val hi = cfg.pauseBetweenMaxSec.coerceAtLeast(lo)
            pause((lo + rnd.nextInt(hi - lo + 1)) * 1000L)
        }
        status("Готово: $done атак. ${BotStats.text.value}")
    }

    /** Разовый заход прокачки без атак. */
    suspend fun upgradeOnly() {
        if (!Vision.ensureLoaded()) return
        service()
        BotStats.reset()
        status("Прокачка: переключитесь в игру, старт через 5 с")
        delay(5000)
        if (ensureHome() == null) {
            status("Не вижу экран базы")
            return
        }
        if (cfg.collectResources) collectResources()
        runUpgrades()
        status("Прокачка завершена. ${BotStats.text.value}")
    }

    /** Разовый сбор ресурсов. */
    suspend fun collectOnly() {
        if (!Vision.ensureLoaded()) return
        service()
        BotStats.reset()
        status("Сбор: переключитесь в игру, старт через 5 с")
        delay(5000)
        if (ensureHome() == null) {
            status("Не вижу экран базы")
            return
        }
        collectResources()
        status("Сбор завершён: ${BotStats.collected} пузырей")
    }

    private fun saveScreenshot(bitmap: Bitmap, name: String): File {
        val file = File(vision.screensDir, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    /** Через delaySec секунд сохраняет скриншот экрана. */
    suspend fun screenshot(delaySec: Int) {
        for (i in delaySec downTo 1) {
            status("Скриншот через $i с, откройте нужный экран игры")
            delay(1000)
        }
        val f = withScreen { s -> saveScreenshot(s, "screen_${System.currentTimeMillis()}.png") }
        // Старые скриншоты не копим: держим последние 30, этого хватает на полный набор экранов с запасом.
        vision.screensDir.listFiles { file -> file.name.startsWith("screen_") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(30)
            ?.forEach { it.delete() }
        status("Скриншот сохранён: ${f.name}")
    }

    /** Проверяет все шаблоны на текущем экране и пишет точность в лог. */
    suspend fun runTest(delaySec: Int) {
        if (!Vision.ensureLoaded()) return
        for (i in delaySec downTo 1) {
            status("Проверка через $i с, откройте экран игры")
            delay(1000)
        }
        val names = vision.availableTemplates()
        if (names.isEmpty()) {
            status("Нет ни одного шаблона")
            return
        }
        withScreen { s ->
            saveScreenshot(s, "last_test.png")
            log("Экран ${s.width}x${s.height}, порог ${cfg.threshold}")
            for (n in names) {
                val m = vision.best(s, n)
                if (m == null) {
                    log("$n: не удалось сравнить")
                } else {
                    val mark = when {
                        m.score >= cfg.threshold -> "НАЙДЕН"
                        m.score >= 0.60 -> "нет, но близко"
                        else -> "нет"
                    }
                    log(String.format(Locale.US, "%s: %.2f %s @ (%d,%d)", n, m.score, mark, m.cx.toInt(), m.cy.toInt()))
                }
            }
            val found = TroopBar.detect(s)
            if (found != null && found.centers.isNotEmpty()) {
                val xs = found.centers.joinToString { String.format(Locale.US, "%.3f", it.x) }
                log("Карточки войск: ${found.centers.size}, центры по X: $xs")
            }
            if (cfg.lootFilter && loot.ready()) {
                val l = loot.read(s)
                log("Добыча в заданных областях: $l")
            }
        }
        val missing = vision.missingFor(cfg)
        if (missing.isNotEmpty()) log("Не хватает для включённых функций: ${missing.joinToString()}")
        status("Проверка завершена, смотрите лог")
    }
}
