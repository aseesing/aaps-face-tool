package app.aaps.facetool.editor.server

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import app.aaps.facetool.editor.model.wrongUse
import java.io.File

/** A box in pixels of a picture, with the text to write at it. */
class LabelBox(val label: String, val left: Int, val top: Int, val width: Int, val height: Int)

/** Small drawing helpers for the pictures the editor answers. They draw on top of the engine's picture, never in its place. */
class Pictures {

    fun save(bitmap: Bitmap, file: File) {
        file.absoluteFile.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A copy of [bitmap] that can be drawn on. */
    fun editable(bitmap: Bitmap): Bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

    /** Draws the grid of the 400 design units (a line every 50, the middle lines stronger) and the round edge. */
    fun grid(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        val size = bitmap.width.toFloat()
        val unit = size / DESIGN
        val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 255, 255, 255); strokeWidth = 1f; style = Paint.Style.STROKE }
        val strong = Paint(thin).apply { color = Color.argb(200, 255, 220, 0) }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 255, 255, 255); textSize = 9f * unit }
        for (at in 0..DESIGN step 50) {
            val p = at * unit
            val paint = if (at == DESIGN / 2) strong else thin
            canvas.drawLine(p, 0f, p, size, paint)
            canvas.drawLine(0f, p, size, p, paint)
            if (at in 1 until DESIGN) {
                canvas.drawText(at.toString(), p + 2f, DESIGN / 2 * unit - 2f, text)
                canvas.drawText(at.toString(), DESIGN / 2 * unit + 2f, p - 2f, text)
            }
        }
        canvas.drawCircle(size / 2, size / 2, size / 2 - 0.5f, strong)
    }

    /** Draws each box of [boxes] with its label. */
    fun annotate(bitmap: Bitmap, boxes: List<LabelBox>) {
        val canvas = Canvas(bitmap)
        val unit = bitmap.width.toFloat() / DESIGN
        boxes.forEachIndexed { i, box ->
            val colour = COLOURS[i % COLOURS.size]
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour; strokeWidth = 1.5f; style = Paint.Style.STROKE }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour; textSize = 10f * unit; isFakeBoldText = true }
            val back = Paint().apply { color = Color.argb(170, 0, 0, 0) }
            canvas.drawRect(box.left + 0.5f, box.top + 0.5f, box.left + box.width - 0.5f, box.top + box.height - 0.5f, line)
            val bounds = Rect().also { text.getTextBounds(box.label, 0, box.label.length, it) }
            val x = box.left.toFloat().coerceIn(0f, (bitmap.width - bounds.width() - 4).toFloat().coerceAtLeast(0f))
            val y = (box.top + bounds.height() + 3).toFloat().coerceAtMost(bitmap.height.toFloat())
            canvas.drawRect(x, y - bounds.height() - 3, x + bounds.width() + 4, y + 1, back)
            canvas.drawText(box.label, x + 2, y - 1, text)
        }
    }

    /** The part `x,y,w,h` (design units) of [bitmap]. */
    fun zoom(bitmap: Bitmap, box: String): Bitmap {
        val numbers = box.split(",").map { it.trim().toDoubleOrNull() ?: wrongUse("--zoom needs x,y,w,h in design units, for example 100,50,200,120") }
        if (numbers.size != 4) wrongUse("--zoom needs x,y,w,h in design units, for example 100,50,200,120")
        val unit = bitmap.width.toDouble() / DESIGN
        val left = (numbers[0] * unit).toInt().coerceIn(0, bitmap.width - 1)
        val top = (numbers[1] * unit).toInt().coerceIn(0, bitmap.height - 1)
        val width = (numbers[2] * unit).toInt().coerceIn(1, bitmap.width - left)
        val height = (numbers[3] * unit).toInt().coerceIn(1, bitmap.height - top)
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    /**
     * One sheet of [frames] with the text of [labels] under each, [columns] wide. Every frame is
     * drawn [cell] pixels wide and high.
     */
    fun sheet(frames: List<Bitmap>, labels: List<String>, columns: Int, cell: Int): Bitmap {
        val rows = (frames.size + columns - 1) / columns
        val labelHeight = (cell / 9).coerceAtLeast(14)
        val sheet = Bitmap.createBitmap(columns * cell, rows.coerceAtLeast(1) * (cell + labelHeight), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.rgb(24, 24, 24))
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = labelHeight * 0.7f; textAlign = Paint.Align.CENTER }
        val smooth = Paint(Paint.FILTER_BITMAP_FLAG)
        frames.forEachIndexed { i, frame ->
            val x = (i % columns) * cell
            val y = (i / columns) * (cell + labelHeight)
            canvas.drawBitmap(frame, null, Rect(x, y, x + cell, y + cell), smooth)
            canvas.drawText(labels.getOrElse(i) { "" }, x + cell / 2f, y + cell + labelHeight * 0.75f, text)
        }
        return sheet
    }

    private companion object {

        const val DESIGN = 400
        val COLOURS = listOf(Color.rgb(255, 80, 80), Color.rgb(80, 220, 255), Color.rgb(255, 220, 0), Color.rgb(120, 255, 120), Color.rgb(255, 130, 255), Color.rgb(255, 160, 60))
    }
}
