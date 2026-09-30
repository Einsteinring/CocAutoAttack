package com.kotbaton.cocbot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Общие настройки бота: геометрия панели войск, что делать между боями,
 * фильтр добычи и цели авто-прокачки. Хранятся в SharedPreferences как JSON.
 */
class BotConfig {
    /** Имя активного шаблона атаки. */
    var presetName = ""

    /** Сколько атак сделать, затем остановиться. */
    var maxAttacks = 20

    /** Порог совпадения шаблона (0..1). */
    var threshold = 0.80

    /** Через сколько секунд после высадки сдаться, если бой не закончился сам. */
    var battleTimeoutSec = 200

    /** Панель войск: Y всех слотов, X первого слота и шаг между слотами (доли экрана). */
    var slotY = 0.93f
    var slotFirstX = 0.06f
    var slotStepX = 0.08f

    /** Точные точки карточек, снятые со скриншота. Если список не пуст, формула выше не используется. */
    var slotPoints: MutableList<PointN> = mutableListOf()

    /** Сколько карточек рисовать в разметке. */
    var slotCount = 8

    // --- Между боями ---

    /** Собирать ресурсы со сборщиков перед атакой. */
    var collectResources = true

    /** Авто-прокачка стен. */
    var autoUpgrade = true

    /** Прокачивать раз в N атак. */
    var upgradeEveryAttacks = 1


    /** Остановиться, когда хранилища заполнены. Действует, когда стены больше не улучшаются. */
    var stopWhenFull = true

    /** Какие хранилища должны быть полными. Чёрный эликсир копится медленно, по умолчанию не ждём его. */
    var fullGold = true
    var fullElixir = true
    var fullDark = false

    // --- Фильтр добычи ---

    /** Пропускать базы, пока добыча меньше порогов. */
    var lootFilter = false
    var minGold = 300_000
    var minElixir = 300_000
    var minDark = 0
    var maxSkips = 25


    /** Сколько баз пропустить вслепую, когда фильтр выключен. */
    var skipBases = 0

    // --- Поведение ---

    /** Случайно растягивать паузы, чтобы действия были менее машинными. */
    var humanize = true

    /** Пауза между атаками, секунды (нижняя и верхняя границы). */
    var pauseBetweenMinSec = 2
    var pauseBetweenMaxSec = 8

    /** Если экран базы не найден, перезапустить игру, а не останавливаться. */
    var restartGameOnStuck = true

    fun slotPoint(index: Int): PointN =
        slotPoints.getOrNull(index) ?: PointN(slotFirstX + slotStepX * index, slotY)

    fun toJson(): JSONObject = JSONObject().apply {
        put("presetName", presetName)
        put("maxAttacks", maxAttacks)
        put("threshold", threshold)
        put("battleTimeoutSec", battleTimeoutSec)
        put("slotY", slotY.toDouble())
        put("slotFirstX", slotFirstX.toDouble())
        put("slotStepX", slotStepX.toDouble())
        put("slotCount", slotCount)
        put("slotPoints", JSONArray().apply { slotPoints.forEach { put(it.toString()) } })
        put("collectResources", collectResources)
        put("autoUpgrade", autoUpgrade)
        put("upgradeEveryAttacks", upgradeEveryAttacks)
        put("stopWhenFull", stopWhenFull)
        put("fullGold", fullGold)
        put("fullElixir", fullElixir)
        put("fullDark", fullDark)
        put("lootFilter", lootFilter)
        put("minGold", minGold)
        put("minElixir", minElixir)
        put("minDark", minDark)
        put("maxSkips", maxSkips)
        put("skipBases", skipBases)
        put("humanize", humanize)
        put("pauseBetweenMinSec", pauseBetweenMinSec)
        put("pauseBetweenMaxSec", pauseBetweenMaxSec)
        put("restartGameOnStuck", restartGameOnStuck)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, toJson().toString())
            .apply()
    }

    companion object {
        private const val PREFS = "cocbot"
        private const val KEY = "config"

        private fun points(j: JSONObject, key: String): MutableList<PointN> {
            val arr = j.optJSONArray(key) ?: return mutableListOf()
            return (0 until arr.length()).mapNotNull { PointN.parse(arr.optString(it)) }.toMutableList()
        }

        fun load(ctx: Context): BotConfig {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            val c = BotConfig()
            if (raw.isNullOrBlank()) return c
            try {
                val j = JSONObject(raw)
                c.presetName = j.optString("presetName", c.presetName)
                c.maxAttacks = j.optInt("maxAttacks", c.maxAttacks)
                c.threshold = j.optDouble("threshold", c.threshold)
                c.battleTimeoutSec = j.optInt("battleTimeoutSec", c.battleTimeoutSec)
                c.slotY = j.optDouble("slotY", c.slotY.toDouble()).toFloat()
                c.slotFirstX = j.optDouble("slotFirstX", c.slotFirstX.toDouble()).toFloat()
                c.slotStepX = j.optDouble("slotStepX", c.slotStepX.toDouble()).toFloat()
                c.slotCount = j.optInt("slotCount", c.slotCount)
                c.slotPoints = points(j, "slotPoints")
                c.collectResources = j.optBoolean("collectResources", c.collectResources)
                c.autoUpgrade = j.optBoolean("autoUpgrade", c.autoUpgrade)
                c.upgradeEveryAttacks = j.optInt("upgradeEveryAttacks", c.upgradeEveryAttacks)
                c.stopWhenFull = j.optBoolean("stopWhenFull", c.stopWhenFull)
                c.fullGold = j.optBoolean("fullGold", c.fullGold)
                c.fullElixir = j.optBoolean("fullElixir", c.fullElixir)
                c.fullDark = j.optBoolean("fullDark", c.fullDark)
                c.lootFilter = j.optBoolean("lootFilter", c.lootFilter)
                c.minGold = j.optInt("minGold", c.minGold)
                c.minElixir = j.optInt("minElixir", c.minElixir)
                c.minDark = j.optInt("minDark", c.minDark)
                c.maxSkips = j.optInt("maxSkips", c.maxSkips)
                c.skipBases = j.optInt("skipBases", c.skipBases)
                c.humanize = j.optBoolean("humanize", c.humanize)
                c.pauseBetweenMinSec = j.optInt("pauseBetweenMinSec", c.pauseBetweenMinSec)
                c.pauseBetweenMaxSec = j.optInt("pauseBetweenMaxSec", c.pauseBetweenMaxSec)
                c.restartGameOnStuck = j.optBoolean("restartGameOnStuck", c.restartGameOnStuck)
            } catch (e: Exception) {
                BotState.log("Не удалось прочитать настройки: ${e.message}")
            }
            return c
        }
    }
}
