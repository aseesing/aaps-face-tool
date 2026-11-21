package app.aaps.facetool.snapshot

import app.aaps.core.interfaces.rx.weardata.DayNightRule
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.BatteryManager
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.wear.R
import app.aaps.wear.phonepreview.CwfPreviewRenderer
import app.aaps.wear.watchfaces.settingsync.CwfSettingNames
import app.aaps.wear.watchfaces.settingsync.CwfSyncedSetting
import app.aaps.wear.watchfaces.settingsync.CwfSyncedSettings
import app.aaps.wear.watchfaces.utils.WatchFaceTime
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The only file of the snapshot harness that uses the watch face engine of AAPS.
 *
 * When the engine changes its API, port this file. [DesignSnapshotTest] and [SnapshotCompareTest]
 * do not use the engine and stay as they are.
 *
 * A design is drawn the way the phone preview of AAPS draws it ([CwfPreviewRenderer]): a still frame
 * goes through the watch's own frame pipeline, a frame of the wake-up animation through the watch's
 * own wake animator. The values come in as the same messages the phone sends to the watch.
 *
 * @param logger the log of the faces; the tests give a mock, as before the harness moved to `src/main`
 */
class SnapshotEngine(private val app: Application, private val logger: AAPSLogger) {

    /** A design, read from its zip. */
    class Design(val name: String, internal val data: CwfData) {

        private val json = JSONObject(data.json)

        /** How many colours the wearer can choose for this design (0, 1 or 2). */
        val colourSlots: Int = json.optJSONObject("colourLooks")?.let { looks -> listOf("colour1", "colour2").count { looks.has(it) } } ?: 0
    }

    /** Reads the design in [zip], as the phone reads a zip the user picks (the design may set watch settings). */
    fun loadDesign(zip: File): Design {
        val file = ZipWatchfaceFormat.loadCustomWatchface(zip.readBytes(), zip.name, authorization = true)
            ?: error("${zip.name} is not a design zip")
        return Design(zip.nameWithoutExtension, file.cwfData)
    }

    /**
     * The time the engine reads as "now" (`System.currentTimeMillis()` in the engine code). The test
     * checks it, to be sure the clock of the engine is frozen.
     */
    fun engineNow(): Long = WatchFaceTime().apply { setToNow() }.millis

    /**
     * One watch face that draws a design. [name] must be new for each face in the test run: the face
     * keeps its files and preferences under this name.
     */
    inner class Face(private val name: String) {

        private val renderer = CwfPreviewRenderer(app, logger, name)

        /** True while the wake-up animation plays (the last frame was a frame of it). */
        val isAnimating: Boolean get() = renderer.isAnimating

        /**
         * Writes every watch setting the phone can set at its default value, as a new watch has them.
         * The design itself can change some of them when it is shown (its metadata).
         */
        fun settingsAtDefaults() {
            val names = CwfSettingNames(app.resources)
            val edit = renderer.watchSettings.edit()
            CwfSyncedSettings.all.forEach { setting ->
                when (setting) {
                    is CwfSyncedSetting.Toggle -> edit.putBoolean(names.nameOf(setting), setting.default)
                    is CwfSyncedSetting.Choice -> edit.putString(names.nameOf(setting), setting.defaultValue)
                }
            }
            edit.commit()
            renderer.settingsChanged()
        }

        /** Chooses a colour other than the design's own for each colour slot (the watch setting "Main colour", "Second colour"). */
        fun chooseColours() {
            val values = app.resources.getStringArray(R.array.cwf_colour_values)
            renderer.applyWatchSettings(
                mapOf(
                    app.getString(R.string.key_cwf_colour_1) to values[4],   // lime: not the cyan that D2 has as its own
                    app.getString(R.string.key_cwf_colour_2) to values[6]    // orange
                )
            )
        }

        /** Gives the face the fixed values of [data], as they are at [nowMs]. */
        fun feed(data: SnapshotData, nowMs: Long) {
            app.getSharedPreferences("${name}_aaps_health_values", Context.MODE_PRIVATE).edit()
                .putInt("heart_rate", data.heartRate).putLong("heart_rate_at", nowMs - 60_000L)
                .putLong("steps", data.steps).putLong("steps_at", nowMs - 60_000L)
                .commit()
            // The battery of the watch itself, from the battery broadcast that the system keeps
            data.watchBattery?.let { percent ->
                app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, percent).putExtra(BatteryManager.EXTRA_SCALE, 100))
            }
            messages(data, nowMs).forEach(renderer::apply)
        }

        /** Shows [design] from the next frame on. Its wake-up animation starts with the next frame. */
        fun show(design: Design) = renderer.setDesign(design.data)

        /**
         * The face at [nowMs], [size] pixels wide and high: a frame of the animation while it plays. A
         * copy that the caller owns: the renderer draws into its own frames again two frames later.
         */
        fun frame(size: Int, nowMs: Long): Bitmap = renderer.frame(size, nowMs).copy(Bitmap.Config.ARGB_8888, false)

        /**
         * Writes the watch settings [values] (stored name -> value), as the phone sends them: the choice
         * of an option of the design, a colour, a show or hide setting. Unknown names and values are left out.
         */
        fun applyWatchSettings(values: Map<String, String>) {
            renderer.applyWatchSettings(values)
        }

        /** True to draw the always-on face, as the watch shows it while the wrist is down. No wake-up animation is drawn then. */
        var ambient: Boolean
            get() = renderer.ambient
            set(value) {
                renderer.ambient = value
            }

        fun release() = renderer.release()
    }

    /** The messages the phone sends to the watch for [data] at [now]. */
    private fun messages(data: SnapshotData, now: Long): List<EventData> = listOfNotNull(
        EventData.Preferences(
            timeStamp = now, wearControl = false, unitsMgdl = data.unitsMgdl, bolusPercentage = 100, maxCarbs = 100, maxBolus = 10.0,
            insulinButtonIncrement1 = 0.5, insulinButtonIncrement2 = 1.0, carbsButtonIncrement1 = 5, carbsButtonIncrement2 = 10
        ),
        // The place of the phone, as the watch gets it: as the rule for when it is day
        EventData.ActionSetDayNightRule(DayNightRule.Place(data.latitude, data.longitude)),
        data.sgv?.let { sgv ->
            EventData.SingleBg(
                // The texts as the phone writes them, with the decimal sign of its language
                dataset = 0, timeStamp = now - data.readingAgeMs, sgvString = signed(data.sgvText, data), glucoseUnits = data.glucoseUnits, slopeArrow = data.direction,
                delta = signed(data.deltaText, data), deltaDetailed = signed(data.deltaDetailedText, data), avgDelta = signed(data.avgDeltaText, data), avgDeltaDetailed = signed(data.avgDeltaDetailedText, data),
                sgvLevel = data.sgvLevel, sgv = sgv, high = data.high, low = data.low, color = 0, deltaMgdl = data.deltaMgdl, avgDeltaMgdl = data.avgDeltaMgdl
            )
        },
        EventData.Status(
            dataset = 0, externalStatus = "", iobSum = data.iobText, iobDetail = data.iobDetailText, cob = data.cobText, currentBasal = data.basalText,
            battery = data.phoneBattery.toString(), rigBattery = data.rigBattery, openApsStatus = now - 3 * MINUTE, bgi = data.bgiText, batteryLevel = 1,
            tempTarget = data.tempTargetText, tempTargetLevel = 0, reservoirString = data.reservoirText, reservoir = data.reservoir, reservoirLevel = 0,
            sensorEndsAt = now + data.sensorHoursLeft * HOUR, pumpEndsAt = now + data.pumpHoursLeft * HOUR,
            // The same values as numbers, for v_iob, v_cob, v_basalRate and the others, as the phone sends them
            cobValue = data.cob, iobValue = data.iob, iobBolusValue = data.iobBolus, iobBasalValue = data.iobBasal, basalRateValue = data.basalRate, bgiMgdl = data.bgiMgdl
        ),
        EventData.GraphData(ArrayList(graphAges().let { ages ->
            ages.mapIndexed { index, age ->
                val hours = age / 3_600_000.0
                val wave = 125.0 + 40.0 * sin(hours * PI / 4.0) + 15.0 * exp(-hours / 2.0) * sin(hours * PI / 0.8)
                // Own values lie evenly over the points, oldest first
                val sgv = data.graph?.let { values -> if (values.isEmpty()) null else values[(index * values.size / ages.size).coerceAtMost(values.size - 1)] } ?: wave
                EventData.SingleBg(dataset = 0, timeStamp = now - age, sgv = sgv, high = data.high, low = data.low)
            }.filter { data.graph?.isEmpty() != true }
        })),
        EventData.TreatmentData(
            temps = arrayListOf(EventData.TreatmentData.TempBasal(now - 90 * MINUTE, 0.85, now - 60 * MINUTE, 0.85, 1.2)),
            basals = arrayListOf(EventData.TreatmentData.Basal(now - 6 * HOUR, now, 0.85)),
            boluses = arrayListOf(
                EventData.TreatmentData.Treatment(now - 150 * MINUTE, 2.5, 30.0, isSMB = false, isValid = true),
                EventData.TreatmentData.Treatment(now - 40 * MINUTE, 0.3, 0.0, isSMB = true, isValid = true),
                EventData.TreatmentData.Treatment(now - 20 * MINUTE, 0.2, 0.0, isSMB = true, isValid = true)
            ),
            predictions = ArrayList((1..12).map { i ->
                EventData.SingleBg(dataset = 0, timeStamp = now + i * 5 * MINUTE, sgv = (data.sgv ?: 121.0) + 2.0 * i, high = data.high, low = data.low, color = 0)
            })
        ),
        EventData.Weather(now - 10 * MINUTE, data.weather, data.temperature)
    )

    /** [text] with the decimal sign of the phone's language. The texts of [SnapshotData] are written with a point. */
    private fun signed(text: String, data: SnapshotData): String = text.replace('.', data.decimalSign)

    /** The ages of the graph points, oldest first: every 5 minutes for the last 6 hours, then thinner, as the phone sends them. */
    private fun graphAges(): List<Long> = ((0L until 6 * 12L).map { it * 5 * MINUTE } + (12L..48L).map { it * 30 * MINUTE }).sortedDescending()

    private companion object {

        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
    }
}

/**
 * The fixed values the face shows. [weather] is one of `clear`, `partly`, `cloudy` and `rain`, as the
 * phone sends it. [watchBattery] is the battery of the watch in percent; null leaves it as it is.
 * [decimalSign] is the decimal sign of the phone's language, in the glucose and delta texts the
 * phone writes (`sgvString`, `delta`).
 *
 * The glucose, the status and the graph are the values and the texts of the phone's messages. The
 * texts are written with a point; the face gets them with [decimalSign]. Every default is the value
 * the harness always showed, so a face made with the defaults does not change. [sgv] null is a phone
 * that never sent a reading. [graph] null is the wave the harness always showed; a list gives the
 * glucose of the graph in mg/dl, oldest first (an empty list: no graph points).
 */
data class SnapshotData(
    val weather: String,
    val temperature: Double = 7.5,
    val latitude: Double = 52.1,
    val longitude: Double = 5.1,
    val phoneBattery: Int = 78,
    val heartRate: Int = 72,
    val steps: Long = 6482,
    val sensorHoursLeft: Long = 60,
    val pumpHoursLeft: Long = 10,
    val watchBattery: Int? = null,
    val decimalSign: Char = ',',
    val unitsMgdl: Boolean = false,
    val glucoseUnits: String = "mmol",
    val sgv: Double? = 121.0,
    val sgvText: String = "6.7",
    val sgvLevel: Long = 0,
    val high: Double = 180.0,
    val low: Double = 70.0,
    val direction: String = "→",
    val readingAgeMs: Long = 120_000L,
    val deltaMgdl: Double = 1.8,
    val deltaText: String = "+0.1",
    val deltaDetailedText: String = "+0.12",
    val avgDeltaMgdl: Double = 1.4,
    val avgDeltaText: String = "+0.1",
    val avgDeltaDetailedText: String = "+0.08",
    val iob: Double = 1.25,
    val iobBolus: Double = 0.90,
    val iobBasal: Double = 0.35,
    val iobText: String = "1.25",
    val iobDetailText: String = "(0.90|0.35)",
    val cob: Double = 12.0,
    val cobText: String = "12g",
    val basalRate: Double = 0.85,
    val basalText: String = "0.85U/h",
    val bgiMgdl: Double = -7.2,
    val bgiText: String = "-0.4",
    val tempTargetText: String = "100",
    val reservoir: Double = 120.0,
    val reservoirText: String = "120U",
    val rigBattery: String = "--",
    val graph: List<Double>? = null
)
