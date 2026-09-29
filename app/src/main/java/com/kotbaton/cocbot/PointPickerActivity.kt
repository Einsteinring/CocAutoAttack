package com.kotbaton.cocbot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kotbaton.cocbot.databinding.ActivityPointPickerBinding
import java.io.File

/**
 * Выбор точек прямо по последнему скриншоту игры: карточки войск, края высадки,
 * цели прокачки, области с числами добычи.
 */
class PointPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_TITLE = "title"
        const val EXTRA_HINT = "hint"
        const val EXTRA_INITIAL = "initial"
        const val EXTRA_MAX = "max"
        const val RESULT_POINTS = "points"

        fun intent(
            ctx: Context,
            mode: PickMode,
            title: String,
            hint: String,
            initial: List<String> = emptyList(),
            max: Int = 64,
        ): Intent = Intent(ctx, PointPickerActivity::class.java)
            .putExtra(EXTRA_MODE, mode.name)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_HINT, hint)
            .putStringArrayListExtra(EXTRA_INITIAL, ArrayList(initial))
            .putExtra(EXTRA_MAX, max)

        /** Самый свежий скриншот из папки приложения. */
        fun latestScreenshot(ctx: Context): File? =
            File(ctx.getExternalFilesDir(null), "screens")
                .listFiles { f -> f.isFile && f.name.endsWith(".png", true) }
                ?.maxByOrNull { it.lastModified() }
    }

    private lateinit var b: ActivityPointPickerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPointPickerBinding.inflate(layoutInflater)
        setContentView(b.root)

        val mode = runCatching { PickMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: "POINTS") }
            .getOrDefault(PickMode.POINTS)
        title = intent.getStringExtra(EXTRA_TITLE) ?: "Выбор точек"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.tvTitle.text = title
        b.tvHint.text = intent.getStringExtra(EXTRA_HINT) ?: ""

        val shot = latestScreenshot(this)
        if (shot == null) {
            Toast.makeText(this, "Сначала сделайте скриншот игры кнопкой «Скриншот»", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val bmp = BitmapFactory.decodeFile(shot.absolutePath)
        if (bmp == null) {
            Toast.makeText(this, "Не удалось открыть скриншот", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        b.picker.mode = mode
        b.picker.maxPoints = intent.getIntExtra(EXTRA_MAX, 64)
        b.picker.setBitmap(bmp)
        b.picker.setPoints(
            intent.getStringArrayListExtra(EXTRA_INITIAL).orEmpty().mapNotNull { PointN.parse(it) }
        )
        b.picker.onChanged = { list -> b.tvCount.text = "Точек: ${list.size}" }
        b.tvCount.text = "Точек: ${b.picker.getPoints().size}"

        b.btnUndo.setOnClickListener { b.picker.undo() }
        b.btnClear.setOnClickListener { b.picker.clearPoints() }
        b.btnCancel.setOnClickListener { finish() }
        b.btnDone.setOnClickListener {
            val points = b.picker.getPoints()
            if (mode == PickMode.PAIRS && points.size % 2 != 0) {
                Toast.makeText(this, "Для краёв нужны пары точек: начало и конец", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (mode == PickMode.RECT && points.size != 2) {
                Toast.makeText(this, "Отметьте два угла области", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val data = Intent().putStringArrayListExtra(
                RESULT_POINTS,
                ArrayList(points.map { it.toString() })
            )
            setResult(Activity.RESULT_OK, data)
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
