package com.kotbaton.cocbot

import android.app.Activity
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kotbaton.cocbot.databinding.ActivityPresetEditorBinding
import com.kotbaton.cocbot.databinding.DialogStepBinding
import com.kotbaton.cocbot.databinding.ItemEdgeBinding
import com.kotbaton.cocbot.databinding.ItemStepBinding
import java.util.Locale

/**
 * Редактор одного шаблона атаки. Края, шаги и точки заклинаний живут в черновике
 * и попадают в шаблон только по «Сохранить» в панели сверху.
 */
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

    private val edges = mutableListOf<Edge>()
    private val steps = mutableListOf<DeployStep>()
    private val spellPoints = mutableListOf<PointN>()

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val what = pending
        pending = null
        if (res.resultCode != Activity.RESULT_OK || what == null) return@registerForActivityResult
        val points = res.data?.getStringArrayListExtra(PointPickerActivity.RESULT_POINTS)
            .orEmpty()
            .mapNotNull { PointN.parse(it) }
        when (what) {
            Pick.EDGES -> {
                val picked = ArrayList<Edge>()
                for (i in 0 until points.size - 1 step 2) {
                    picked += Edge(points[i].x, points[i].y, points[i + 1].x, points[i + 1].y)
                }
                if (picked.isEmpty()) {
                    toast("Края не заданы: нужны пары точек")
                } else {
                    edges.clear()
                    edges += picked
                    renderEdges()
                    toast("Краёв: ${picked.size}")
                }
            }
            Pick.SPELLS -> {
                spellPoints.clear()
                spellPoints += points
                renderSpellPoints()
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
        title = "Шаблон атаки"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        edges += preset.edges
        steps += preset.steps
        spellPoints += preset.spellPoints
        fillUi()

        b.swAutoEdges.setOnCheckedChangeListener { _, on -> showEdgesMode(on) }
        b.btnPickEdges.setOnClickListener {
            openPicker(
                Pick.EDGES, PickMode.PAIRS, "Края высадки",
                "Загрузите скриншот чужой базы и отметьте пары точек: начало и конец каждого края.",
                edges.flatMap { listOf(PointN(it.x1, it.y1).toString(), PointN(it.x2, it.y2).toString()) }
            )
        }
        b.btnPickSpells.setOnClickListener {
            openPicker(
                Pick.SPELLS, PickMode.POINTS, "Точки заклинаний",
                "Отметьте, куда бросать заклинания. Порядок совпадает с порядком карточек.",
                spellPoints.map { it.toString() }
            )
        }
        b.btnAddStep.setOnClickListener { editStep(null) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_save) {
            if (save()) finish()
            return true
        }
        return super.onOptionsItemSelected(item)
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
        b.swAutoEdges.isChecked = preset.autoEdges
        showEdgesMode(preset.autoEdges)
        renderEdges()
        renderSteps()
        renderSpellPoints()
        b.etHeroSlots.setText(preset.heroSlots.joinToString(", ") { (it + 1).toString() })
        b.etHeroEdges.setText(preset.heroEdges.joinToString(", ") { (it + 1).toString() })
        b.cbHeroAbilities.isChecked = preset.useHeroAbilities
        b.etHeroDelay.setText(preset.heroAbilityDelaySec.toString())
        b.etSpellSlots.setText(preset.spellSlots.joinToString(", ") { (it + 1).toString() })
        b.etSpellDelay.setText(preset.spellDelaySec.toString())
        b.etPointsPerEdge.setText(preset.pointsPerEdge.toString())
        b.etTapInterval.setText(preset.tapIntervalMs.toString())
    }

    private fun showEdgesMode(auto: Boolean) {
        b.groupEdges.visibility = if (auto) View.GONE else View.VISIBLE
        b.tvEdgesHint.text = if (auto) {
            "Края считаются сами по границе карты под ваш экран"
        } else {
            "Выключено: края заданы вручную, ниже"
        }
    }

    /** Сколько краёв доступно шагам: автокрая всегда четыре, ручные — сколько нарисовано. */
    private fun edgeCount(): Int =
        if (b.swAutoEdges.isChecked) AttackPreset.mapEdges(2f).size else edges.size

    private fun renderEdges() {
        b.edgesContainer.removeAllViews()
        if (edges.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Краёв нет. Нарисуйте их на скриншоте."
                setTextColor(ContextCompat.getColor(this@PresetEditorActivity, R.color.text_3))
                textSize = 13f
                setPadding(0, dp(10), 0, dp(10))
            }
            b.edgesContainer.addView(empty)
            return
        }
        edges.forEachIndexed { i, e ->
            val row = ItemEdgeBinding.inflate(layoutInflater, b.edgesContainer, false)
            row.tvIndex.text = (i + 1).toString()
            row.tvCoords.text = "${fmt2(e.x1)}, ${fmt2(e.y1)}  →  ${fmt2(e.x2)}, ${fmt2(e.y2)}"
            b.edgesContainer.addView(row.root)
        }
    }

    private fun renderSteps() {
        b.stepsContainer.removeAllViews()
        steps.forEachIndexed { i, s ->
            val row = ItemStepBinding.inflate(layoutInflater, b.stepsContainer, false)
            row.tvSlot.text = (s.slot + 1).toString()
            row.tvTitle.text = "${taps(s.count)} · края ${s.edges.joinToString(", ") { (it + 1).toString() }}"
            row.tvDelay.text = "пауза после ${s.delayAfterMs} мс"
            row.btnEdit.setOnClickListener { editStep(i) }
            row.btnDelete.setOnClickListener {
                steps.removeAt(i)
                renderSteps()
            }
            b.stepsContainer.addView(row.root)
        }
    }

    private fun renderSpellPoints() {
        b.tvSpellPoints.text = if (spellPoints.isEmpty()) "Не отмечены" else "Отмечено: ${spellPoints.size}"
    }

    /** Диалог шага: новый (index == null) или правка существующего. */
    private fun editStep(index: Int?) {
        val d = DialogStepBinding.inflate(layoutInflater)
        val current = index?.let { steps[it] }
        if (current != null) {
            d.etSlot.setText((current.slot + 1).toString())
            d.etCount.setText(current.count.toString())
            d.etEdges.setText(current.edges.joinToString(", ") { (it + 1).toString() })
            d.etDelay.setText(current.delayAfterMs.toString())
        } else {
            d.etSlot.setText((steps.size + 1).coerceAtMost(14).toString())
            d.etCount.setText("20")
            d.etEdges.setText((1..edgeCount()).joinToString(", "))
            d.etDelay.setText("400")
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (current == null) "Новый шаг" else "Шаг ${index!! + 1}")
            .setView(d.root)
            .setNegativeButton("Отмена", null)
            .setPositiveButton(if (current == null) "Добавить" else "Готово", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val step = parseStep(d) ?: return@setOnClickListener
                if (index == null) steps += step else steps[index] = step
                renderSteps()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun parseStep(d: DialogStepBinding): DeployStep? {
        val slot = d.etSlot.int("Карточка", 1, 14) ?: return null
        val count = d.etCount.int("Тапов", 1, 999) ?: return null
        val maxEdge = edgeCount()
        val edgeNumbers = slotList(d.etEdges)
        if (edgeNumbers.isEmpty()) {
            toast("Укажите хотя бы один край")
            return null
        }
        if (edgeNumbers.any { it >= maxEdge }) {
            toast("Такого края нет: краёв всего $maxEdge")
            return null
        }
        val delay = d.etDelay.int("Пауза после шага", 0, 60_000) ?: return null
        return DeployStep(slot - 1, count, edgeNumbers, delay.toLong())
    }

    /** Список номеров «5, 6» → индексы с нуля. */
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

        val auto = b.swAutoEdges.isChecked
        if (!auto && edges.isEmpty()) {
            toast("Нужен хотя бы один край высадки")
            return false
        }
        if (steps.isEmpty()) {
            toast("Добавьте хотя бы один шаг высадки")
            return false
        }
        val maxEdge = edgeCount()
        steps.forEachIndexed { i, s ->
            if (s.edges.any { it >= maxEdge }) {
                toast("В шаге ${i + 1} указан край, которого нет (краёв $maxEdge)")
                return false
            }
        }

        val heroEdges = slotList(b.etHeroEdges)
        if (heroEdges.any { it >= maxEdge }) {
            toast("У героев указан край, которого нет")
            return false
        }

        preset.name = name
        preset.autoEdges = auto
        if (edges.isNotEmpty()) preset.edges = edges.toMutableList()
        preset.steps = steps.toMutableList()
        preset.heroSlots = slotList(b.etHeroSlots).toMutableList()
        preset.heroEdges = heroEdges.toMutableList()
        preset.useHeroAbilities = b.cbHeroAbilities.isChecked
        preset.heroAbilityDelaySec = b.etHeroDelay.int("Способности через", 0, 180) ?: return false
        preset.spellSlots = slotList(b.etSpellSlots).toMutableList()
        preset.spellDelaySec = b.etSpellDelay.int("Бросать через", 0, 180) ?: return false
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

    /** «1 тап», «3 тапа», «40 тапов». */
    private fun taps(n: Int): String {
        val word = when {
            n % 100 in 11..19 -> "тапов"
            n % 10 == 1 -> "тап"
            n % 10 in 2..4 -> "тапа"
            else -> "тапов"
        }
        return "$n $word"
    }

    private fun fmt2(v: Float): String = String.format(Locale.US, "%.2f", v)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
