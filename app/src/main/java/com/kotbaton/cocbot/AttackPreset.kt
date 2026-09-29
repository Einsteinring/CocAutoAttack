package com.kotbaton.cocbot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Один шаг высадки: какую карточку выбрать, сколько тапов сделать и по каким краям.
 * Текстовая запись: `карточка:количество:края[:пауза_мс]`, например `1:40:1,2,3,4:500`.
 * Нумерация карточек и краёв в тексте с единицы, внутри — с нуля.
 */
data class DeployStep(
    val slot: Int,
    val count: Int,
    val edges: List<Int>,
    val delayAfterMs: Long = 400,
) {
    override fun toString(): String {
        val e = edges.joinToString(",") { (it + 1).toString() }
        return "${slot + 1}:$count:$e:$delayAfterMs"
    }

    companion object {
        fun parse(line: String): DeployStep? {
            val parts = line.split(':').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size < 3) return null
            val slot = parts[0].toIntOrNull()?.minus(1) ?: return null
            val count = parts[1].toIntOrNull() ?: return null
            val edges = parts[2].split(',', ' ')
                .filter { it.isNotBlank() }
                .mapNotNull { it.trim().toIntOrNull()?.minus(1) }
            if (slot < 0 || count < 1 || edges.isEmpty() || edges.any { it < 0 }) return null
            val delay = parts.getOrNull(3)?.toLongOrNull() ?: 400L
            return DeployStep(slot, count, edges, delay.coerceIn(0, 60_000))
        }
    }
}

/**
 * Шаблон атаки: где края высадки, в каком порядке и чем высаживаться,
 * когда выпускать героев, когда жать способности и куда кидать заклинания.
 */
data class AttackPreset(
    var name: String = "Новый шаблон",
    var edges: MutableList<Edge> = defaultEdges(),
    var steps: MutableList<DeployStep> = mutableListOf(),
    var heroSlots: MutableList<Int> = mutableListOf(),
    var heroEdges: MutableList<Int> = mutableListOf(),
    var useHeroAbilities: Boolean = true,
    var heroAbilityDelaySec: Int = 12,
    var spellSlots: MutableList<Int> = mutableListOf(),
    var spellPoints: MutableList<PointN> = mutableListOf(),
    var spellDelaySec: Int = 25,
    var pointsPerEdge: Int = 10,
    var tapIntervalMs: Long = 80,
    /**
     * Сколько карточек пройти повторно после высадки, чтобы включить способности героев,
     * не зная, в каких карточках они стоят. Тап по карточке обычного войска ничего не делает.
     */
    var abilityCards: Int = 0,
    /** Края считаются сами по границе карты для пропорций текущего экрана, [edges] не используются. */
    var autoEdges: Boolean = false,
) {
    /** Края для экрана с соотношением сторон [aspect] = ширина / высота. */
    fun effectiveEdges(aspect: Float): List<Edge> = if (autoEdges) mapEdges(aspect) else edges

    fun copyWithName(newName: String): AttackPreset = AttackPreset(
        name = newName,
        edges = edges.toMutableList(),
        steps = steps.toMutableList(),
        heroSlots = heroSlots.toMutableList(),
        heroEdges = heroEdges.toMutableList(),
        useHeroAbilities = useHeroAbilities,
        heroAbilityDelaySec = heroAbilityDelaySec,
        spellSlots = spellSlots.toMutableList(),
        spellPoints = spellPoints.toMutableList(),
        spellDelaySec = spellDelaySec,
        pointsPerEdge = pointsPerEdge,
        tapIntervalMs = tapIntervalMs,
        abilityCards = abilityCards,
        autoEdges = autoEdges,
    )

    /** Короткое описание для списка шаблонов. */
    fun summary(): String {
        val troops = steps.sumOf { it.count }
        val parts = mutableListOf("краёв ${edges.size}", "шагов ${steps.size}", "тапов $troops")
        if (heroSlots.isNotEmpty()) parts += "героев ${heroSlots.size}"
        if (heroSlots.isEmpty() && abilityCards > 0 && useHeroAbilities) parts += "способности сами"
        if (spellSlots.isNotEmpty()) parts += "заклинаний ${spellSlots.size}"
        return parts.joinToString(", ")
    }

    /** Края шага с защитой от индексов, которых нет в списке. */
    fun edgesOf(step: DeployStep): List<Edge> {
        val list = step.edges.mapNotNull { edges.getOrNull(it) }
        return list.ifEmpty { edges }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("edges", JSONArray().apply { edges.forEach { put(it.toString()) } })
        put("steps", JSONArray().apply { steps.forEach { put(it.toString()) } })
        put("heroSlots", JSONArray().apply { heroSlots.forEach { put(it) } })
        put("heroEdges", JSONArray().apply { heroEdges.forEach { put(it) } })
        put("useHeroAbilities", useHeroAbilities)
        put("heroAbilityDelaySec", heroAbilityDelaySec)
        put("spellSlots", JSONArray().apply { spellSlots.forEach { put(it) } })
        put("spellPoints", JSONArray().apply { spellPoints.forEach { put(it.toString()) } })
        put("spellDelaySec", spellDelaySec)
        put("pointsPerEdge", pointsPerEdge)
        put("tapIntervalMs", tapIntervalMs)
        put("abilityCards", abilityCards)
        put("autoEdges", autoEdges)
    }

    companion object {
        fun defaultEdges(): MutableList<Edge> = mutableListOf(
            Edge(0.14f, 0.45f, 0.46f, 0.12f),
            Edge(0.54f, 0.12f, 0.86f, 0.45f),
            Edge(0.86f, 0.55f, 0.62f, 0.80f),
            Edge(0.14f, 0.55f, 0.38f, 0.80f),
        )

        private fun ints(j: JSONObject, key: String): MutableList<Int> {
            val arr = j.optJSONArray(key) ?: return mutableListOf()
            return (0 until arr.length()).map { arr.optInt(it) }.toMutableList()
        }

        fun fromJson(j: JSONObject): AttackPreset {
            val p = AttackPreset()
            p.name = j.optString("name", p.name)
            j.optJSONArray("edges")?.let { arr ->
                val list = (0 until arr.length()).mapNotNull { Edge.parse(arr.optString(it)) }
                if (list.isNotEmpty()) p.edges = list.toMutableList()
            }
            j.optJSONArray("steps")?.let { arr ->
                p.steps = (0 until arr.length()).mapNotNull { DeployStep.parse(arr.optString(it)) }.toMutableList()
            }
            p.heroSlots = ints(j, "heroSlots")
            p.heroEdges = ints(j, "heroEdges")
            p.useHeroAbilities = j.optBoolean("useHeroAbilities", p.useHeroAbilities)
            p.heroAbilityDelaySec = j.optInt("heroAbilityDelaySec", p.heroAbilityDelaySec)
            p.spellSlots = ints(j, "spellSlots")
            j.optJSONArray("spellPoints")?.let { arr ->
                p.spellPoints = (0 until arr.length()).mapNotNull { PointN.parse(arr.optString(it)) }.toMutableList()
            }
            p.spellDelaySec = j.optInt("spellDelaySec", p.spellDelaySec)
            p.pointsPerEdge = j.optInt("pointsPerEdge", p.pointsPerEdge)
            p.tapIntervalMs = j.optLong("tapIntervalMs", p.tapIntervalMs)
            p.abilityCards = j.optInt("abilityCards", p.abilityCards)
            // Шаблон без настройки из прошлой сборки сохранён ещё без автокраёв.
            p.autoEdges = j.optBoolean("autoEdges", p.name == UNIVERSAL)
            return p
        }

        /** Имена шаблонов из первых версий: пользователи их уже видели. */
        val LEGACY_BUILT_IN = setOf(
            "Круг: все стороны",
            "Фарм Barch (две стороны)",
            "Одна сторона (массой)",
            "Воронка + основа",
            "Снос сборщиков (по углам)",
        )

        const val UNIVERSAL = "Всё войско по кругу"

        /**
         * Края вдоль границы карты на экране поиска базы. Сняты со снимка игры: центр карты по центру
         * экрана, полуширина ромба 0.92 высоты экрана, полувысота 0.57. По краю карты всегда есть
         * полоса, где строить нельзя, а высаживать можно, поэтому линии идут чуть внутри границы.
         * Нижние края обрезаны выше кнопок «Далее», «Ускорить армию» и «Закончить сражение».
         */
        fun mapEdges(aspect: Float): List<Edge> {
            val a = if (aspect > 0.5f) aspect else 2.2f
            fun x(dx: Float) = 0.5f + dx / a
            return listOf(
                Edge(x(-0.874f), 0.50f, x(-0.162f), 0.06f),
                Edge(x(0.874f), 0.50f, x(0.162f), 0.06f),
                Edge(x(0.874f), 0.50f, x(0.712f), 0.60f),
                Edge(x(-0.874f), 0.50f, x(-0.615f), 0.66f),
            )
        }

        /**
         * Шаблон без настройки: по очереди берёт каждую карточку и раскладывает её по всем краям.
         * Состав армии знать не нужно. Кончилась карточка — тапы уходят в пустоту или следующему войску.
         */
        fun universal(): AttackPreset = AttackPreset(
            name = UNIVERSAL,
            steps = (0 until 10).map { DeployStep(it, 40, listOf(0, 1, 2, 3), 250) }.toMutableList(),
            useHeroAbilities = true,
            heroAbilityDelaySec = 12,
            pointsPerEdge = 12,
            tapIntervalMs = 70,
            abilityCards = 10,
            autoEdges = true,
        )

        /** Готовые шаблоны, с которыми приложение ставится в первый раз. */
        fun builtIn(): List<AttackPreset> = listOf(
            universal(),
            AttackPreset(
                name = "Круг: все стороны",
                steps = mutableListOf(
                    DeployStep(0, 40, listOf(0, 1, 2, 3)),
                    DeployStep(1, 40, listOf(0, 1, 2, 3)),
                    DeployStep(2, 20, listOf(0, 1, 2, 3)),
                    DeployStep(3, 20, listOf(0, 1, 2, 3)),
                ),
                heroSlots = mutableListOf(4, 5),
                heroEdges = mutableListOf(0, 1),
            ),
            AttackPreset(
                name = "Фарм Barch (две стороны)",
                steps = mutableListOf(
                    DeployStep(0, 30, listOf(0, 1)),
                    DeployStep(1, 40, listOf(0, 1)),
                ),
                heroSlots = mutableListOf(2, 3),
                heroEdges = mutableListOf(0, 1),
                heroAbilityDelaySec = 10,
            ),
            AttackPreset(
                name = "Одна сторона (массой)",
                steps = mutableListOf(
                    DeployStep(0, 20, listOf(0)),
                    DeployStep(1, 40, listOf(0)),
                    DeployStep(2, 20, listOf(0)),
                ),
                heroSlots = mutableListOf(3, 4),
                heroEdges = mutableListOf(0),
                pointsPerEdge = 14,
            ),
            AttackPreset(
                name = "Воронка + основа",
                steps = mutableListOf(
                    DeployStep(0, 4, listOf(0), 1500),
                    DeployStep(0, 4, listOf(1), 2500),
                    DeployStep(1, 30, listOf(0, 1), 800),
                    DeployStep(2, 10, listOf(0, 1)),
                ),
                heroSlots = mutableListOf(3, 4),
                heroEdges = mutableListOf(0, 1),
                heroAbilityDelaySec = 15,
                spellSlots = mutableListOf(5),
                spellPoints = mutableListOf(PointN(0.5f, 0.42f)),
                spellDelaySec = 22,
            ),
            AttackPreset(
                name = "Снос сборщиков (по углам)",
                steps = mutableListOf(
                    DeployStep(0, 8, listOf(0), 300),
                    DeployStep(0, 8, listOf(1), 300),
                    DeployStep(0, 8, listOf(2), 300),
                    DeployStep(0, 8, listOf(3), 300),
                ),
                pointsPerEdge = 4,
            ),
        )
    }
}

/** Хранилище шаблонов атаки в SharedPreferences. */
object PresetStore {
    private const val PREFS = "cocbot"
    private const val KEY = "presets"
    private const val SEEN = "presets_builtin_seen"

    fun load(ctx: Context): MutableList<AttackPreset> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null)
        if (raw.isNullOrBlank()) return AttackPreset.builtIn().toMutableList()
        val list = try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { AttackPreset.fromJson(arr.getJSONObject(it)) }.toMutableList()
        } catch (e: Exception) {
            BotState.log("Не удалось прочитать шаблоны: ${e.message}")
            return AttackPreset.builtIn().toMutableList()
        }
        if (list.isEmpty()) return AttackPreset.builtIn().toMutableList()

        // Встроенные шаблоны из новых версий приложения добавляем в начало, но только один раз:
        // если человек потом удалит такой шаблон, он не вернётся.
        val seen = prefs.getStringSet(SEEN, null) ?: AttackPreset.LEGACY_BUILT_IN
        val fresh = AttackPreset.builtIn().filter { b -> b.name !in seen && list.none { it.name == b.name } }
        if (fresh.isNotEmpty()) {
            list.addAll(0, fresh)
            save(ctx, list)
        }
        if (fresh.isNotEmpty() || !prefs.contains(SEEN)) markSeen(ctx)
        return list
    }

    fun save(ctx: Context, presets: List<AttackPreset>) {
        val arr = JSONArray()
        presets.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }

    private fun markSeen(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(SEEN, AttackPreset.builtIn().map { it.name }.toSet())
            .apply()
    }

    /** Шаблон по имени, иначе первый в списке. */
    fun active(ctx: Context, name: String): AttackPreset {
        val list = load(ctx)
        return list.firstOrNull { it.name == name } ?: list.first()
    }

    fun names(ctx: Context): List<String> = load(ctx).map { it.name }

    fun resetToBuiltIn(ctx: Context): MutableList<AttackPreset> {
        val list = AttackPreset.builtIn().toMutableList()
        save(ctx, list)
        markSeen(ctx)
        return list
    }
}
