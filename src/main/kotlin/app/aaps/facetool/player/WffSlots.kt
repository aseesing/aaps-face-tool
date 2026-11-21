package app.aaps.facetool.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.facetool.snapshot.SnapshotData
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import app.aaps.shared.impl.weardata.ResFileMap
import app.aaps.shared.impl.weardata.toDrawable
import java.io.File

/**
 * What the complication slots of a converted face show in the player (design 11.1), from the values
 * of a scene:
 *
 * - slot 10, the glucose: as `WffGlucoseComplication` of the wear app sends it (the value in mg/dl,
 *   the text of the phone, the delta as title, the arrow as picture);
 * - slot 11, the age of the reading: the minutes, as `WffGlucoseAgeComplication`;
 * - slot 20, the data picture: the picture the engine draws of the data parts of the design;
 * - a slot of the wearer: a plain sample of the source the face asks for by default (heart rate,
 *   steps). These are samples, not the real providers of the watch.
 */
class WffSlots(private val app: Application) {

    /**
     * @param face the face folder, for the default source of each slot
     * @param dataPicture the data picture of the scene, or null when the design has none
     */
    fun of(face: File, design: CwfData, data: SnapshotData, dataPicture: Bitmap?): Map<Int, WffSlotData> {
        val slots = HashMap<Int, WffSlotData>()
        val xml = File(face, "res/raw/watchface.xml").readText()
        for (match in SLOT.findAll(xml)) {
            val id = match.groupValues[1].toIntOrNull() ?: continue
            val policy = POLICY.find(xml, match.range.last)?.value.orEmpty()
            slots[id] = when {
                id == GLUCOSE_SLOT -> glucose(design, data)
                id == AGE_SLOT     -> age(data)
                id == DATA_SLOT    -> if (dataPicture != null) WffSlotData("SMALL_IMAGE", smallImage = dataPicture) else WffSlotData("EMPTY")
                "HEART_RATE" in policy -> WffSlotData("SHORT_TEXT", text = data.heartRate.toString())
                "STEP_COUNT" in policy || "StepsComplication" in policy -> WffSlotData("SHORT_TEXT", text = data.steps.toString())
                else               -> WffSlotData("EMPTY")
            }
        }
        return slots
    }

    private fun glucose(design: CwfData, data: SnapshotData): WffSlotData {
        val sgv = data.sgv ?: return WffSlotData("EMPTY")
        return WffSlotData(
            "RANGED_VALUE", text = data.sgvText.replace('.', data.decimalSign), title = data.deltaText.replace('.', data.decimalSign),
            value = sgv.coerceIn(GLUCOSE_MIN, GLUCOSE_MAX), min = GLUCOSE_MIN, max = GLUCOSE_MAX, monochromaticImage = arrow(design, data.direction)
        )
    }

    /**
     * The trend arrow of the glucose slot: the design's own `Arrow*` picture, as `GlucoseArrows` of the
     * wear app takes it. Without one the wear app sends its own arrow drawing, which this repository
     * cannot reach (it is not in `:wear:cwfrender`): then a plain arrow is drawn here as a sample.
     */
    private fun arrow(design: CwfData, symbol: String): Bitmap {
        val known = ARROWS[symbol]
        val bitmap = Bitmap.createBitmap(ARROW_SIZE, ARROW_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val own = known?.let { design.resData[it.first.fileName]?.toDrawable(app.resources) }
        if (own != null) {
            own.setBounds(0, 0, ARROW_SIZE, ARROW_SIZE)
            own.draw(canvas)
            return bitmap
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = ARROW_SIZE / 10f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val middle = ARROW_SIZE / 2f
        // Drawn pointing right, then turned: up is -90 degrees
        canvas.rotate(known?.second ?: 0f, middle, middle)
        canvas.drawPath(Path().apply {
            moveTo(ARROW_SIZE * 0.15f, middle); lineTo(ARROW_SIZE * 0.85f, middle)
            moveTo(ARROW_SIZE * 0.55f, ARROW_SIZE * 0.25f); lineTo(ARROW_SIZE * 0.85f, middle); lineTo(ARROW_SIZE * 0.55f, ARROW_SIZE * 0.75f)
        }, paint)
        return bitmap
    }

    private fun age(data: SnapshotData): WffSlotData {
        if (data.sgv == null) return WffSlotData("EMPTY")
        val minutes = (data.readingAgeMs / 60_000L).coerceIn(0L, MAX_AGE_MINUTES)
        return WffSlotData("RANGED_VALUE", text = "${minutes}m", value = minutes.toDouble(), min = 0.0, max = MAX_AGE_MINUTES.toDouble())
    }

    private companion object {

        const val GLUCOSE_SLOT = 10
        const val AGE_SLOT = 11
        const val DATA_SLOT = 20
        const val ARROW_SIZE = 96

        /** As `GlucoseRange` of the wear app: the lowest and the highest value a sensor reports, in mg/dl. */
        const val GLUCOSE_MIN = 39.0
        const val GLUCOSE_MAX = 400.0

        /** The symbols the phone sends in `slopeArrow`, with the picture of the design and the angle of the sample arrow. */
        val ARROWS = mapOf(
            "⇈" to (ResFileMap.ARROW_DOUBLE_UP to -90f), "X" to (ResFileMap.ARROW_DOUBLE_UP to -90f), "↑" to (ResFileMap.ARROW_SINGLE_UP to -90f),
            "↗" to (ResFileMap.ARROW_FORTY_FIVE_UP to -45f), "→" to (ResFileMap.ARROW_FLAT to 0f), "↘" to (ResFileMap.ARROW_FORTY_FIVE_DOWN to 45f),
            "↓" to (ResFileMap.ARROW_SINGLE_DOWN to 90f), "⇊" to (ResFileMap.ARROW_DOUBLE_DOWN to 90f)
        )

        /** As `WffGlucoseAgeComplication.MAX_MINUTES`. */
        const val MAX_AGE_MINUTES = 60L
        val SLOT = Regex("<ComplicationSlot[^>]*slotId=\"(\\d+)\"")
        val POLICY = Regex("<DefaultProviderPolicy[^>]*>")
    }
}
