package app.aaps.facetool.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import app.aaps.facetool.wff.WffEval
import app.aaps.facetool.wff.WffFaceWriter
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.floor

/**
 * What a complication slot shows in the player (design 11.1).
 *
 * @param type the WFF type of the data: `RANGED_VALUE`, `SHORT_TEXT`, `SMALL_IMAGE`, `MONOCHROMATIC_IMAGE` or `EMPTY`
 */
class WffSlotData(
    val type: String, val text: String = "", val title: String = "", val value: Double = 0.0, val min: Double = 0.0, val max: Double = 0.0,
    val smallImage: Bitmap? = null, val monochromaticImage: Bitmap? = null
)

/**
 * The moment and the values the player draws (design section 7, the keys of the WFF player).
 *
 * @param timeMs the wall clock
 * @param visibleSec seconds since the screen came on, for frames that play on `ON_VISIBLE`
 * @param slots what each complication slot shows, by slot id; a slot without an entry is empty
 */
class WffScene(
    val timeMs: Long, val zone: ZoneId, val ambient: Boolean = false, val visibleSec: Double = 5.0, val is24Hour: Boolean = true,
    val heartRate: Int = 72, val steps: Long = 6482, val batteryPercent: Int = 64, val slots: Map<Int, WffSlotData> = emptyMap()
)

/**
 * Draws a converted face the way the WFF runtime would (design section 11), from the face folder the
 * converter wrote: `res/raw/watchface.xml` with its pictures and fonts. It knows the elements the
 * converter writes today (level 1 of design 11.2). An element or attribute it does not know is never
 * skipped silently: it gets a red hatched box and a line in [problems] (design 11.5).
 *
 * This is our reading of WFF. The parity suite compares it with the emulator.
 *
 * @param face the face folder
 * @param version the WFF version the face was written for (4 or 2), for the rules of design 11.3
 */
class WffPlayer(private val face: File, private val version: Int) {

    /** What the player could not show, or shows as the given WFF version would: lines for the lint. */
    val problems: List<String> get() = problemLines.toList()

    private val problemLines = LinkedHashSet<String>()
    private val root: Element = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(face, "res/raw/watchface.xml")).documentElement
    private val pictures = HashMap<String, Bitmap?>()
    private val fonts = HashMap<String, Typeface?>()

    val width: Int = root.getAttribute("width").toIntOrNull() ?: 450
    val height: Int = root.getAttribute("height").toIntOrNull() ?: 450

    /** What is known while a part is drawn: the scene, the sources of the slot it is in, and its place. */
    private class Frame(val scene: WffScene, val time: ZonedDateTime, val slot: WffSlotData?)

    fun draw(scene: WffScene): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val frame = Frame(scene, Instant.ofEpochMilli(scene.timeMs).atZone(scene.zone), null)
        known(root, "WatchFace", setOf("width", "height", "clipShape"))
        for (child in children(root)) when (child.tagName) {
            "Metadata" -> known(child, "WatchFace/Metadata", setOf("key", "value"))
            "Scene"    -> {
                known(child, "WatchFace/Scene", setOf("backgroundColor"))
                color(child.getAttribute("backgroundColor"))?.let { canvas.drawColor(it) }
                parts(canvas, child, frame, "WatchFace/Scene")
            }
            else       -> unknown(canvas, "WatchFace/${child.tagName}", null)
        }
        return bitmap
    }

    /** Draws the parts inside [parent], in document order. */
    private fun parts(canvas: Canvas, parent: Element, frame: Frame, path: String) {
        for (child in children(parent)) {
            val at = "$path/${child.tagName}"
            when (child.tagName) {
                "PartImage"         -> partImage(canvas, child, frame, at)
                "PartAnimatedImage" -> partAnimatedImage(canvas, child, frame, at)
                "PartText"          -> partText(canvas, child, frame, at)
                "PartDraw"          -> partDraw(canvas, child, frame, at)
                "DigitalClock"      -> digitalClock(canvas, child, frame, at)
                "AnalogClock"       -> analogClock(canvas, child, frame, at)
                "ComplicationSlot"  -> complicationSlot(canvas, child, frame, at)
                "Condition"         -> condition(child, frame, at)?.let { parts(canvas, it, frame, "$at/${it.tagName}") }
                else                -> unknown(canvas, at, box(child))
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Parts

    private fun partImage(canvas: Canvas, part: Element, frame: Frame, path: String) {
        known(part, path, setOf("x", "y", "width", "height", "tintColor"))
        val alpha = alphaOf(part, frame, path, 255)
        var resource: String? = null
        for (child in children(part)) when (child.tagName) {
            "Image"   -> { known(child, "$path/Image", setOf("resource")); resource = child.getAttribute("resource") }
            "Variant" -> Unit
            else      -> unknown(canvas, "$path/${child.tagName}", box(part))
        }
        if (alpha == 0) return
        val bitmap = picture(resource ?: return, frame) ?: return
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha }
        color(part.getAttribute("tintColor"))?.let { paint.colorFilter = PorterDuffColorFilter(it, PorterDuff.Mode.SRC_IN) }
        drawInBox(canvas, bitmap, box(part), paint)
    }

    private fun partAnimatedImage(canvas: Canvas, part: Element, frame: Frame, path: String) {
        known(part, path, setOf("x", "y", "width", "height", "blendMode"))
        val alpha = alphaOf(part, frame, path, 255)
        var controller: Element? = null
        var sequence: Element? = null
        for (child in children(part)) when (child.tagName) {
            "AnimationController" -> { known(child, "$path/AnimationController", setOf("play", "beforePlaying", "afterPlaying", "loopCount", "delayPlay")); controller = child }
            "SequenceImages"      -> { known(child, "$path/SequenceImages", setOf("frameRate")); sequence = child }
            "Variant"             -> Unit
            else                  -> unknown(canvas, "$path/${child.tagName}", box(part))
        }
        val frames = children(sequence ?: return).filter { it.tagName == "Image" }.map { it.getAttribute("resource") }
        if (frames.isEmpty() || alpha == 0) return
        val play = controller?.getAttribute("play").orEmpty().ifEmpty { "ON_VISIBLE" }
        if (play != "ON_VISIBLE") problem("player: cannot show AnimationController play=\"$play\" at $path: drawn as ON_VISIBLE")
        for (name in listOf("beforePlaying", "afterPlaying")) controller?.getAttribute(name)?.takeIf { it.isNotEmpty() && it != "HIDE" }?.let { problem("player: cannot show AnimationController $name=\"$it\" at $path: drawn as HIDE") }
        val rate = sequence.getAttribute("frameRate").toDoubleOrNull() ?: 15.0
        val loops = controller?.getAttribute("loopCount")?.toIntOrNull() ?: 1
        val since = frame.scene.visibleSec - (controller?.getAttribute("delayPlay")?.toDoubleOrNull() ?: 0.0)
        // Hidden before it plays and after its last loop
        if (since < 0) return
        val index = floor(since * rate).toInt()
        if (index >= frames.size * loops) return
        val bitmap = picture(frames[index % frames.size], frame) ?: return
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha }
        when (val blend = part.getAttribute("blendMode")) {
            ""       -> Unit
            // WFF 2 has no blendMode: the watch draws the frames without it
            "PLUS"   -> if (version >= BLEND_VERSION) paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) else problem("player: WFF $version has no blendMode at $path: drawn without it")
            "SCREEN" -> if (version >= BLEND_VERSION) paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) else problem("player: WFF $version has no blendMode at $path: drawn without it")
            else     -> problem("player: cannot show blendMode=\"$blend\" at $path")
        }
        drawInBox(canvas, bitmap, box(part), paint)
    }

    private fun partText(canvas: Canvas, part: Element, frame: Frame, path: String) {
        known(part, path, setOf("x", "y", "width", "height", "alpha"))
        val alpha = alphaOf(part, frame, path, part.getAttribute("alpha").toIntOrNull() ?: 255)
        for (child in children(part)) when (child.tagName) {
            "Text"    -> {
                known(child, "$path/Text", setOf("align"))
                val font = children(child).firstOrNull { it.tagName == "Font" }
                if (font == null) unknown(canvas, "$path/Text without Font", box(part)) else if (alpha > 0) drawText(canvas, box(part), child.getAttribute("align"), font, textOf(font, frame, "$path/Text/Font"), alpha, "$path/Text/Font")
            }
            "Variant" -> Unit
            else      -> unknown(canvas, "$path/${child.tagName}", box(part))
        }
    }

    private fun digitalClock(canvas: Canvas, clock: Element, frame: Frame, path: String) {
        known(clock, path, setOf("x", "y", "width", "height"))
        val outer = box(clock)
        for (child in children(clock)) when (child.tagName) {
            "TimeText" -> {
                known(child, "$path/TimeText", setOf("x", "y", "width", "height", "format", "hourFormat", "align"))
                val font = children(child).firstOrNull { it.tagName == "Font" } ?: continue
                val inner = box(child).apply { offset(outer.left, outer.top) }
                val hour24 = when (val mode = child.getAttribute("hourFormat")) {
                    "", "SYNC_TO_DEVICE" -> frame.scene.is24Hour
                    "24"                 -> true
                    "12"                 -> false
                    else                 -> true.also { problem("player: cannot show TimeText hourFormat=\"$mode\" at $path") }
                }
                drawText(canvas, inner, child.getAttribute("align"), font, timeText(child.getAttribute("format"), frame.time, hour24, path), 255, "$path/TimeText/Font")
            }
            else       -> unknown(canvas, "$path/${child.tagName}", outer)
        }
    }

    /** The text of a `TimeText`: `hh`, `mm`, `ss` with two digits, other letters as they are. */
    private fun timeText(format: String, time: ZonedDateTime, hour24: Boolean, path: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < format.length) {
            val two = format.substring(i, minOf(i + 2, format.length))
            when {
                two == "hh" -> { out.append("%02d".format(if (hour24) time.hour else ((time.hour + 11) % 12) + 1)); i += 2 }
                two == "mm" -> { out.append("%02d".format(time.minute)); i += 2 }
                two == "ss" -> { out.append("%02d".format(time.second)); i += 2 }
                format[i].isLetter() -> { problem("player: cannot show TimeText format=\"$format\" at $path"); out.append(format[i]); i++ }
                else        -> { out.append(format[i]); i++ }
            }
        }
        return out.toString()
    }

    private fun analogClock(canvas: Canvas, clock: Element, frame: Frame, path: String) {
        known(clock, path, setOf("x", "y", "width", "height"))
        if (alphaOf(clock, frame, path, 255) == 0) return
        val outer = box(clock)
        val time = frame.time
        val second = time.second + time.nano / 1e9
        for (hand in children(clock)) {
            val angle = when (hand.tagName) {
                "HourHand"   -> (time.hour % 12 + time.minute / 60.0) * 30.0
                "MinuteHand" -> (time.minute + second / 60.0) * 6.0
                "SecondHand" -> second * 6.0
                "Variant"    -> continue
                else         -> { unknown(canvas, "$path/${hand.tagName}", outer); continue }
            }
            known(hand, "$path/${hand.tagName}", setOf("x", "y", "width", "height", "pivotX", "pivotY", "resource"))
            // The second hand is not shown in always-on
            if (hand.tagName == "SecondHand" && frame.scene.ambient) continue
            val bitmap = picture(hand.getAttribute("resource"), frame) ?: continue
            val inner = box(hand).apply { offset(outer.left, outer.top) }
            val pivotX = inner.left + inner.width() * (hand.getAttribute("pivotX").toFloatOrNull() ?: 0.5f)
            val pivotY = inner.top + inner.height() * (hand.getAttribute("pivotY").toFloatOrNull() ?: 0.5f)
            canvas.save()
            canvas.rotate(angle.toFloat(), pivotX, pivotY)
            drawInBox(canvas, bitmap, inner, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            canvas.restore()
        }
    }

    private fun complicationSlot(canvas: Canvas, slot: Element, frame: Frame, path: String) {
        known(slot, path, setOf("x", "y", "width", "height", "slotId", "name", "displayName", "isCustomizable", "supportedTypes"))
        val data = frame.scene.slots[slot.getAttribute("slotId").toIntOrNull() ?: -1] ?: WffSlotData("EMPTY")
        val outer = box(slot)
        for (child in children(slot)) when (child.tagName) {
            "DefaultProviderPolicy" -> known(child, "$path/DefaultProviderPolicy", setOf("defaultSystemProvider", "defaultSystemProviderType", "primaryProvider", "primaryProviderType"))
            "BoundingBox", "BoundingOval" -> known(child, "$path/${child.tagName}", setOf("x", "y", "width", "height"))
            "Complication"          -> {
                known(child, "$path/Complication", setOf("type"))
                if (child.getAttribute("type") != data.type) continue
                canvas.save()
                canvas.translate(outer.left.toFloat(), outer.top.toFloat())
                parts(canvas, child, Frame(frame.scene, frame.time, data), "$path/Complication[${data.type}]")
                canvas.restore()
            }
            else                    -> unknown(canvas, "$path/${child.tagName}", outer)
        }
    }

    private fun partDraw(canvas: Canvas, part: Element, frame: Frame, path: String) {
        known(part, path, setOf("x", "y", "width", "height"))
        val outer = box(part)
        for (shape in children(part)) {
            val at = "$path/${shape.tagName}"
            val paints = ArrayList<Paint>()
            val moved = HashMap<String, Double>()
            for (child in children(shape)) when (child.tagName) {
                "Fill"      -> { known(child, "$at/Fill", setOf("color")); paints += Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = color(child.getAttribute("color")) ?: Color.WHITE } }
                "Stroke"    -> {
                    known(child, "$at/Stroke", setOf("color", "thickness"))
                    paints += Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = color(child.getAttribute("color")) ?: Color.WHITE; strokeWidth = child.getAttribute("thickness").toFloatOrNull() ?: 1f }
                }
                "Transform" -> { known(child, "$at/Transform", setOf("target", "value")); number(child.getAttribute("value"), frame, "$at/Transform")?.let { moved[child.getAttribute("target")] = it } }
                else        -> unknown(canvas, "$at/${child.tagName}", outer)
            }
            fun value(name: String): Float = (moved[name] ?: shape.getAttribute(name).toDoubleOrNull() ?: 0.0).toFloat()
            when (shape.tagName) {
                "RoundRectangle" -> {
                    known(shape, at, setOf("x", "y", "width", "height", "cornerRadiusX", "cornerRadiusY"))
                    val rect = RectF(outer.left + value("x"), outer.top + value("y"), outer.left + value("x") + value("width"), outer.top + value("y") + value("height"))
                    for (paint in paints) canvas.drawRoundRect(rect, value("cornerRadiusX"), value("cornerRadiusY"), paint)
                }
                "Arc"            -> {
                    known(shape, at, setOf("centerX", "centerY", "width", "height", "startAngle", "endAngle"))
                    val rect = RectF(
                        outer.left + value("centerX") - value("width") / 2, outer.top + value("centerY") - value("height") / 2,
                        outer.left + value("centerX") + value("width") / 2, outer.top + value("centerY") + value("height") / 2
                    )
                    // WFF counts angles clockwise from the top, Android from 3 o'clock
                    for (paint in paints) canvas.drawArc(rect, value("startAngle") - 90f, value("endAngle") - value("startAngle"), false, paint)
                }
                else             -> unknown(canvas, at, outer)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Conditions, expressions and sources

    /** The `Compare` of [condition] whose expression is true, else its `Default`; null when there is neither. */
    private fun condition(condition: Element, frame: Frame, path: String): Element? {
        val values = HashMap<String, Double>()
        var default: Element? = null
        val compares = ArrayList<Element>()
        for (child in children(condition)) when (child.tagName) {
            "Expressions" -> for (expression in children(child)) {
                known(expression, "$path/Expressions/Expression", setOf("name"))
                number(expression.textContent, frame, "$path/Expressions/Expression")?.let { values[expression.getAttribute("name")] = it }
            }
            "Compare"     -> { known(child, "$path/Compare", setOf("expression")); compares += child }
            "Default"     -> default = child
            else          -> problem("player: cannot show ${child.tagName} at $path")
        }
        return compares.firstOrNull { (values[it.getAttribute("expression")] ?: 0.0) != 0.0 } ?: default
    }

    /** The number sources at [frame]: the clock, the watch and the slot. */
    private fun numberSources(frame: Frame): Map<String, Double> {
        val t = frame.time
        val map = HashMap<String, Double>()
        map["HOUR_0_23"] = t.hour.toDouble()
        map["HOUR_0_23_Z"] = t.hour.toDouble()
        map["HOUR_1_12"] = (((t.hour + 11) % 12) + 1).toDouble()
        map["HOUR_1_12_Z"] = map.getValue("HOUR_1_12")
        map["MINUTE"] = t.minute.toDouble()
        map["MINUTE_Z"] = t.minute.toDouble()
        map["SECOND"] = t.second.toDouble()
        map["SECOND_Z"] = t.second.toDouble()
        map["MILLISECOND"] = (t.nano / 1_000_000).toDouble()
        map["SECOND_MILLISECOND"] = t.second + t.nano / 1e9
        map["SECONDS_IN_DAY"] = t.toLocalTime().toSecondOfDay().toDouble()
        map["DAY"] = t.dayOfMonth.toDouble()
        map["DAY_Z"] = t.dayOfMonth.toDouble()
        map["MONTH"] = t.monthValue.toDouble()
        map["MONTH_Z"] = t.monthValue.toDouble()
        map["YEAR"] = t.year.toDouble()
        // Sunday is 1
        map["DAY_OF_WEEK"] = (t.dayOfWeek.value % 7 + 1).toDouble()
        map["IS_24_HOUR_MODE"] = if (frame.scene.is24Hour) 1.0 else 0.0
        map["BATTERY_PERCENT"] = frame.scene.batteryPercent.toDouble()
        map["HEART_RATE"] = frame.scene.heartRate.toDouble()
        map["STEP_COUNT"] = frame.scene.steps.toDouble()
        frame.slot?.let { slot ->
            map["COMPLICATION.RANGED_VALUE_VALUE"] = slot.value
            map["COMPLICATION.RANGED_VALUE_MIN"] = slot.min
            map["COMPLICATION.RANGED_VALUE_MAX"] = slot.max
        }
        return map
    }

    /** The text sources at [frame]. A source that is only a number is written by [numberText]. */
    private fun textSource(name: String, frame: Frame): String? {
        val t = frame.time
        return when (name) {
            "COMPLICATION.TEXT"  -> frame.slot?.text
            "COMPLICATION.TITLE" -> frame.slot?.title
            "HOUR_0_23_Z"        -> "%02d".format(t.hour)
            "HOUR_1_12_Z"        -> "%02d".format(((t.hour + 11) % 12) + 1)
            "MINUTE_Z"           -> "%02d".format(t.minute)
            "SECOND_Z"           -> "%02d".format(t.second)
            "DAY_Z"              -> "%02d".format(t.dayOfMonth)
            "MONTH_Z"            -> "%02d".format(t.monthValue)
            "MONTH_S"            -> t.month.getDisplayName(TextStyle.SHORT, Locale.US)
            "MONTH_F"            -> t.month.getDisplayName(TextStyle.FULL, Locale.US)
            "DAY_OF_WEEK_S"      -> t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)
            "DAY_OF_WEEK_F"      -> t.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
            "AMPM_STATE"         -> if (t.hour < 12) "AM" else "PM"
            else                 -> null
        }
    }

    /** The value of the number expression [text]; null, with a problem line, when the player cannot work it out. */
    private fun number(text: String, frame: Frame, path: String): Double? {
        // textLength() of a text source is a number
        val plain = Regex("textLength\\(\\s*\\[([A-Z_0-9.]+)]\\s*\\)").replace(text.trim()) { match -> (textSource(match.groupValues[1], frame) ?: "").length.toString() }
        return try {
            WffEval(numberSources(frame)).eval(plain)
        } catch (e: Exception) {
            problem("player: cannot show the expression \"${text.trim()}\" at $path (${e.message})")
            null
        }
    }

    /** A number as WFF writes it in a text: without decimals when it is whole. */
    private fun numberText(value: Double): String = if (value == floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()

    /** The text of the expression of a `Parameter`: a text source, or a number expression. */
    private fun parameterText(expression: String, frame: Frame, path: String): String {
        val single = Regex("^\\[([A-Z_0-9.]+)]$").find(expression.trim())?.groupValues?.get(1)
        single?.let { name -> textSource(name, frame)?.let { return it } }
        return number(expression, frame, path)?.let(::numberText) ?: ""
    }

    /** The text inside a `Font`: plain text, a `Template` with its `Parameter`s, an `Upper` round either. */
    private fun textOf(parent: Element, frame: Frame, path: String): String {
        val out = StringBuilder()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            when {
                node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE -> out.append(node.nodeValue.trim().let { if (it.isEmpty()) "" else node.nodeValue })
                node !is Element                                                           -> Unit
                node.tagName == "Upper"                                                    -> out.append(textOf(node, frame, "$path/Upper").uppercase(Locale.US))
                node.tagName == "Lower"                                                    -> out.append(textOf(node, frame, "$path/Lower").lowercase(Locale.US))
                node.tagName == "Template"                                                 -> {
                    val parameters = children(node).filter { it.tagName == "Parameter" }.map { known(it, "$path/Template/Parameter", setOf("expression")); parameterText(it.getAttribute("expression"), frame, "$path/Template/Parameter") }
                    var format = ""
                    for (k in 0 until node.childNodes.length) node.childNodes.item(k).takeIf { it.nodeType == Node.TEXT_NODE }?.let { format += it.nodeValue }
                    var next = 0
                    out.append(Regex("%[sd]").replace(format.trim()) { parameters.getOrElse(next++) { "" } })
                }
                else                                                                       -> problem("player: cannot show ${node.tagName} at $path")
            }
        }
        return out.toString()
    }

    // ---------------------------------------------------------------------------------------------
    // Drawing helpers

    /**
     * One line of text in [box], placed as the WFF runtime places it as far as we know it: in the
     * middle of the height by the ascent and descent of the font, at the side [align] names.
     */
    private fun drawText(canvas: Canvas, box: Rect, align: String, font: Element, text: String, alpha: Int, path: String) {
        known(font, path, setOf("family", "size", "color", "weight"))
        val sizeText = font.getAttribute("size")
        var size = sizeText.toFloatOrNull() ?: DEFAULT_FONT_SIZE
        // The runtime of Wear OS 5 draws a default size for a size with decimals (converter doc 13.3)
        if (version < DECIMAL_SIZE_VERSION && size != floor(size)) {
            problem("player: WFF $version reads only whole font sizes; size=\"$sizeText\" at $path is drawn at the default size")
            size = DEFAULT_FONT_SIZE
        }
        val family = font.getAttribute("family")
        var typeface = typeface(family, path)
        when (val weight = font.getAttribute("weight")) {
            "", "NORMAL" -> Unit
            "BOLD"       -> typeface = Typeface.create(typeface, Typeface.BOLD)
            else         -> problem("player: cannot show Font weight=\"$weight\" at $path")
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.typeface = typeface
            textSize = size
            color = color(font.getAttribute("color")) ?: Color.WHITE
            this.alpha = this.alpha * alpha / 255
        }
        val metrics = paint.fontMetrics
        val baseline = box.top + box.height() / 2f - (metrics.ascent + metrics.descent) / 2f
        val width = paint.measureText(text)
        val x = when (align) {
            "START"      -> box.left.toFloat()
            "END"        -> box.right - width
            "", "CENTER" -> box.left + (box.width() - width) / 2f
            else         -> (box.left + (box.width() - width) / 2f).also { problem("player: cannot show align=\"$align\" at $path") }
        }
        canvas.drawText(text, x, baseline, paint)
    }

    private fun typeface(family: String, path: String): Typeface {
        if (family.isEmpty() || family == "SYNC_TO_DEVICE") return Typeface.DEFAULT
        return fonts.getOrPut(family) {
            File(face, "res/font").listFiles()?.firstOrNull { it.nameWithoutExtension == family }?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
        } ?: Typeface.DEFAULT.also { problem("player: the font \"$family\" is not in the face folder, at $path: drawn with the default font") }
    }

    /** Draws [bitmap] into [box]: one to one when it has the size of the box, else scaled. */
    private fun drawInBox(canvas: Canvas, bitmap: Bitmap, box: Rect, paint: Paint) {
        if (bitmap.width == box.width() && bitmap.height == box.height()) canvas.drawBitmap(bitmap, box.left.toFloat(), box.top.toFloat(), paint)
        else canvas.drawBitmap(bitmap, null, box, paint)
    }

    /** The picture [resource]: a file of `res/drawable`, or the picture of the slot (`[COMPLICATION.SMALL_IMAGE]`). */
    private fun picture(resource: String, frame: Frame): Bitmap? = when (resource) {
        "[COMPLICATION.SMALL_IMAGE]"         -> frame.slot?.smallImage
        "[COMPLICATION.MONOCHROMATIC_IMAGE]" -> frame.slot?.monochromaticImage
        else                                 -> pictures.getOrPut(resource) {
            File(face, "res/drawable").listFiles()?.firstOrNull { it.nameWithoutExtension == resource }?.let { file -> file.readBytes().let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } }
                .also { if (it == null) problem("player: the picture \"$resource\" is not in the face folder") }
        }
    }

    /** The alpha of a part, 0 to 255: [own], or the value of its `Variant` for always-on while the scene is in always-on. */
    private fun alphaOf(part: Element, frame: Frame, path: String, own: Int): Int {
        var alpha = own
        for (variant in children(part).filter { it.tagName == "Variant" }) {
            known(variant, "$path/Variant", setOf("mode", "target", "value"))
            if (variant.getAttribute("mode") != "AMBIENT") problem("player: cannot show Variant mode=\"${variant.getAttribute("mode")}\" at $path")
            else if (variant.getAttribute("target") != "alpha") problem("player: cannot show Variant target=\"${variant.getAttribute("target")}\" at $path")
            else if (frame.scene.ambient) alpha = variant.getAttribute("value").toIntOrNull() ?: alpha
        }
        return alpha.coerceIn(0, 255)
    }

    private fun box(element: Element): Rect {
        fun number(name: String) = element.getAttribute(name).toDoubleOrNull()?.toInt() ?: 0
        val x = number("x")
        val y = number("y")
        return Rect(x, y, x + (element.getAttribute("width").toDoubleOrNull()?.toInt() ?: width), y + (element.getAttribute("height").toDoubleOrNull()?.toInt() ?: height))
    }

    private fun color(text: String): Int? = if (text.isEmpty()) null else runCatching { Color.parseColor(text) }.getOrNull()

    private fun children(parent: Element): List<Element> {
        val nodes = parent.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    /** Says so when [element] has an attribute that is not in [attributes]. */
    private fun known(element: Element, path: String, attributes: Set<String>) {
        val all = element.attributes
        for (i in 0 until all.length) if (all.item(i).nodeName !in attributes) problem("player: cannot show the attribute ${all.item(i).nodeName} of $path")
    }

    private fun problem(line: String) {
        problemLines += line
    }

    /** An element the player does not know: a red hatched box at its place (the whole face when it has none), and a problem line. */
    private fun unknown(canvas: Canvas, path: String, box: Rect?) {
        problem("player: cannot show $path")
        val at = box ?: Rect(0, 0, width, height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 2f }
        canvas.save()
        canvas.clipRect(at)
        canvas.drawRect(at, paint)
        var x = at.left - at.height()
        while (x < at.right) {
            canvas.drawLine(x.toFloat(), at.bottom.toFloat(), (x + at.height()).toFloat(), at.top.toFloat(), paint)
            x += 12
        }
        canvas.restore()
    }

    private companion object {

        /** `blendMode` is in WFF from version 3. */
        const val BLEND_VERSION = 3

        /** The runtime of Wear OS 6 reads font sizes with decimals; the faces for it are WFF 4. */
        const val DECIMAL_SIZE_VERSION = WffFaceWriter.VERSION_4
        const val DEFAULT_FONT_SIZE = 24f
    }
}
