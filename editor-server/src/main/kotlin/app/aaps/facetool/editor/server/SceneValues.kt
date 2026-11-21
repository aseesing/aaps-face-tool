package app.aaps.facetool.editor.server

import app.aaps.facetool.editor.model.Scenes
import app.aaps.facetool.editor.model.wrongUse
import app.aaps.facetool.snapshot.SnapshotClock
import app.aaps.facetool.snapshot.SnapshotData
import app.aaps.wear.phonepreview.PreviewSlotSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A scene (design section 7) as the engine needs it.
 *
 * @param timeMs the moment of the picture
 * @param wakeMs how long ago the wake began; null is the rest picture
 * @param data the values the phone sends
 * @param options the choice per option name
 * @param colours `colour1` and `colour2`: a colour value of the watch setting
 * @param watch other watch settings, stored name -> value
 *
 * The source of each complication slot (the scene key `slots`) is in [data], with the other values the face is fed.
 */
class SceneValues(
    val timeMs: Long, val size: Int, val wakeMs: Long?, val data: SnapshotData,
    val options: Map<String, Int>, val colours: Map<String, String>, val watch: Map<String, String>, val ambient: Boolean
) {

    /** Changes when the values that are fed to the face change: the face is fed again only then. */
    val feedKey: String get() = "$timeMs|$data"
}

/** Reads a scene object (with every default filled in, see `Scenes` of the model) into [SceneValues]. */
class SceneReader {

    fun read(scene: JsonObject): SceneValues {
        val zone = text(scene, "zone") ?: SnapshotClock.ZONE
        // The engine's clock runs in one zone for the whole server (SnapshotClock)
        if (zone != SnapshotClock.ZONE) wrongUse("The scene key zone must be ${SnapshotClock.ZONE}: the engine preview runs in this one zone")
        val time = runCatching { LocalDateTime.parse(text(scene, "time")) }.getOrElse { wrongUse("The scene key time must be a local time like 2026-11-17T10:08:00") }
        val size = number(scene, "size")?.toInt() ?: 450
        if (size !in 50..2000) wrongUse("The scene key size must be 50 to 2000")
        val mgdl = when (text(scene, "units")) {
            "mgdl", "mg/dl" -> true
            "mmol", null    -> false
            else            -> wrongUse("The scene key units must be mmol or mgdl")
        }
        val sign = (text(scene, "decimalSign") ?: ",").firstOrNull() ?: ','
        val sgv = number(scene, "sgv")
        val delta = number(scene, "delta") ?: 0.0
        val avgDelta = number(scene, "avgDelta") ?: 0.0
        val iob = number(scene, "iob") ?: 0.0
        val iobBolus = number(scene, "iobBolus") ?: 0.0
        val iobBasal = number(scene, "iobBasal") ?: 0.0
        val cob = number(scene, "cob") ?: 0.0
        val basal = number(scene, "basalRate") ?: 0.0
        val bgi = number(scene, "bgi") ?: 0.0
        val reservoir = number(scene, "reservoir") ?: 0.0
        val data = SnapshotData(
            weather = text(scene, "weather") ?: "clear",
            temperature = number(scene, "temperature") ?: 7.5,
            latitude = number(scene, "latitude") ?: 52.1,
            longitude = number(scene, "longitude") ?: 5.1,
            phoneBattery = number(scene, "phoneBattery")?.toInt() ?: 78,
            heartRate = number(scene, "heartRate")?.toInt() ?: 72,
            steps = number(scene, "steps")?.toLong() ?: 6482,
            watchBattery = number(scene, "watchBattery")?.toInt(),
            decimalSign = sign,
            unitsMgdl = mgdl,
            glucoseUnits = if (mgdl) "mg/dl" else "mmol",
            sgv = sgv,
            sgvText = sgv?.let { glucose(it, mgdl, 1) } ?: "---",
            sgvLevel = sgv?.let { if (it > HIGH) 1L else if (it < LOW) -1L else 0L } ?: 0L,
            direction = text(scene, "direction") ?: "",
            readingAgeMs = ((number(scene, "readingAgeMin") ?: 2.0) * 60_000).toLong(),
            deltaMgdl = delta,
            deltaText = signed(delta, mgdl, 1),
            deltaDetailedText = signed(delta, mgdl, 2),
            avgDeltaMgdl = avgDelta,
            avgDeltaText = signed(avgDelta, mgdl, 1),
            avgDeltaDetailedText = signed(avgDelta, mgdl, 2),
            iob = iob, iobBolus = iobBolus, iobBasal = iobBasal,
            iobText = fixed(iob, 2), iobDetailText = "(${fixed(iobBolus, 2)}|${fixed(iobBasal, 2)})",
            cob = cob, cobText = "${cob.roundToInt()}g",
            basalRate = basal, basalText = "${fixed(basal, 2)}U/h",
            bgiMgdl = bgi, bgiText = if (mgdl) fixed(bgi, 0) else fixed(bgi / MGDL_PER_MMOL, 1),
            tempTargetText = fixed(number(scene, "tempTarget") ?: 100.0, 0),
            reservoir = reservoir, reservoirText = "${fixed(reservoir, 0)}U",
            rigBattery = text(scene, "rigBattery") ?: "--",
            // null stays null: the face then sees the value as missing
            ic = number(scene, "ic"), isfMgdl = number(scene, "isf"), maxIob = number(scene, "max_iob"), maxCarbs = number(scene, "max_carbs"),
            graph = graph(scene["graph"]),
            slotSources = slots(scene["slots"])
        )
        val wake = number(scene, "wake")
        if (wake != null && wake !in 0.0..60.0) wrongUse("The scene key wake must be 0 to 60 seconds")
        return SceneValues(
            timeMs = SnapshotClock.epochMs(time), size = size, wakeMs = wake?.let { (it * 1000).toLong() }, data = data,
            options = (scene["options"] as? JsonObject).orEmpty().mapValues { (name, value) -> (value as? JsonPrimitive)?.doubleOrNull?.toInt() ?: wrongUse("The option $name needs the number of a choice") },
            colours = (scene["colours"] as? JsonObject).orEmpty().mapValues { (it.value as JsonPrimitive).content },
            watch = (scene["watch"] as? JsonObject).orEmpty().mapValues { (it.value as JsonPrimitive).content },
            ambient = (scene["ambient"] as? JsonPrimitive)?.booleanOrNull == true
        )
    }

    /** The source per slot number of the scene key `slots`. A slot with null is left out: it shows the default of the watch. */
    private fun slots(value: JsonElement?): Map<Int, PreviewSlotSource> {
        if (value == null || value is JsonNull) return emptyMap()
        val slots = value as? JsonObject ?: wrongUse("The scene key slots must be an object like {\"3\": \"bg\"}")
        val result = LinkedHashMap<Int, PreviewSlotSource>()
        for ((slot, source) in slots) {
            val number = slot.toIntOrNull()?.takeIf { it in 1..5 } ?: wrongUse("The scene key slots has the slots 1 to 5, not '$slot'")
            if (source is JsonNull) continue
            val name = (source as? JsonPrimitive)?.content
            result[number] = SlotNames.source(name) ?: wrongUse("Slot $slot of the scene key slots cannot show '$name'. Sources: ${Scenes.SLOT_SOURCES.joinToString(", ")}")
        }
        return result
    }

    /** The graph history in mg/dl, oldest first; null is the wave of the harness. */
    private fun graph(value: JsonElement?): List<Double>? = when {
        value is JsonArray                                -> value.map { (it as? JsonPrimitive)?.doubleOrNull ?: wrongUse("The scene key graph holds numbers") }
        value !is JsonPrimitive || value is JsonNull     -> null
        value.content == "wave"                          -> null
        value.content == "flat"                          -> List(GRAPH_POINTS) { 110.0 }
        value.content == "high"                          -> List(GRAPH_POINTS) { i -> 190.0 + 60.0 * sin(i * PI / 40.0) }
        value.content == "low"                           -> List(GRAPH_POINTS) { i -> 75.0 + 20.0 * sin(i * PI / 30.0) }
        else                                              -> wrongUse("The scene key graph must be wave, flat, high, low or a list of values")
    }

    private fun glucose(mgdlValue: Double, mgdl: Boolean, mmolDecimals: Int): String = if (mgdl) fixed(mgdlValue, 0) else fixed(mgdlValue / MGDL_PER_MMOL, mmolDecimals)

    private fun signed(mgdlValue: Double, mgdl: Boolean, mmolDecimals: Int): String =
        (if (mgdlValue >= 0) "+" else "-") + if (mgdl) fixed(abs(mgdlValue), mmolDecimals - 1) else fixed(abs(mgdlValue) / MGDL_PER_MMOL, mmolDecimals)

    /** A number with a point; the face gets it with the decimal sign of the scene. */
    private fun fixed(value: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", value)

    private fun text(scene: JsonObject, key: String): String? = (scene[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun number(scene: JsonObject, key: String): Double? {
        val value = scene[key] as? JsonPrimitive ?: return null
        if (value is JsonNull) return null
        return value.doubleOrNull ?: wrongUse("The scene key $key needs a number")
    }

    private companion object {

        const val MGDL_PER_MMOL = 18.0182
        const val HIGH = 180.0
        const val LOW = 70.0
        const val GRAPH_POINTS = 109
    }
}

/** The names of the scene key `slots` for the sources of a complication slot of the preview, see `Scenes.SLOT_SOURCES`. */
object SlotNames {

    private val names = mapOf(
        "none" to PreviewSlotSource.NONE, "cob" to PreviewSlotSource.COB, "iob" to PreviewSlotSource.IOB, "bg" to PreviewSlotSource.BG,
        "basal" to PreviewSlotSource.BASAL, "phoneBattery" to PreviewSlotSource.PHONE_BATTERY, "heartRate" to PreviewSlotSource.HEART_RATE,
        "steps" to PreviewSlotSource.STEPS
    )

    /** The source with the scene name [name], or null when there is none. */
    fun source(name: String?): PreviewSlotSource? = names[name]

    /** The scene name of [source]. */
    fun name(source: PreviewSlotSource): String = names.entries.first { it.value == source }.key
}
