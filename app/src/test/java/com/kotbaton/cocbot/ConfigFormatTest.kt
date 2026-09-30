package com.kotbaton.cocbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Проверяем разбор текстовых полей и сохранение шаблонов: на этом держатся все настройки. */
class ConfigFormatTest {

    @Test
    fun `отрезок читается и записывается без потерь`() {
        val e = Edge.parse("0.140,0.450,0.460,0.120")
        assertNotNull(e)
        assertEquals(0.14f, e!!.x1, 0.0001f)
        assertEquals(0.12f, e.y2, 0.0001f)
        assertEquals(e, Edge.parse(e.toString()))
    }

    @Test
    fun `точка на отрезке считается по доле`() {
        val e = Edge(0f, 0f, 1f, 0.5f)
        val mid = e.at(0.5f)
        assertEquals(0.5f, mid.x, 0.0001f)
        assertEquals(0.25f, mid.y, 0.0001f)
    }

    @Test
    fun `область помнит углы и пустоту`() {
        val r = RectN.parse("0.8,0.1,0.9,0.16")
        assertNotNull(r)
        assertEquals(0.8f, r!!.left, 0.0001f)
        assertEquals(0.16f, r.bottom, 0.0001f)
        assertTrue(RectN.NONE.isEmpty)
        assertTrue(!r.isEmpty)
    }

    @Test
    fun `шаг высадки читается в формате карточка двоеточие количество двоеточие края`() {
        val s = DeployStep.parse("1:40:1,2,3,4:500")
        assertNotNull(s)
        assertEquals(0, s!!.slot)
        assertEquals(40, s.count)
        assertEquals(listOf(0, 1, 2, 3), s.edges)
        assertEquals(500L, s.delayAfterMs)
    }

    @Test
    fun `шаг без паузы получает значение по умолчанию и переживает запись`() {
        val s = DeployStep.parse("3:12:2")
        assertNotNull(s)
        assertEquals(2, s!!.slot)
        assertEquals(listOf(1), s.edges)
        assertEquals(s, DeployStep.parse(s.toString()))
    }

    @Test
    fun `битые шаги отбрасываются`() {
        assertNull(DeployStep.parse("1:40"))
        assertNull(DeployStep.parse("нет:40:1"))
        assertNull(DeployStep.parse("1:0:1"))
        assertNull(DeployStep.parse("0:10:1"))
        assertNull(DeployStep.parse(""))
    }

    @Test
    fun `шаблон атаки переживает запись в JSON и чтение обратно`() {
        for (original in AttackPreset.builtIn()) {
            val restored = AttackPreset.fromJson(original.toJson())
            assertEquals(original.name, restored.name)
            assertEquals(original.edges, restored.edges)
            assertEquals(original.steps.map { it.toString() }, restored.steps.map { it.toString() })
            assertEquals(original.heroSlots, restored.heroSlots)
            assertEquals(original.heroEdges, restored.heroEdges)
            assertEquals(original.spellSlots, restored.spellSlots)
            assertEquals(original.spellPoints, restored.spellPoints)
            assertEquals(original.spellDelaySec, restored.spellDelaySec)
            assertEquals(original.pointsPerEdge, restored.pointsPerEdge)
            assertEquals(original.tapIntervalMs, restored.tapIntervalMs)
            assertEquals(original.abilityCards, restored.abilityCards)
        }
    }

    @Test
    fun `универсальный шаблон идёт первым и не требует знать армию`() {
        val first = AttackPreset.builtIn().first()
        assertEquals(AttackPreset.UNIVERSAL, first.name)
        assertTrue(first.heroSlots.isEmpty())
        assertTrue(first.spellSlots.isEmpty())
        assertTrue(first.abilityCards > 0)
        assertTrue(first.steps.all { it.edges.size == first.edges.size })
        assertTrue(AttackPreset.UNIVERSAL !in AttackPreset.LEGACY_BUILT_IN)
    }

    @Test
    fun `шаг берёт только существующие края`() {
        val preset = AttackPreset(edges = mutableListOf(Edge(0f, 0f, 1f, 1f)))
        val step = DeployStep(0, 5, listOf(0, 7))
        assertEquals(1, preset.edgesOf(step).size)
        val onlyBad = DeployStep(0, 5, listOf(9))
        assertEquals(preset.edges, preset.edgesOf(onlyBad))
    }

    @Test
    fun `копия шаблона независима от оригинала`() {
        val original = AttackPreset.builtIn().first()
        val copy = original.copyWithName("Копия")
        copy.steps.add(DeployStep(5, 1, listOf(0)))
        copy.edges.add(Edge(0f, 0f, 1f, 1f))
        assertEquals("Копия", copy.name)
        assertTrue(copy.steps.size != original.steps.size)
        assertTrue(copy.edges.size != original.edges.size)
    }

    @Test
    fun `карточка войск считается по формуле пока нет точных точек`() {
        val cfg = BotConfig()
        cfg.slotFirstX = 0.1f
        cfg.slotStepX = 0.05f
        assertEquals(0.1f, cfg.slotPoint(0).x, 0.0001f)
        assertEquals(0.2f, cfg.slotPoint(2).x, 0.0001f)
        cfg.slotPoints = mutableListOf(PointN(0.3f, 0.9f))
        assertEquals(0.3f, cfg.slotPoint(0).x, 0.0001f)
        assertEquals(0.15f, cfg.slotPoint(1).x, 0.0001f)
    }

    @Test
    fun `настройки переживают запись в JSON`() {
        val cfg = BotConfig()
        cfg.presetName = "Фарм"
        cfg.fullDark = true
        cfg.minGold = 400_000
        val json = cfg.toJson()
        assertEquals("Фарм", json.getString("presetName"))
        assertTrue(json.getBoolean("fullDark"))
        assertTrue(json.getBoolean("stopWhenFull"))
        assertEquals(400_000, json.getInt("minGold"))
    }

    @Test
    fun `фильтр добычи пропускает бедные базы и не блокирует нераспознанное`() {
        val cfg = BotConfig()
        cfg.minGold = 300_000
        cfg.minElixir = 300_000
        cfg.minDark = 5_000
        assertTrue(Loot(351_561, 444_968, 8_884).passes(cfg))
        assertTrue(!Loot(63_881, 271_936, 524).passes(cfg))
        assertTrue(!Loot(400_000, 250_000, 9_000).passes(cfg))
        assertTrue(!Loot(400_000, 400_000, 2_000).passes(cfg))
        // Число не прочиталось: базу не отбрасываем, иначе бот мог бы вечно пропускать хорошие.
        assertTrue(Loot(-1, 400_000, 9_000).passes(cfg))
        cfg.minDark = 0
        assertTrue(Loot(400_000, 400_000, 0).passes(cfg))
    }

    @Test
    fun `края по границе карты не вылезают за экран и обходят нижние кнопки`() {
        for (aspect in listOf(1.78f, 2.0f, 2.22f, 2.4f)) {
            val edges = AttackPreset.mapEdges(aspect)
            assertEquals(4, edges.size)
            for (e in edges) {
                for (t in listOf(0f, 0.5f, 1f)) {
                    val p = e.at(t)
                    assertTrue("x=${p.x} при $aspect", p.x in 0f..1f)
                    assertTrue("y=${p.y} при $aspect", p.y in 0.05f..0.67f)
                }
            }
        }
    }

    @Test
    fun `шаблон без настройки из прошлой сборки получает автокрая`() {
        val old = AttackPreset.universal().toJson()
        old.remove("autoEdges")
        assertTrue(AttackPreset.fromJson(old).autoEdges)
        val other = AttackPreset.builtIn()[1].toJson()
        other.remove("autoEdges")
        assertTrue(!AttackPreset.fromJson(other).autoEdges)
    }
}
