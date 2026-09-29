package com.kotbaton.cocbot

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.kotbaton.cocbot.databinding.ActivityPresetEditorBinding

/** Редактор одного шаблона атаки. */
class PresetEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "name"
    }

    private enum class Pick { EDGES, SPELLS }

    private lateinit var b: ActivityPresetEditorBinding
    private lateinit var presets: MutableList<AttackPreset>
    private lateinit var preset: AttackPreset
    private var originalName = ""
    private var pending: Pick? = null

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val what = pending
        pending = null
        if (res.resultCode != Activity.RESULT_OK || what == null) return@registerForActivityResult
        val points = res.data?.getStringArrayListExtra(PointPickerActivity.RESULT_POINTS)
            .orEmpty()
            .mapNotNull { PointN.parse(it) }
        when (what) {
            Pick.EDGES -> {
                val edges = ArrayList<Edge>()
                for (i in 0 until points.size - 1 step 2) {
                    edges += Edge(points[i].x, points[i].y, points[i + 1].x, points[i + 1].y)
                }
                if (edges.isEmpty()) {
                    toast("Края не заданы: нужны пары точек")
                } else {
                    b.etEdges.setText(edges.joinToString("\n"))
                    toast("Краёв: ${edges.size}")
                }
            }
            Pick.SPELLS -> {
                b.etSpellPoints.setText(points.joinToString("\n"))
                toast("Точек заклинаний: ${points.size}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPresetEditorBinding.inflate(layoutInflater)
        setContentView(b.root)

        presets = PresetStore.load(this)
        originalName = intent.getStringExtra(EXTRA_NAME) ?: ""
        preset = presets.firstOrNull { it.name == originalName } ?: presets.first()
        originalName = preset.name
        title = "Шаблон: ${preset.name}"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        fillUi()

        b.btnPickEdges.setOnClickListener {
            openPicker(
                Pick.EDGES, PickMode.PAIRS, "Края высадки",
                "Загрузите скриншот чужой базы и отметьте пары точек: начало и конец каждого края.",
                currentEdgesAsPoints()
            )
        }
        b.btnPickSpells.setOnClickListener {
            openPicker(
                Pick.SPELLS, PickMode.POINTS, "Точки заклинаний",
                "Отметьте, куда бросать заклинания. Порядок совпадает с порядком карточек.",
                b.etSpellPoints.text.toString().lines().filter { it.isNotBlank() }
            )
        }
        b.btnSave.setOnClickListener { if (save()) finish() }
    }

    private fun currentEdgesAsPoints(): List<String> {
        val out = ArrayList<String>()
        b.etEdges.text.toString().lines().mapNotNull { Edge.parse(it) }.forEach {
            out += PointN(it.x1, it.y1).toString()
            out += PointN(it.x2, it.y2).toString()
        }
        return out
    }

    private fun openPicker(what: Pick, mode: PickMode, title: String, hint: String, initial: List<String>) {
        if (PointPickerActivity.latestScreenshot(this) == null) {
            toast("Сначала сделайте скриншот нужного экрана игры")
            return
        }
        pending = what
        picker.launch(PointPickerActivity.intent(this, mode, title, hint, initial, 40))
    }

    private fun fillUi() {
        b.etName.setText(preset.name)
        b.etEdges.setText(preset.edges.joinToString("\n"))
        b.etSteps.setText(preset.steps.joinToString("\n"))
        b.etHeroSlots.setText(preset.heroSlots.joinToString(",") { (it + 1).toString() })
        b.etHeroEdges.setText(preset.heroEdges.joinToString(",") { (it + 1).toString() })
        b.cbHeroAbilities.isChecked = preset.useHeroAbilities
        b.etHeroDelay.setText(preset.heroAbilityDelaySec.toString())
        b.etSpellSlots.setText(preset.spellSlots.joinToString(",") { (it + 1).toString() })
        b.etSpellDelay.setText(preset.spellDelaySec.toString())
        b.etSpellPoints.setText(preset.spellPoints.joinToString("\n"))
        b.etPointsPerEdge.setText(preset.pointsPerEdge.toString())
        b.etTapInterval.setText(preset.tapIntervalMs.toString())
    }

    /** Список номеров «5,6» → индексы с нуля. */
    private fun slotList(field: EditText): List<Int> =
        field.text.toString()
            .split(',', ' ', ';')
            .filter { it.isNotBlank() }
            .mapNotNull { it.trim().toIntOrNull()?.minus(1) }
            .filter { it >= 0 }

    private fun EditText.int(name: String, min: Int, max: Int): Int? {
        val v = text.toString().trim().toIntOrNull()
        if (v == null || v < min || v > max) {
            toast("«$name»: введите число от $min до $max")
            return null
        }
        return v
    }

    private fun save(): Boolean {
        val name = b.etName.text.toString().trim()
        if (name.isEmpty()) {
            toast("Введите название шаблона")
            return false
        }
        if (presets.any { it.name == name && it.name != originalName }) {
            toast("Шаблон с таким названием уже есть")
            return false
        }

        val edgeLines = b.etEdges.text.toString().lines().filter { it.isNotBlank() }
        val edges = ArrayList<Edge>(edgeLines.size)
        for (line in edgeLines) {
            val e = Edge.parse(line)
            if (e == null) {
                toast("Не понял край: «$line»")
                return false
            }
            edges += e
        }
        if (edges.isEmpty()) {
            toast("Нужен хотя бы один край высадки")
            return false
        }

        val stepLines = b.etSteps.text.toString().lines().filter { it.isNotBlank() }
        val steps = ArrayList<DeployStep>(stepLines.size)
        for (line in stepLines) {
            val s = DeployStep.parse(line)
            if (s == null) {
                toast("Не понял шаг: «$line» (формат карточка:количество:края)")
                return false
            }
            if (s.edges.any { it >= edges.size }) {
                toast("В шаге «$line» указан край, которого нет (краёв ${edges.size})")
                return false
            }
            steps += s
        }
        if (steps.isEmpty()) {
            toast("Добавьте хотя бы один шаг высадки")
            return false
        }

        val heroEdges = slotList(b.etHeroEdges)
        if (heroEdges.any { it >= edges.size }) {
            toast("У героев указан край, которого нет")
            return false
        }
        val spellPoints = b.etSpellPoints.text.toString().lines()
            .filter { it.isNotBlank() }
            .mapNotNull { PointN.parse(it) }

        preset.name = name
        preset.edges = edges
        preset.steps = steps
        preset.heroSlots = slotList(b.etHeroSlots).toMutableList()
        preset.heroEdges = heroEdges.toMutableList()
        preset.useHeroAbilities = b.cbHeroAbilities.isChecked
        preset.heroAbilityDelaySec = b.etHeroDelay.int("Способности через", 0, 180) ?: return false
        preset.spellSlots = slotList(b.etSpellSlots).toMutableList()
        preset.spellDelaySec = b.etSpellDelay.int("Заклинания через", 0, 180) ?: return false
        preset.spellPoints = spellPoints.toMutableList()
        preset.pointsPerEdge = b.etPointsPerEdge.int("Точек на край", 1, 100) ?: return false
        preset.tapIntervalMs = (b.etTapInterval.int("Интервал тапов", 20, 2000) ?: return false).toLong()

        if (preset.spellSlots.isNotEmpty() && preset.spellPoints.isEmpty()) {
            toast("Для заклинаний отметьте хотя бы одну точку")
            return false
        }

        PresetStore.save(this, presets)
        // Активный шаблон мог быть переименован: чиним ссылку в настройках.
        val cfg = BotConfig.load(this)
        if (cfg.presetName == originalName && originalName != name) {
            cfg.presetName = name
            cfg.save(this)
        }
        toast("Шаблон сохранён")
        return true
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
