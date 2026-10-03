package com.kotbaton.cocbot

import android.graphics.Bitmap
import android.graphics.PointF
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
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
    private val closeGame: () -> Boolean = { false },
) {
    private var w = 1
    private var h = 1
    private val rnd = Random(System.currentTimeMillis())
    private val loot = LootReader(vision, cfg)
    private var gotFirstFrame = false

    /** Карточки войск, найденные в текущем бою. null — не искали или не нашли. */
    private var cards: List<PointN>? = null

    private enum class UpgradeResult { DONE, NO_BUTTON, NOT_ENOUGH, CANCELLED }

    private companion object {
        /** Предохранитель режима «фарм до максимума»: больше этого числа атак за запуск не делаем. */
        const val FARM_ATTACK_CAP = 2000

        /** Предохранитель: столько стен за один заход, дальше остановка. Обычно раньше кончаются ресурсы. */
        const val WALL_RUN_CAP = 400

        /** Сколько раз за один заход можно отменить окно «Улучшить стены» и попробовать снова. */
        const val MAX_WALL_CANCELS = 3

        /** Сколько раз нажать «Попробуйте снова», прежде чем закрыть игру. */
        const val MAX_RETRIES = 10

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

    /**
     * Служба для тапа. Если игра свёрнута или поверх неё открыто другое приложение (в том числе
     * сам бот), ждёт её возвращения: иначе тапы попадали бы в чужое окно и бота было бы не остановить.
     */
    private suspend fun svc(): BotAccessibilityService {
        awaitGame()
        return service()
    }

    private suspend fun awaitGame() {
        if (BotAccessibilityService.gameOnScreen()) return
        val before = BotState.status.value
        status("Пауза: игра не на экране. Вернитесь в игру или нажмите «Стоп»")
        while (!BotAccessibilityService.gameOnScreen()) {
            currentCoroutineContext().ensureActive()
            delay(500)
        }
        log("Игра снова на экране, продолжаю")
        delay(1500)
        BotState.status.value = before
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
        var started = System.currentTimeMillis()
        var warned = false
        while (true) {
            currentCoroutineContext().ensureActive()
            if (capturer.released) throw IllegalStateException("Захват экрана прекращён")
            val b = capturer.capture()
            if (b != null) {
                // Окно «Ошибка подключения» или «Подключение прервано» может выскочить на любом шаге:
                // закрываем его здесь, чтобы ни один шаг не тыкал в кнопки под затемнением.
                val retry = findRetry(b)
                if (retry != null) {
                    b.recycle()
                    retryConnection(retry)
                    started = System.currentTimeMillis()
                    continue
                }
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
        svc().tap(p.x * w + jitter(amp), p.y * h + jitter(amp))
    }

    private suspend fun tapMatch(m: Match, settleMs: Long = 350) {
        svc().tap(m.cx + jitter(), m.cy + jitter())
        pause(settleMs)
    }

    private suspend fun findAny(vararg names: String): Pair<String, Match>? = withScreen { s ->
        for (n in names) {
            val m = vision.find(s, n)
            if (m != null) return@withScreen n to m
        }
        null
    }

    private fun findRetry(s: Bitmap): Match? = if (vision.has("retry")) vision.find(s, "retry") else null

    private var reconnects = 0

    /** Нажатий «Попробуйте снова» с тех пор, как игра последний раз была в рабочем состоянии. */
    private var retryAttempts = 0

    /** Растёт с каждым нажатием «Попробуйте снова»: по нему шаги понимают, что игра перезагружалась. */
    private var retryTick = 0

    /** Связь не восстановилась за [MAX_RETRIES] нажатий: закрываем игру и останавливаем бота. */
    private suspend fun giveUpOnConnection(): Nothing {
        val msg = "Связь не восстановилась за $MAX_RETRIES попыток, игра закрыта"
        log("Игра не перешла в рабочее состояние: закрываю её")
        try {
            service().goHome()
        } catch (e: IllegalStateException) {
            log("Служба доступности недоступна: ${e.message}")
        }
        delay(1500)
        if (!closeGame()) log("Закрыть игру не получилось, закройте её вручную")
        status(msg)
        throw IllegalStateException(msg)
    }

    /** Игра в рабочем состоянии: счётчик неудачных подключений обнуляется. */
    private fun connectionOk() {
        retryAttempts = 0
    }

    /** Окно «Ошибка подключения»: жмёт «Попробуйте снова» и даёт игре время загрузиться. */
    private suspend fun retryConnection(m: Match) {
        if (retryAttempts >= MAX_RETRIES) giveUpOnConnection()
        retryAttempts++
        reconnects++
        retryTick++
        log("Ошибка подключения, жму «Попробуйте снова» (раз $reconnects)")
        tapMatch(m, 4000)
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

    /**
     * Нажимает «Отмена». Безопасно в любом окне с двумя кнопками: отмена ничего не тратит и не
     * прекращает стройку. Окно «Улучшить стены» бот отменяет, потому что оно выбирает стены группой.
     */
    private suspend fun tapCancel(): Boolean {
        val c = waitFor(3, 400, "cancel")?.second
        if (c == null) {
            log("Кнопка «Отмена» не найдена")
            return false
        }
        tapMatch(c, 900)
        return true
    }

    /** Окно «Получен звёздный бонус!»: жмёт зелёную «ОК» этого окна. Для другого окна ничего не делает. */
    private suspend fun dismissStarBonus(): Boolean {
        val (title, ok) = withScreen { s ->
            vision.find(s, "star_bonus") to vision.find(s, "star_ok")
        }
        if (title == null) return false
        log("Звёздный бонус: жму «ОК»")
        if (ok != null) {
            tapMatch(ok, 1200)
        } else {
            // Кнопка «ОК» стоит по центру под заголовком, на 0,745 высоты экрана ниже него.
            svc().tap(title.cx + jitter(3f), title.cy + 0.745f * h + jitter(3f))
            pause(1200)
        }
        return true
    }

    // --- Своя база ---

    /** Добивается экрана своей базы с кнопкой «В бой!». */
    private suspend fun ensureHome(rounds: Int = 10): Match? {
        var left = rounds
        while (left > 0) {
            left--
            currentCoroutineContext().ensureActive()
            // Один кадр на все проверки. Ошибка подключения и крестик первыми: кнопка «В бой!» видна и из-под окна.
            val tickBefore = retryTick
            val found = withScreen<Pair<String, Match>?> { s ->
                vision.find(s, "star_bonus")?.let { return@withScreen "star_bonus" to it }
                vision.find(s, "walls_dialog")?.let { return@withScreen "walls_dialog" to it }
                vision.find(s, "close")?.let { return@withScreen "close" to it }
                vision.find(s, "return_home")?.let { return@withScreen "return_home" to it }
                vision.find(s, "attack")?.let { return@withScreen "attack" to it }
                null
            }
            // Игре нужно время загрузиться после «Попробуйте снова»: не тратим на это попытки поиска.
            if (retryTick != tickBefore) left = max(left, 25)
            when (found?.first) {
                "attack" -> {
                    connectionOk()
                    return found.second
                }
                "star_bonus" -> dismissStarBonus()
                "walls_dialog" -> {
                    log("Окно «Улучшить стены»: жму «Отмена»")
                    tapCancel()
                }
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
    /** Места, где «пузырь» не исчез после нажатия: это не пузырь, больше туда не нажимаем. */
    private val stuckBubbles = ArrayList<PointN>()

    private fun sameSpot(a: Match, b: Match): Boolean =
        abs(a.cx - b.cx) < 0.015f * w && abs(a.cy - b.cy) < 0.03f * h

    private fun isStuck(m: Match): Boolean =
        stuckBubbles.any { abs(it.x * w - m.cx) < 0.015f * w && abs(it.y * h - m.cy) < 0.03f * h }

    private suspend fun scanBubbles(): List<Match> = withScreen { s ->
        vision.findAll(s, "collect", maxCount = 20).filter { !inHud(it, s.width, s.height) }
    }

    /**
     * Собирает ресурсы: тапает пузыри над шахтами и сборщиками. Собранный пузырь исчезает, поэтому
     * после нажатия бот сканирует кадр ещё раз, и всё, что осталось на прежнем месте, считает ложным
     * срабатыванием и запоминает.
     */
    private suspend fun collectResources() {
        if (!vision.has("collect")) return
        var last: List<Match> = emptyList()
        for (pass in 0 until 3) {
            currentCoroutineContext().ensureActive()
            val seen = scanBubbles()
            for (m in seen) {
                if (last.any { sameSpot(it, m) } && !isStuck(m)) {
                    stuckBubbles += PointN(m.cx / w, m.cy / h)
                    log("Пузырь не исчез после нажатия, это место пропускаю")
                }
            }
            val fresh = seen.filter { !isStuck(it) }
            if (fresh.isEmpty()) break
            if (pass == 0) status("Собираю ресурсы: ${fresh.size}")
            for (m in fresh) {
                currentCoroutineContext().ensureActive()
                tapMatch(m, 250)
                BotStats.collect()
            }
            last = fresh
            pause(700)
        }
    }

    /** Совпадение попало на панели игры (полоса ресурсов, боковые кнопки): это не пузырь над зданием. */
    private fun inHud(m: Match, w: Int, h: Int): Boolean {
        val nx = m.cx / w
        val ny = m.cy / h
        return (nx > 0.78f && ny < 0.38f) || nx < 0.10f || nx > 0.90f ||
            (ny > 0.78f && (nx < 0.27f || nx > 0.80f))
    }

    // --- Стены ---

    /** Все стены улучшены: список строителей просмотрен до конца, строки «Стена» нет. */
    private var wallsDone = false

    /** Улучшить стену не получилось несколько раз подряд: до конца запуска не пробуем. */
    private var wallsBlocked = false
    private var wallFailStreak = 0

    /** Сколько раз за заход окно «Улучшить стены» пришлось отменять. */
    private var wallCancels = 0
    private var menuFailures = 0
    private var wallDebugSaved = false

    private enum class WallSearch { FOUND, NONE, MENU_FAILED }

    /** Список строителей сейчас на экране: виден один из его заголовков или строка «Стена». */
    private suspend fun menuIsOpen(): Boolean = findAny("menu_head", "can_upgrade", "wall_row") != null

    /**
     * Открывает список строителей: тап по правому из двух значков (там «1/7» или «0/7»).
     * Значок открывает и закрывает список, поэтому, если список уже открыт, второй раз не нажимаем.
     */
    private suspend fun openBuilderMenu(): Boolean {
        if (menuIsOpen()) return true
        repeat(2) {
            val icon = withScreen { s -> vision.findRightmost(s, "builder") } ?: return false
            tapMatch(icon, 1000)
            if (waitFor(3, 500, "menu_head", "can_upgrade", "wall_row") != null) return true
        }
        return false
    }

    private suspend fun closeBuilderMenu() {
        if (!menuIsOpen()) return
        withScreen { s -> vision.findRightmost(s, "builder") }?.let { tapMatch(it, 500) }
    }

    /**
     * Есть ли свободный строитель: в счётчике «N/7» слева от дроби не ноль.
     * null, если значок строителей не нашёлся.
     */
    private suspend fun hasFreeBuilder(): Boolean? = withScreen { s ->
        val icon = vision.findRightmost(s, "builder") ?: return@withScreen null
        val nx = icon.cx / s.width
        val ny = icon.cy / s.height
        val area = RectN(nx + 0.010f, ny - 0.045f, nx + 0.075f, ny + 0.045f)
        val zero = vision.findAllIn(s, area, "no_builders", maxCount = 1, minScore = 0.85)
        zero.isEmpty()
    }

    /** Грубый отпечаток области списка: по нему видно, сдвинулся ли список после прокрутки. */
    private fun listSignature(s: Bitmap): IntArray {
        val x1 = (0.39f * s.width).toInt()
        val x2 = (0.63f * s.width).toInt()
        val y1 = (0.16f * s.height).toInt()
        val y2 = (0.80f * s.height).toInt()
        val out = ArrayList<Int>()
        var y = y1
        while (y < y2) {
            var x = x1
            while (x < x2) {
                val p = s.getPixel(x, y)
                out += (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
                x += 6
            }
            y += 6
        }
        return out.toIntArray()
    }

    private fun sameList(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size || a.isEmpty()) return false
        var changed = 0
        for (i in a.indices) if (abs(a[i] - b[i]) > 40) changed++
        return changed * 100 < a.size * 3
    }

    /** Листает список вверх на треть окна: строки между экранами не пропускаются. */
    private suspend fun scrollList() {
        val x = 0.51f * w
        svc().swipe(x, 0.74f * h, x, 0.42f * h, 700)
        pause(800)
    }

    /**
     * Открывает список строителей и ищет строку «Стена». Если её нет на экране, листает вниз
     * до конца списка: конец определяется по тому, что после прокрутки картинка не меняется.
     */
    private suspend fun findWall(): Pair<WallSearch, Match?> {
        if (!openBuilderMenu()) return WallSearch.MENU_FAILED to null
        var prev: IntArray? = null
        var unchanged = 0
        repeat(16) {
            currentCoroutineContext().ensureActive()
            val (m, sig) = withScreen { s -> vision.find(s, "wall_row") to listSignature(s) }
            if (m != null) return WallSearch.FOUND to m
            val p = prev
            if (p != null && sameList(p, sig)) {
                if (++unchanged >= 2) {
                    closeBuilderMenu()
                    return WallSearch.NONE to null
                }
            } else {
                unchanged = 0
            }
            prev = sig
            scrollList()
        }
        closeBuilderMenu()
        return WallSearch.NONE to null
    }

    /**
     * Стена выбрана. Внизу две кнопки «Улучшить»: за золото (левая) и за эликсир (правая, она видна
     * целиком, а левую частично закрывает список строителей, поэтому её точка считается от правой).
     * Начинаем с того ресурса, чьё хранилище полнее: так ресурс не пропадает впустую. Если на него не
     * хватило, пробуем второй. Дальше окно «Подтвердить» и проверка окна «Не хватает».
     */
    private suspend fun upgradeSelected(): UpgradeResult {
        var poor = false
        val fill = readStorages()
        val goldFirst = fill != null && fill.gold > fill.elixir
        for (attempt in 0..1) {
            val ups = waitFor(3, 500, "upgrade") ?: run {
                saveDebug("нет_кнопки_улучшить")
                return if (poor) UpgradeResult.NOT_ENOUGH else UpgradeResult.NO_BUTTON
            }
            val right = ups.second
            val step = 106f * h / Vision.WORK_HEIGHT
            val elixir = PointF(right.cx, right.cy)
            val gold = PointF(right.cx - step, right.cy + 8f * h / Vision.WORK_HEIGHT)
            val goldNow = if (attempt == 0) goldFirst else !goldFirst
            val target = if (goldNow) gold else elixir
            log("Улучшаю стену за ${if (goldNow) "золото" else "эликсир"}")
            svc().tap(target.x + jitter(2f), target.y + jitter(2f))
            pause(1000)
            val answer = waitFor(4, 500, "confirm", "walls_dialog")
            if (answer?.first == "walls_dialog") {
                log("Вместо подтверждения вылезло окно «Улучшить стены»: жму «Отмена» и начну заново через список")
                tapCancel()
                return UpgradeResult.CANCELLED
            }
            val confirm = answer?.second
            if (confirm == null) {
                saveDebug("нет_подтверждения")
                closeDialogs(2)
                continue
            }
            tapMatch(confirm, 1200)
            if (waitFor(2, 400, "not_enough") != null) {
                log("На ${if (goldNow) "золото" else "эликсир"} не хватает, закрываю окно")
                closeDialogs(3)
                poor = true
                continue
            }
            closeDialogs(2)
            return UpgradeResult.DONE
        }
        return if (poor) UpgradeResult.NOT_ENOUGH else UpgradeResult.NO_BUTTON
    }

    /**
     * Прокачка только стен. Значок строителей, строка «Стена» (список листается вниз, пока её нет
     * на экране), «Улучшить», «Подтвердить». Ресурсы кончились: стоп до следующего захода.
     * Строки «Стена» нет во всём списке: все стены улучшены, дальше прокачка не запускается.
     */
    private suspend fun upgradeWalls() {
        if (wallsDone || wallsBlocked) return
        val missing = listOf("builder", "no_builders", "wall_row", "upgrade", "confirm", "not_enough", "close")
            .filter { !vision.has(it) }
        if (missing.isNotEmpty()) {
            log("Прокачка стен пропущена: нет шаблонов ${missing.joinToString()}")
            wallsBlocked = true
            return
        }
        when (hasFreeBuilder()) {
            null -> {
                log("Значок строителей не найден, прокачку стен пропускаю")
                return
            }
            false -> {
                log("Свободных строителей нет (0/…), прокачку стен пропускаю")
                return
            }
            true -> Unit
        }
        val limit = WALL_RUN_CAP
        var done = 0
        wallCancels = 0
        status("Прокачка стен")
        while (done < limit) {
            currentCoroutineContext().ensureActive()
            val (state, row) = findWall()
            if (state == WallSearch.MENU_FAILED) {
                log("Список строителей не открылся")
                saveDebug("список_не_открылся")
                if (++menuFailures >= 2) {
                    wallsBlocked = true
                    log("Прокачка стен отключена до конца запуска")
                }
                break
            }
            menuFailures = 0
            if (state == WallSearch.NONE || row == null) {
                wallsDone = true
                status("Все стены прокачаны")
                break
            }
            tapMatch(row, 1500)
            if (!wallDebugSaved) {
                wallDebugSaved = true
                saveDebug("стена_выбрана")
            }
            when (upgradeSelected()) {
                UpgradeResult.DONE -> {
                    done++
                    wallFailStreak = 0
                    BotStats.upgrade()
                    log("Стена улучшена ($done)")
                }
                UpgradeResult.NOT_ENOUGH -> {
                    log("На следующую стену не хватает ресурсов")
                    break
                }
                UpgradeResult.CANCELLED -> {
                    if (++wallCancels > MAX_WALL_CANCELS) {
                        log("Окно «Улучшить стены» вылезло слишком часто, прокачку до следующего захода прекращаю")
                        break
                    }
                    closeBuilderMenu()
                }
                UpgradeResult.NO_BUTTON -> {
                    log("Стену улучшить не удалось, смотрите снимок неудачи в архиве")
                    if (++wallFailStreak >= 3) {
                        wallsBlocked = true
                        log("Прокачка стен отключена до конца запуска: три неудачи подряд")
                    }
                    break
                }
            }
        }
        if (!wallsDone) status(if (done > 0) "Стен улучшено: $done" else "Стены: ничего не улучшено")
        closeBuilderMenu()
        ensureHome(3)
    }

    // --- Хранилища ---

    /** Заполнение хранилищ по полосам ресурсов. null, если кадр не прочитался. */
    private suspend fun readStorages(): StorageBars.Fill? {
        pause(900)
        return withScreen { s -> vision.workPixels(s)?.let { (px, w, h) -> StorageBars.read(px, w, h) } }
    }

    private var storageDebugSaved = false

    /** Какие хранилища бот ждёт полными, словами для лога. */
    private fun waitingFor(): String {
        val names = listOfNotNull(
            "золото".takeIf { cfg.fullGold },
            "эликсир".takeIf { cfg.fullElixir },
            "чёрный эликсир".takeIf { cfg.fullDark },
        )
        return if (names.isEmpty()) "ничего не выбрано в настройках, остановлюсь только по числу атак" else names.joinToString(", ")
    }

    /** Все выбранные в настройках хранилища заполнены. Если не выбрано ни одного, считается, что нет. */
    private fun isFull(f: StorageBars.Fill): Boolean {
        val checks = listOf(cfg.fullGold to f.gold, cfg.fullElixir to f.elixir, cfg.fullDark to f.dark)
            .filter { it.first }
        return checks.isNotEmpty() && checks.all { it.second >= StorageBars.FULL }
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
        if (r != null) connectionOk()
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
            svc().tapSequence(points, preset.tapIntervalMs)
            if (preset.autoEdges) {
                // Добор: часть тапов попадает в запретную зону у построек, стоящих у самого края.
                // Те же края, но у кромки травы и между прежними точками, где точно можно высаживать.
                val outer = AttackPreset.mapEdges(w.toFloat() / h, AttackPreset.OUTER_REACH)
                val outerEdges = step.edges.mapNotNull { outer.getOrNull(it) }.ifEmpty { outer }
                val extra = max(4, step.count / 3)
                val more = ArrayList<PointF>(extra)
                for (k in 0 until extra) {
                    val edge = outerEdges[k % outerEdges.size]
                    val idx = (k / outerEdges.size) % perEdge
                    val t = ((idx + 0.5f) / perEdge).coerceIn(0f, 1f)
                    more += edgePoint(edge, t)
                }
                svc().tapSequence(more, preset.tapIntervalMs)
            }
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
                svc().tap(p.x, p.y)
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
                dismissStarBonus()
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

    /**
     * Полный цикл автоатаки. [farmToFull]: фармить до полных хранилищ и остановиться,
     * без прокачки и без ограничения по числу атак.
     */
    suspend fun run(farmToFull: Boolean = false) {
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
        val limit = if (farmToFull) FARM_ATTACK_CAP else cfg.maxAttacks
        if (farmToFull) log("Фарм до полных хранилищ: остановлюсь, когда они заполнятся")
        while (done < limit) {
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
            if (!farmToFull && cfg.autoUpgrade && sinceUpgrade >= cfg.upgradeEveryAttacks - 1) {
                upgradeWalls()
                sinceUpgrade = 0
            } else {
                sinceUpgrade++
            }

            // Остановка по хранилищам: только когда стены больше не улучшаются (или прокачка выключена).
            val onlyFarming = farmToFull || !cfg.autoUpgrade || wallsDone || wallsBlocked
            if (onlyFarming && (farmToFull || cfg.stopWhenFull)) {
                val fill = readStorages()
                if (fill == null || (fill.gold == 0.0 && fill.elixir == 0.0 && fill.dark == 0.0)) {
                    log("Полосы хранилищ не прочитались: на экране, видимо, открыто окно")
                    if (!storageDebugSaved) {
                        storageDebugSaved = true
                        saveDebug("хранилища")
                    }
                } else {
                    log("Хранилища: $fill · жду: ${waitingFor()}")
                    if (isFull(fill)) {
                        status("Хранилища заполнены ($fill). Готово: $done атак")
                        return
                    }
                }
            }

            val attackBtn = ensureHome(4)
            if (attackBtn == null) {
                log("После домашних дел не нашёл «В бой!», повторяю круг")
                continue
            }

            status("Атака ${done + 1}${if (farmToFull) "" else "/$limit"}: поиск противника")
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
            status("Атака $done${if (farmToFull) "" else "/$limit"} завершена")
            val lo = cfg.pauseBetweenMinSec.coerceAtLeast(0)
            val hi = cfg.pauseBetweenMaxSec.coerceAtLeast(lo)
            pause((lo + rnd.nextInt(hi - lo + 1)) * 1000L)
        }
        status("Готово: $done атак. ${BotStats.text.value}")
    }

    /** Разовый заход прокачки стен без атак. */
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
        upgradeWalls()
        if (!wallsDone) status("Прокачка стен завершена. ${BotStats.text.value}")
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

    /** Снимок в момент неудачи: попадает в архив из «Шаблоны кнопок», по нему видно, что было на экране. */
    private suspend fun saveDebug(tag: String) {
        try {
            withScreen { s -> saveScreenshot(s, "dbg_${tag}_${System.currentTimeMillis()}.png") }
            vision.screensDir.listFiles { f -> f.name.startsWith("dbg_") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(10)
                ?.forEach { it.delete() }
            log("Сохранён снимок неудачи: dbg_$tag")
        } catch (e: java.io.IOException) {
            log("Снимок неудачи не сохранён: ${e.message}")
        }
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
            if (cfg.lootFilter) {
                val l = loot.read(s)
                log("Добыча в заданных областях: $l")
            }
        }
        val missing = vision.missingFor(cfg)
        if (missing.isNotEmpty()) log("Не хватает для включённых функций: ${missing.joinToString()}")
        status("Проверка завершена, смотрите лог")
    }
}
