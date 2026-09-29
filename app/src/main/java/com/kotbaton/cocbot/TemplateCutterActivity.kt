package com.kotbaton.cocbot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kotbaton.cocbot.databinding.ActivityTemplateCutterBinding
import java.io.File
import java.io.FileOutputStream

/** Вырезание шаблона кнопки прямо из снимка экрана игры, без компьютера. */
class TemplateCutterActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "name"
        private const val MIN_SIDE = 10
    }

    private lateinit var b: ActivityTemplateCutterBinding
    private lateinit var vision: Vision
    private var shots: List<File> = emptyList()
    private var index = 0
    private var bitmap: Bitmap? = null
    private var name = "attack"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTemplateCutterBinding.inflate(layoutInflater)
        setContentView(b.root)
        vision = Vision(this) { 0.8 }
        name = intent.getStringExtra(EXTRA_NAME) ?: "attack"
        title = "Вырезать $name.png"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val about = Vision.catalog().firstOrNull { it.name == name }?.about ?: ""
        b.tvTitle.text = "$name.png — $about"
        b.tvHint.text = "Обведите кнопку двумя углами: тапните по одному, потом по другому. " +
            "Точку можно тянуть пальцем, под ним показывается увеличение. Режьте вплотную к краям кнопки."

        shots = vision.screensDir
            .listFiles { f -> f.isFile && f.name.endsWith(".png", true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        if (shots.isEmpty()) {
            Toast.makeText(this, "Снимков экрана нет", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        b.picker.mode = PickMode.RECT
        b.picker.maxPoints = 2
        b.picker.onChanged = { showSelection() }
        showShot(0)

        b.btnPrev.setOnClickListener { showShot(index + 1) }
        b.btnNext.setOnClickListener { showShot(index - 1) }
        b.btnClear.setOnClickListener { b.picker.clearPoints() }
        b.btnSave.setOnClickListener { save() }
    }

    private fun showShot(newIndex: Int) {
        if (shots.isEmpty()) return
        index = ((newIndex % shots.size) + shots.size) % shots.size
        val bmp = BitmapFactory.decodeFile(shots[index].absolutePath)
        if (bmp == null) {
            Toast.makeText(this, "Снимок не открывается", Toast.LENGTH_SHORT).show()
            return
        }
        bitmap = bmp
        b.picker.setBitmap(bmp)
        b.picker.clearPoints()
        b.tvShot.text = "Снимок ${index + 1} из ${shots.size}: ${shots[index].name}, ${bmp.width}×${bmp.height}"
    }

    private fun cropRect(): android.graphics.Rect? {
        val bmp = bitmap ?: return null
        val r = b.picker.asRect() ?: return null
        val x = (r.left * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val y = (r.top * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val w = ((r.right - r.left) * bmp.width).toInt().coerceIn(1, bmp.width - x)
        val h = ((r.bottom - r.top) * bmp.height).toInt().coerceIn(1, bmp.height - y)
        return android.graphics.Rect(x, y, x + w, y + h)
    }

    private fun showSelection() {
        val rect = cropRect()
        if (rect == null) {
            b.tvSelection.text = "Область не выбрана"
            b.ivPreview.setImageDrawable(null)
            return
        }
        b.tvSelection.text = "Область ${rect.width()}×${rect.height()} px"
        val bmp = bitmap ?: return
        if (rect.width() >= MIN_SIDE && rect.height() >= MIN_SIDE) {
            b.ivPreview.setImageBitmap(Bitmap.createBitmap(bmp, rect.left, rect.top, rect.width(), rect.height()))
        } else {
            b.ivPreview.setImageDrawable(null)
        }
    }

    private fun save() {
        val bmp = bitmap ?: return
        val rect = cropRect()
        if (rect == null) {
            Toast.makeText(this, "Сначала обведите кнопку двумя углами", Toast.LENGTH_SHORT).show()
            return
        }
        if (rect.width() < MIN_SIDE || rect.height() < MIN_SIDE) {
            Toast.makeText(this, "Область слишком маленькая", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val crop = Vision.toWorkScale(
                Bitmap.createBitmap(bmp, rect.left, rect.top, rect.width(), rect.height()),
                bmp.height,
            )
            val file = File(vision.templatesDir, "$name.png")
            FileOutputStream(file).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            vision.clearCache()
            BotState.log("Шаблон $name.png вырезан (${rect.width()}×${rect.height()})")
            Toast.makeText(this, "$name.png сохранён", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось сохранить: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
