package app.aaps.watchfaces.wffparity

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import app.aaps.core.interfaces.rx.weardata.ResData
import app.aaps.core.interfaces.rx.weardata.ResFormat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/*
 * The classic views of a design (no animation): the clock and date texts, the hands, the glucose
 * views, the data views, `dynData`, the wearer's complication slots and the always-on look.
 *
 * The scene: 17 Nov 2026 10:08 (the picture is taken 31 s later), glucose 6.7 mmol/l in range, flat,
 * delta +0.1, the reading 2 minutes old, iob 1.25, cob 12 g, basal 0.85 U/h (see `SnapshotEngine`).
 */
/** The classic clock and date views. */
class ParityClassicFaceClockTest : WffParityBase() {

    // ---- the clock ----

    @Test
    fun timeViewZipFont() = check(
        ParityDesign("Time").view("time", 60, 150, 280, 100, "\"textsize\": 72, \"gravity\": \"left\", \"font\": \"AuroraCut\", \"fontColor\": \"#4CC9F0\""),
        lint = listOf(live("time"))
    )

    @Test
    fun hourAndMinuteViews() = check(
        ParityDesign("HourMinute")
            .view("hour", 70, 140, 120, 110, "\"textsize\": 88, \"gravity\": \"right\", \"font\": \"default\", \"fontColor\": \"#00E5FF\"")
            .view("minute", 210, 140, 120, 110, "\"textsize\": 88, \"gravity\": \"left\", \"font\": \"default\", \"fontStyle\": \"bold\", \"fontColor\": \"#FFFFFF\""),
        lint = listOf(live("hour"), live("minute"))
    )

    /**
     * With `enableSecond` our `time` view shows the seconds too: 10:08:31. The emulator's picture is
     * taken some seconds after ours, so the two digits of the seconds are their own region and may
     * differ. The digits have the same width, so the rest of the line stays in place.
     */
    @Test
    fun timeViewWithSeconds() = check(
        ParityDesign("TimeSeconds").keys("\"enableSecond\": true")
            .view("time", 40, 150, 320, 100, "\"textsize\": 64, \"gravity\": \"center\", \"font\": \"default\", \"fontColor\": \"#FFFFFF\""),
        regions = listOf(
            ParityCompare.Region("left of the seconds", 1.0, intArrayOf(0, 0, 250, 400)),
            // The seconds of the emulator run some seconds ahead of ours (tools/wff-emulator.sh)
            ParityCompare.Region("seconds", 60.0, intArrayOf(250, 150, 70, 100)),
            ParityCompare.Region("right of the seconds", 1.0, intArrayOf(320, 0, 80, 400))
        )
    )

    /**
     * The seconds. The emulator's clock is set 2 s before its picture is taken, so its seconds are
     * about 2 more than ours: the digits are their own region and may differ.
     */
    @Test
    fun secondView() = check(
        ParityDesign("Second").keys("\"enableSecond\": true")
            .view("minute", 100, 150, 100, 100, "\"textsize\": 72, \"gravity\": \"right\", \"font\": \"default\", \"fontColor\": \"#FFFFFF\"")
            .view("second", 210, 150, 100, 100, "\"textsize\": 72, \"gravity\": \"left\", \"font\": \"default\", \"fontColor\": \"#FFD166\""),
        lint = listOf(live("second")),
        regions = listOf(
            ParityCompare.Region("outside the seconds", 1.0, intArrayOf(0, 0, 200, 400)),
            // The seconds of the emulator run about 2 s ahead of ours (tools/wff-emulator.sh)
            ParityCompare.Region("seconds", 60.0, intArrayOf(210, 150, 100, 100))
        )
    )

    /** Our engine shows the seconds only when the design sets `enableSecond`; without it the view stays empty. */
    @Test
    fun secondViewNeedsEnableSecond() = check(
        ParityDesign("SecondOff")
            .view("second", 150, 150, 100, 100, "\"textsize\": 72, \"gravity\": \"center\", \"font\": \"default\", \"fontColor\": \"#FFD166\"")
    )

    // ---- the date ----

    private fun dateDesign(name: String) = ParityDesign(name).meta("key_show_date", "true")
        .view("day_name", 70, 160, 110, 50, "\"textsize\": 36, \"gravity\": \"right\", \"font\": \"default\", \"fontColor\": \"#F2F2F2\", \"allCaps\": true")
        .view("day", 185, 160, 50, 50, "\"textsize\": 36, \"gravity\": \"center\", \"font\": \"default\", \"fontStyle\": \"bold\", \"fontColor\": \"#B6FF1A\"")
        .view("month", 240, 160, 100, 50, "\"textsize\": 36, \"gravity\": \"left\", \"font\": \"default\", \"fontColor\": \"#8C93B8\"")

    @Test
    fun dateViews() = check(dateDesign("Date"), lint = listOf(live("day_name"), live("day"), live("month")))

    /** `dayNameFormat` and `monthFormat` are keys of the design (top level), as our engine reads them: Tuesday, November. */
    @Test
    fun dateLongFormats() = check(
        ParityDesign("DateLong").meta("key_show_date", "true").keys("\"dayNameFormat\": \"EEEE\", \"monthFormat\": \"MMMM\"")
            .view("day_name", 50, 130, 300, 60, "\"textsize\": 40, \"gravity\": \"center\", \"font\": \"default\", \"fontColor\": \"#F2F2F2\"")
            .view("month", 50, 210, 300, 60, "\"textsize\": 40, \"gravity\": \"center\", \"font\": \"default\", \"fontColor\": \"#8C93B8\"")
    )

    /** The date shows only with the watch preference "Show date", off by default: the live date is left out and the lint says so. */
    @Test
    fun dateOffByDefault() = check(
        ParityDesign("DateOff")
            .view("day", 170, 170, 60, 60, "\"textsize\": 40, \"gravity\": \"center\", \"font\": \"default\", \"fontColor\": \"#FFFFFF\""),
        lint = listOf("off by the watch preference key_show_date")
    )

    @Test
    fun weekNumber() = check(
        ParityDesign("Week").meta("key_show_week_number", "true")
            .view("week_number", 150, 170, 100, 60, "\"textsize\": 40, \"gravity\": \"center\", \"font\": \"default\", \"fontColor\": \"#FFFFFF\""),
        lint = listOf(data("week_number"))
    )
}

/** The classic hands and glucose views. */
class ParityClassicFaceGlucoseTest : WffParityBase() {

    // ---- the hands ----

    @Test
    fun handPictures() {
        check(
            ParityDesign("Hands").withHands().view("hour_hand", 0, 0, 400, 400).view("minute_hand", 0, 0, 400, 400),
            lint = listOf(live("hour_hand"), live("minute_hand"))
        )
    }

    /** The second hand. The emulator's seconds run about 2 s ahead (12 degrees), so only the hand's own circle may differ. */
    @Test
    fun secondHand() {
        check(
            ParityDesign("SecondHand").withHands().keys("\"enableSecond\": true").view("second_hand", 0, 0, 400, 400),
            lint = listOf(live("second_hand")),
            regions = listOf(
                ParityCompare.Region("above the hand", 1.0, intArrayOf(0, 0, 400, 190)),
                // The hand points down (about 31 s); the emulator is about 12 degrees further
                ParityCompare.Region("the hand", 10.0, intArrayOf(120, 190, 160, 210))
            )
        )
    }

    /** Hands without pictures in the design: our engine draws its own default hands. */
    @Test
    fun handsDefaultPictures() = check(
        ParityDesign("HandsDefault").view("hour_hand", 0, 0, 400, 400).view("minute_hand", 0, 0, 400, 400)
    )

    // ---- the glucose ----

    @Test
    fun sgvRangeColour() = check(
        ParityDesign("Sgv").keys("\"lowColor\": \"#FF003C\", \"midColor\": \"#39FF14\", \"highColor\": \"#FCEE0A\"")
            .view("sgv", 80, 140, 240, 110, "\"textsize\": 80, \"gravity\": \"center\", \"font\": \"default\", \"fontStyle\": \"bold\", \"fontColor\": \"#FFFFFF\""),
        lint = listOf("live text, coloured by the glucose level")
    )

    /** As D2: three `fontColor` steps on `sgvLevel`. */
    @Test
    fun sgvColourByLevel() = check(
        ParityDesign("SgvLevel")
            .keys("\"dynData\": {\"sgvColour\": {\"valueKey\": \"sgvLevel\", \"minData\": -1, \"maxData\": 1, \"invalidFontColor\": \"#8A9099\", \"fontColor1\": \"#FF3B3B\", \"fontColor2\": \"#2EE66B\", \"fontColor3\": \"#FFB21A\"}}")
            .view("sgv", 80, 140, 240, 110, "\"textsize\": 80, \"gravity\": \"center\", \"font\": \"AuroraCut\", \"fontColor\": \"#FFFFFF\", \"dynData\": \"sgvColour\""),
        lint = listOf("live text, coloured by the glucose level")
    )

    @Test
    fun directionArrow() = check(
        ParityDesign("Direction").keys("\"midColor\": \"#39FF14\"").view("direction", 160, 160, 80, 80),
        lint = listOf("the arrow, monochromatic image")
    )

    @Test
    fun deltaView() = check(
        ParityDesign("Delta").view("delta", 100, 160, 200, 70, "\"textsize\": 50, \"gravity\": \"center\", \"font\": \"default\", \"fontColor\": \"#FFFFFF\""),
        lint = listOf("the title of the glucose slot")
    )

    @Test
    fun avgDeltaView() = check(
        ParityDesign("AvgDelta").view("avg_delta", 100, 160, 200, 70, "\"textsize\": 50, \"gravity\": \"center\", \"font\": \"default\", \"fontColor\": \"#FFFFFF\""),
        lint = listOf(data("avg_delta"))
    )

    @Test
    fun timestampAge() = check(
        ParityDesign("Age").view("timestamp", 120, 160, 160, 70, "\"textsize\": 50, \"gravity\": \"center\", \"font\": \"default\", \"fontColor\": \"#FFFFFF\""),
        lint = listOf("minutes that keep counting")
    )

    /** As D2: the age with a text and colour steps (live in the age slot). */
    @Test
    fun timestampColourSteps() = check(
        ParityDesign("AgeSteps")
            .keys("\"dynData\": {\"bgAge\": {\"valueKey\": \"timestamp\", \"minData\": 0, \"maxData\": 20, \"textvalue1\": \"%.0f'\", \"invalidTextvalue\": \"--\", \"invalidFontColor\": \"#8A9099\", \"fontColor1\": \"#F3E9B5\", \"fontColor2\": \"#FF3B3B\"}}")
            .view("timestamp", 120, 160, 160, 70, "\"textsize\": 50, \"gravity\": \"center\", \"font\": \"default\", \"fontColor\": \"#F3E9B5\", \"textvalue\": \"%.0f'\", \"dynValue\": false, \"dynData\": \"bgAge\""),
        lint = listOf("minutes that keep counting")
    )
}

/** The classic data views, dynData, the slots of the wearer and the always-on look. */
class ParityClassicFaceDataTest : WffParityBase() {

    // ---- the data views (the data picture) ----

    @Test
    fun chartView() = check(
        ParityDesign("Chart").view("chart", 40, 130, 320, 140),
        lint = listOf(data("chart"))
    )

    @Test
    fun iobCobBasalViews() = check(
        ParityDesign("IobCob")
            .view("iob1", 90, 110, 100, 40, "\"textsize\": 24, \"gravity\": \"left\", \"font\": \"default\", \"fontColor\": \"#8C93B8\"")
            .view("iob2", 200, 110, 120, 40, "\"textsize\": 28, \"gravity\": \"left\", \"font\": \"default\", \"fontColor\": \"#FFFFFF\"")
            .view("cob1", 90, 160, 100, 40, "\"textsize\": 24, \"gravity\": \"left\", \"font\": \"default\", \"fontColor\": \"#8C93B8\"")
            .view("cob2", 200, 160, 120, 40, "\"textsize\": 28, \"gravity\": \"left\", \"font\": \"default\", \"fontColor\": \"#FFFFFF\"")
            .view("basalRate", 90, 210, 230, 40, "\"textsize\": 28, \"gravity\": \"center\", \"font\": \"AuroraCut\", \"fontColor\": \"#FFD166\""),
        lint = listOf(data("iob2"), data("cob2"), data("basalRate"))
    )

    @Test
    fun otherStatusViews() = check(
        ParityDesign("Status")
            .view("reservoir", 100, 110, 200, 40, "\"textsize\": 28, \"gravity\": \"center\", \"font\": \"default\", \"fontColor\": \"#FFFFFF\"")
            .view("tempTarget", 100, 155, 200, 40, "\"textsize\": 28, \"gravity\": \"center\", \"font\": \"default\", \"fontColor\": \"#FFFFFF\"")
            .view("uploader_battery", 100, 200, 200, 40, "\"textsize\": 28, \"gravity\": \"center\", \"font\": \"default\", \"fontColor\": \"#FFFFFF\"")
            .view("loop", 160, 250, 80, 40, "\"textsize\": 24, \"gravity\": \"center\", \"font\": \"default\", \"fontColor\": \"#FFFFFF\""),
        lint = listOf(data("reservoir"), data("loop"))
    )

    // ---- dynData (the data picture) ----

    /** A text colour by value: iob 1.25 lies in the first of two steps of 0 to 4. */
    @Test
    fun dynDataColourByValue() = check(
        ParityDesign("DynColour")
            .keys("\"dynData\": {\"iobColour\": {\"valueKey\": \"iob\", \"minData\": 0, \"maxData\": 4, \"fontColor1\": \"#FF3B3B\", \"fontColor2\": \"#39FF14\"}}")
            .view("iob2", 100, 160, 200, 70, "\"textsize\": 50, \"gravity\": \"center\", \"font\": \"default\", \"fontColor\": \"#FFFFFF\", \"dynData\": \"iobColour\""),
        lint = listOf("shows iob: in the data picture")
    )

    /** A picture turned by a value, as D2's needles: the star turned by the glucose. */
    @Test
    fun dynDataRotation() = check(
        ParityDesign("DynRotation")
            .keys("\"dynData\": {\"needle\": {\"valueKey\": \"sgv\", \"minData\": 40, \"maxData\": 300, \"rotationOffset\": {\"minValue\": -60, \"maxValue\": 60, \"invalidValue\": -60}, \"image1\": \"Star\"}}")
            .view("freetext1", 120, 120, 160, 160, "\"textsize\": 20, \"gravity\": \"center\", \"font\": \"default\", \"fontColor\": \"#00000000\", \"textvalue\": \"\", \"background\": \"Star\", \"rotationOffset\": true, \"dynData\": \"needle\""),
        lint = listOf("shows sgv: in the data picture")
    )

    /** A picture per step: the glucose 121 mg/dl lies in the first of two steps of 0 to 400 (the star, not the disc). */
    @Test
    fun dynDataPictureSteps() = check(
        ParityDesign("DynPicture")
            .keys("\"dynData\": {\"steps\": {\"valueKey\": \"sgv\", \"minData\": 0, \"maxData\": 400, \"invalidImage\": \"Disc\", \"image1\": \"Star\", \"image2\": \"Disc\"}}")
            .view("freetext2", 150, 150, 100, 100, "\"textsize\": 20, \"gravity\": \"center\", \"font\": \"default\", \"fontColor\": \"#00000000\", \"textvalue\": \"\", \"dynData\": \"steps\""),
        lint = listOf("shows sgv: in the data picture")
    )

    // ---- the wearer's slots ----

    // The values in the wearer's slots: slots 1 and 2 start with the heart rate and the steps. Our
    // engine shows those of the scene (72, 6482). The emulator shows them too until its own sensors
    // report, then its own values (105 and 26 were seen), so a value can differ between runs. Slots 3
    // to 5 cannot be used: our engine has no data for them in the test and draws them empty.

    /** As D2 and Cyberpunk: two text slots without a fill, in a zip font. */
    @Test
    fun complicationTextSlots() = check(
        ParityDesign("SlotsText").meta("key_show_complication1", "true").meta("key_show_complication2", "true")
            .keys("\"complicationStyle\": {}")
            .view("complication1", 120, 150, 160, 40, SLOT_TEXT_STYLE)
            .view("complication2", 120, 200, 160, 40, SLOT_TEXT_STYLE),
        lint = listOf("user slot 1", "user slot 2")
    )

    /** As Grand Complication: a round slot with a fill, a ring and an icon (slot 2, the steps). */
    @Test
    fun complicationRoundRing() = check(
        ParityDesign("SlotRound").meta("key_show_complication2", "true")
            .view(
                "complication2", 150, 150, 100, 100,
                "\"color\": \"#FFFDF7\", \"borderRadius\": 50, \"ringWidth\": 6, \"ringPrimaryColor\": \"#1D3F9E\", \"ringSecondaryColor\": \"#E7DDC8\", " +
                    "\"fontColor\": \"#1C2A5A\", \"iconColor\": \"#1C2A5A\", \"textsize\": 24, \"font\": \"default\", \"fontStyle\": \"normal\", \"gravity\": \"center\""
            ),
        lint = listOf("user slot 2"),
        regions = listOf(
            WHOLE,
            // Above the text, so the value does not count: the icon our engine draws over the text
            ParityCompare.Region("the icon", 1.0, intArrayOf(189, 172, 22, 16))
        )
    )

    /** The style in the design's `complicationStyle` section, nothing on the slot itself: our engine uses the section. */
    @Test
    fun complicationStyleSection() = check(
        ParityDesign("SlotStyle").meta("key_show_complication2", "true")
            .keys("\"complicationStyle\": {\"color\": \"#3A86FF\", \"borderRadius\": 12, \"fontColor\": \"#FFD166\", \"textsize\": 26}")
            .view("complication2", 110, 170, 180, 60),
        lint = listOf("user slot 2"),
        regions = listOf(
            WHOLE,
            // A strip of the fill above the text and right of the icon, so the value does not count
            ParityCompare.Region("the fill", 1.0, intArrayOf(175, 172, 110, 6))
        )
    )

    // ---- always-on ----

    /**
     * The always-on look. The emulator cannot show always-on (its display goes off), so this checks
     * only the lint and the written `watchface.xml`: the clock, the hands and the glucose stay; the
     * static picture and the data picture are hidden in always-on.
     */
    @Test
    fun ambientLook() {
        check(
            ParityDesign("Ambient").withHands().keys("\"midColor\": \"#39FF14\"")
                .view("time", 100, 90, 200, 60, "\"textsize\": 48, \"gravity\": \"center\", \"font\": \"default\", \"fontColor\": \"#FFFFFF\"")
                .view("sgv", 100, 230, 200, 70, "\"textsize\": 56, \"gravity\": \"center\", \"font\": \"default\", \"fontColor\": \"#FFFFFF\"")
                .view("iob2", 120, 300, 160, 40, "\"textsize\": 28, \"gravity\": \"center\", \"font\": \"default\", \"fontColor\": \"#FFFFFF\"")
                .view("hour_hand", 0, 0, 400, 400).view("minute_hand", 0, 0, 400, 400),
            lint = listOf(live("time"), glucose("sgv"), data("iob2")),
            // Only the xml is checked: the other tests compare these parts in the active face
            regions = emptyList()
        )
        val xml = File("build/wff-parity/${javaClass.simpleName}/ambientLook/face/res/raw/watchface.xml").readText()
        val hide = """<Variant mode="AMBIENT" target="alpha" value="0" />"""
        assertWithMessage("the static picture hides in always-on:\n%s", xml).that(xml.substringAfter("<PartImage").substringBefore("</PartImage>")).contains(hide)
        assertWithMessage("the data picture hides in always-on:\n%s", xml).that(xml.substringAfter("name=\"Data\"").substringBefore("</ComplicationSlot>")).contains(hide)
        // The time view is a text with the hour by the 12 or 24 hour setting of the watch
        assertWithMessage("the clock stays in always-on:\n%s", xml).that(xml).contains("[IS_24_HOUR_MODE]")
        assertWithMessage("the clock stays in always-on:\n%s", xml).that(xml.substringAfter("[IS_24_HOUR_MODE]").substringBefore("</Condition>")).doesNotContain(hide)
        assertWithMessage("the glucose stays in always-on:\n%s", xml).that(xml.substringAfter("name=\"Glucose\"").substringBefore("</ComplicationSlot>")).doesNotContain(hide)
        assertWithMessage("the large hands stay in always-on:\n%s", xml).that(xml.substringAfter("<AnalogClock").substringBefore("</AnalogClock>")).doesNotContain(hide)
    }
}

/**
 * The design with hand pictures, by the file names our engine and the converter read (`HourHand`,
 * `MinuteHand`, `SecondHand`): each as large as the face, pointing to 12.
 */
private fun ParityDesign.withHands(): ParityDesign = picture("HourHand", hand(Color.WHITE, 14f, 105f))
    .picture("MinuteHand", hand(Color.rgb(76, 201, 240), 9f, 50f))
    .picture("SecondHand", hand(Color.rgb(247, 37, 133), 4f, 40f))

/** A bar from a little below the centre up to [tip], [width] wide. */
private fun hand(colour: Int, width: Float, tip: Float): ResData {
    val b = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour }
    Canvas(b).drawRoundRect(200f - width / 2, tip, 200f + width / 2, 220f, width / 2, width / 2, paint)
    return ResData(ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(), ResFormat.PNG)
}

/** A row of the lint: [name] is live in WFF. */
private fun live(name: String) = name.padEnd(NAME) + " live"

/** A row of the lint: [name] is in the data picture. */
private fun data(name: String) = name.padEnd(NAME) + " complication data"

/** A row of the lint: [name] is in the glucose slot. */
private fun glucose(name: String) = name.padEnd(NAME) + " complication glucose"

/** The width of a name in the lint (`WffPlan`). */
private const val NAME = 22

/** The text slot of D2 and Cyberpunk Tech: no fill, no ring, no icon colour, a zip font. */
private const val SLOT_TEXT_STYLE = "\"color\": \"#00000000\", \"borderRadius\": 0, \"ringPrimaryColor\": \"#00000000\", \"ringSecondaryColor\": \"#00000000\", " +
    "\"fontColor\": \"#F3E9B5\", \"iconColor\": \"#00000000\", \"textsize\": 26, \"font\": \"AuroraCut\", \"fontStyle\": \"normal\", \"gravity\": \"left\""
