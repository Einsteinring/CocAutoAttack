package com.kotbaton.cocbot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Описание одного шаблона кнопки для списка в приложении. */
data class TemplateInfo(val name: String, val about: String, val core: Boolean)

/** Результат поиска шаблона на экране, в пикселях настоящего экрана. */
data class Match(val x: Int, val y: Int, val w: Int, val h: Int, val score: Double) {
    val cx: Float get() = x + w / 2f
    val cy: Float get() = y + h / 2f
}

/** Встроенный набор шаблонов из assets/templates. */
private class BundledPack(val names: Set<String>, val lang: String)

/**
 * Поиск кнопок игры по шаблонам через OpenCV.
 *
 * Все сравнения идут в едином рабочем масштабе: кадр уменьшается до высоты [WORK_HEIGHT],
 * и шаблоны хранятся в этом же масштабе. Так один набор подходит телефонам с любым разрешением,
 * а поиск идёт в несколько раз быстрее, чем по полному кадру.
 *
 * У шаблона может быть несколько вариантов: `close.png`, `close~2.png`, `close~3.png`.
 * Засчитывается лучший из них. Свои вырезки пользователя важнее встроенных.
 */
class Vision(private val context: Context, private val threshold: () -> Double) {

    companion object {
        /** Высота кадра, в которой идёт поиск и хранятся шаблоны. */
        const val WORK_HEIGHT = 540

        /** Шаблоны, без которых не работает основной цикл. */
        val CORE = listOf("attack", "find_match", "next", "end_battle", "return_home")

        /** Обязательные шаблоны: имя → что вырезать. */
        val CORE_ABOUT = mapOf(
            "attack" to "кнопка «В бой!» на своей базе",
            "find_match" to "кнопка «Подбор» в обычном сражении",
            "next" to "кнопка «Далее» на чужой базе",
            "end_battle" to "кнопка «Закончить сражение» до высадки",
            "return_home" to "кнопка «Домой» на итогах боя",
        )

        /** Шаблоны отдельных функций: имя → зачем. */
        val FEATURE = linkedMapOf(
            "army_attack" to "кнопка «В бой!» в окне «Моя армия»",
            "surrender" to "кнопка «Сдаться» во время боя",
            "close" to "красный крестик окна",
            "okay" to "кнопка «ОК» в подтверждении сдачи",
            "builder" to "значок строителей вверху экрана",
            "builder_free" to "строка «Свободен!» в меню строителей",
            "can_upgrade" to "заголовок «Можно улучшить:» в меню строителей",
            "upgrade" to "кнопка «Улучшить» у выбранного здания",
            "confirm" to "кнопка «Подтвердить» в окне улучшения",
            "not_enough" to "заголовок «Не хватает» в окне нехватки ресурсов",
            "collect" to "пузырь сбора ресурсов",
            "train_troops" to "кнопка армии/тренировки",
            "quick_train" to "вкладка быстрой тренировки",
            "train_preset" to "кнопка «Тренировать» у шаблона армии",
        )

        /** Цифры для чтения добычи. */
        val DIGITS = (0..9).map { "d$it" }

        /** Полный список шаблонов для экрана «Шаблоны кнопок». */
        fun catalog(): List<TemplateInfo> {
            val out = ArrayList<TemplateInfo>(CORE.size + FEATURE.size + DIGITS.size)
            CORE.forEach { out += TemplateInfo(it, CORE_ABOUT[it] ?: it, true) }
            FEATURE.forEach { (n, about) -> out += TemplateInfo(n, about, false) }
            DIGITS.forEachIndexed { i, n -> out += TemplateInfo(n, "цифра $i из числа добычи", false) }
            return out
        }

        private const val ASSET_DIR = "templates"
        private const val MARKER = ".scale540"

        @Volatile
        private var loaded = false

        @Volatile
        private var pack: BundledPack? = null

        fun ensureLoaded(): Boolean {
            if (loaded) return true
            loaded = try {
                OpenCVLoader.initLocal()
            } catch (e: Throwable) {
                false
            }
            if (!loaded) BotState.log("OpenCV не загрузился")
            return loaded
        }

        private fun pack(ctx: Context): BundledPack {
            pack?.let { return it }
            val p = try {
                val files = ctx.assets.list(ASSET_DIR)?.toList().orEmpty()
                val names = files.filter { it.endsWith(".png") }.map { it.removeSuffix(".png") }.toSet()
                var lang = ""
                if ("pack.json" in files) {
                    val text = ctx.assets.open("$ASSET_DIR/pack.json").bufferedReader().use { it.readText() }
                    lang = JSONObject(text).optString("lang", "")
                }
                BundledPack(names, lang)
            } catch (e: Exception) {
                BundledPack(emptySet(), "")
            }
            pack = p
            return p
        }

        /**
         * Прошлые версии хранили свои вырезки в полном разрешении экрана. В рабочем масштабе они
         * не совпадут, поэтому убираем их в templates_v1, а работаем на встроенном наборе.
         */
        fun migrateOwnTemplates(ctx: Context) {
            val root = ctx.getExternalFilesDir(null) ?: return
            val dir = File(root, "templates").apply { mkdirs() }
            if (File(dir, MARKER).exists()) return
            val old = dir.listFiles { f -> f.isFile && f.name.endsWith(".png", true) }.orEmpty()
            if (old.isNotEmpty()) {
                val backup = File(root, "templates_v1").apply { mkdirs() }
                old.forEach { f -> f.renameTo(File(backup, f.name)) }
                BotState.log("Старые вырезки (${old.size}) убраны в templates_v1: теперь используются встроенные")
            }
            File(dir, MARKER).writeText("templates are stored at height $WORK_HEIGHT\n")
        }

        /** Приводит вырезку к рабочему масштабу: [sourceHeight] — высота кадра, из которого её вырезали. */
        fun toWorkScale(bmp: Bitmap, sourceHeight: Int): Bitmap {
            if (sourceHeight <= 0) return bmp
            val s = WORK_HEIGHT.toDouble() / sourceHeight
            if (abs(s - 1.0) < 0.01) return bmp
            val w = max(1, (bmp.width * s).roundToInt())
            val h = max(1, (bmp.height * s).roundToInt())
            return Bitmap.createScaledBitmap(bmp, w, h, true)
        }
    }

    private val lock = Any()
    private val tplCache = HashMap<String, Mat?>()
    private var variantCache: Map<String, List<String>>? = null

    // Последний подготовленный кадр: один кадр обычно проверяют на несколько шаблонов подряд.
    private var prepRef: Bitmap? = null
    private var prepGen = -1
    private var prepMat: Mat? = null
    private var prepScale = 1.0

    val templatesDir: File
        get() = File(context.getExternalFilesDir(null), "templates").apply { mkdirs() }

    val screensDir: File
        get() = File(context.getExternalFilesDir(null), "screens").apply { mkdirs() }

    private fun ownNames(): List<String> =
        templatesDir.listFiles { f -> f.isFile && f.name.endsWith(".png", true) }
            ?.map { it.nameWithoutExtension }
            .orEmpty()

    /** Есть своя вырезка пользователя. */
    fun hasOwn(name: String): Boolean = File(templatesDir, "$name.png").exists()

    /** Есть встроенная вырезка в APK. */
    fun isBundled(name: String): Boolean = variants(name).any { it in pack(context).names }

    fun has(name: String): Boolean = variants(name).isNotEmpty()

    fun bundledCount(): Int = pack(context).names.map { it.substringBefore('~') }.distinct().size

    fun bundledLang(): String = pack(context).lang

    fun availableTemplates(): List<String> =
        (ownNames() + pack(context).names).map { it.substringBefore('~') }.distinct().sorted()

    /** Все варианты шаблона: сам файл и `имя~N`. Свои вырезки перекрывают встроенные с тем же именем. */
    private fun variants(name: String): List<String> = synchronized(lock) {
        val map = variantCache ?: run {
            val all = (ownNames() + pack(context).names).distinct()
            all.groupBy { it.substringBefore('~') }.also { variantCache = it }
        }
        map[name].orEmpty()
    }

    /** Каких шаблонов не хватает для включённых в настройках функций. */
    fun missingFor(cfg: BotConfig): List<String> {
        val need = CORE.toMutableList()
        if (cfg.autoUpgrade) {
            need += if (cfg.upgradeTargets.isEmpty()) {
                listOf("builder", "builder_free", "can_upgrade", "upgrade", "confirm", "not_enough", "close")
            } else {
                listOf("upgrade", "confirm", "not_enough", "close")
            }
        }
        if (cfg.collectResources) need += "collect"
        if (cfg.trainTroops) need += listOf("train_troops", "quick_train", "train_preset")
        if (cfg.lootFilter) need += DIGITS
        return need.distinct().filter { !has(it) }
    }

    fun clearCache() = synchronized(lock) {
        tplCache.values.forEach { it?.release() }
        tplCache.clear()
        variantCache = null
        prepMat?.release()
        prepMat = null
        prepRef = null
    }

    private fun templateMat(variant: String): Mat? = synchronized(lock) {
        if (tplCache.containsKey(variant)) return@synchronized tplCache[variant]
        val own = File(templatesDir, "$variant.png")
        val bmp = try {
            if (own.exists()) {
                BitmapFactory.decodeFile(own.absolutePath)
            } else if (variant in pack(context).names) {
                context.assets.open("$ASSET_DIR/$variant.png").use { BitmapFactory.decodeStream(it) }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
        val mat = bmp?.let {
            val rgba = Mat()
            Utils.bitmapToMat(it, rgba)
            val bgr = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            rgba.release()
            bgr
        }
        tplCache[variant] = mat
        mat
    }

    /** Кадр в рабочем масштабе и коэффициент «рабочий / настоящий». */
    private fun prepare(screen: Bitmap): Pair<Mat, Double> = synchronized(lock) {
        val cached = prepMat
        if (cached != null && prepRef === screen && prepGen == screen.generationId) {
            return@synchronized cached to prepScale
        }
        prepMat?.release()
        val rgba = Mat()
        Utils.bitmapToMat(screen, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        val s = WORK_HEIGHT.toDouble() / screen.height
        val out = if (abs(s - 1.0) < 0.01) {
            bgr
        } else {
            val r = Mat()
            val w = max(1, (screen.width * s).roundToInt())
            Imgproc.resize(bgr, r, Size(w.toDouble(), WORK_HEIGHT.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            bgr.release()
            r
        }
        prepRef = screen
        prepGen = screen.generationId
        prepMat = out
        prepScale = s
        out to s
    }

    /** Лучшее совпадение, если оно не хуже порога. */
    fun find(screen: Bitmap, name: String): Match? {
        val m = best(screen, name) ?: return null
        return if (m.score >= threshold()) m else null
    }

    /** Лучшее совпадение среди всех вариантов шаблона независимо от порога (для отладки). */
    fun best(screen: Bitmap, name: String): Match? =
        search(screen, name, maxCount = 1, minScore = -1.0, roi = null).maxByOrNull { it.score }

    /** Все совпадения выше порога, от лучшего к худшему. */
    fun findAll(screen: Bitmap, name: String, maxCount: Int = 16, minScore: Double = threshold()): List<Match> =
        search(screen, name, maxCount, minScore, roi = null)

    /** Самое левое из найденных совпадений: так отличаем обычный «Подбор» от рангового справа. */
    fun findLeftmost(screen: Bitmap, name: String, minScore: Double = threshold()): Match? =
        findAll(screen, name, maxCount = 6, minScore = minScore).minByOrNull { it.x }

    /** Поиск в прямоугольной области экрана; координаты результата — в пикселях всего экрана. */
    fun findAllIn(
        screen: Bitmap,
        rect: RectN,
        name: String,
        maxCount: Int = 16,
        minScore: Double = threshold(),
    ): List<Match> = search(screen, name, maxCount, minScore, rect)

    private fun search(screen: Bitmap, name: String, maxCount: Int, minScore: Double, roi: RectN?): List<Match> {
        if (!ensureLoaded()) return emptyList()
        val names = variants(name)
        if (names.isEmpty()) return emptyList()
        return try {
            val (src, s) = prepare(screen)
            val out = ArrayList<Match>()
            for (v in names) {
                val tpl = templateMat(v) ?: continue
                out += matchMany(src, tpl, maxCount, minScore, roi, s)
            }
            out.sortedByDescending { it.score }.take(maxCount)
        } catch (e: Exception) {
            BotState.log("Ошибка распознавания: ${e.message}")
            emptyList()
        }
    }

    private fun matchMany(src: Mat, t: Mat, maxCount: Int, minScore: Double, roi: RectN?, s: Double): List<Match> {
        var ox = 0
        var oy = 0
        var area = src
        var sub: Mat? = null
        if (roi != null && !roi.isEmpty) {
            ox = (roi.left * src.cols()).toInt().coerceIn(0, src.cols() - 1)
            oy = (roi.top * src.rows()).toInt().coerceIn(0, src.rows() - 1)
            val w = ((roi.right - roi.left) * src.cols()).toInt().coerceIn(1, src.cols() - ox)
            val h = ((roi.bottom - roi.top) * src.rows()).toInt().coerceIn(1, src.rows() - oy)
            sub = src.submat(Rect(ox, oy, w, h))
            area = sub
        }
        if (t.cols() > area.cols() || t.rows() > area.rows()) {
            sub?.release()
            return emptyList()
        }
        val result = Mat()
        try {
            Imgproc.matchTemplate(area, t, result, Imgproc.TM_CCOEFF_NORMED)
            val out = ArrayList<Match>(maxCount)
            repeat(maxCount) {
                val mm = Core.minMaxLoc(result)
                if (mm.maxVal < minScore) return out
                val x = mm.maxLoc.x.toInt()
                val y = mm.maxLoc.y.toInt()
                out += Match(
                    x = ((x + ox) / s).roundToInt(),
                    y = ((y + oy) / s).roundToInt(),
                    w = (t.cols() / s).roundToInt(),
                    h = (t.rows() / s).roundToInt(),
                    score = mm.maxVal,
                )
                if (out.size >= maxCount) return out
                // Гасим окрестность найденного, чтобы следующий максимум был другим объектом.
                val half = 0.6
                Imgproc.rectangle(
                    result,
                    Point(x - t.cols() * half, y - t.rows() * half),
                    Point(x + t.cols() * half, y + t.rows() * half),
                    Scalar(-1.0),
                    -1
                )
            }
            return out
        } finally {
            result.release()
            sub?.release()
        }
    }
}
