// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.facetool.wff

import android.app.Application
import android.graphics.Color
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.ResData
import app.aaps.core.interfaces.rx.weardata.ResFormat
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.wear.watchfaces.animation.DesignRect
import app.aaps.wear.watchfaces.animation.WakeAnimationJson
import app.aaps.wear.watchfaces.wff.WffDesignParts
import app.aaps.wear.watchfaces.wff.WffPlanner
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.samsung.watchface.WatchFaceXmlValidator
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/**
 * The writer and the design copies of the converter to Watch Face Format faces, on a small design.
 * The whole converter (baking, Pack, the push validator, the emulator) runs in the watch face designs
 * repository, see its `docs/wff-apk.md`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WffConvertTest {

    private fun view(left: Int, top: Int, width: Int, height: Int, extra: String = "") =
        """{"leftmargin": $left, "topmargin": $top, "width": $width, "height": $height, "visibility": "visible"$extra}"""

    private val design = JSONObject(
        """{"metadata": {"name": "Small", "key_show_date": "true", "key_show_complication1": "true"}, "highColor": "#FFFF00", "midColor": "#00FF00", "lowColor": "#FF0000",
            "background": ${view(0, 0, 400, 400)},
            "sgv": ${view(40, 150, 160, 80, ", \"textsize\": 60, \"gravity\": \"right\"")},
            "direction": ${view(210, 160, 60, 60)},
            "delta": ${view(270, 160, 60, 30, ", \"textsize\": 20")},
            "timestamp": ${view(270, 190, 60, 30, ", \"textsize\": 20, \"dynData\": \"age\"")},
            "time": ${view(100, 60, 200, 70, ", \"textsize\": 60, \"background\": \"Frame\"")},
            "day_name": ${view(150, 30, 100, 24, ", \"textsize\": 18, \"allCaps\": true")},
            "complication1": ${view(150, 300, 100, 40, ", \"color\": \"#40000000\"")},
            "iob1": ${view(20, 240, 60, 30)},
            "chart": ${view(40, 340, 320, 50)},
            "cover_plate": ${view(0, 0, 400, 400)},
            "hour_hand": ${view(0, 0, 400, 400)},
            "dynData": {"age": {"valueKey": "timestamp", "minData": 0, "maxData": 20, "textvalue1": "%.0f'", "invalidTextvalue": "--", "fontColor1": "#FFFFFF", "fontColor2": "#FF0000"}},
            "animation": {"layers": [
                {"text": "clock()", "x": "200", "y": "260", "size": 30, "align": "center"},
                {"text": "glucose(v_sgv)", "x": "200", "y": "290", "align": "center", "colors": ["#FF0000", "#00FF00", "#FFFF00"], "colorIndex": "v_sgvLevel + 1"},
                {"shape": "circle", "count": 20, "x": "u * 400", "y": "100 + 10 * sin(t)", "slot": "front"},
                {"shape": "circle", "x": "100 + v_iob", "y": "100"}
            ]}}"""
    )

    private val animation = WakeAnimationJson({ text, default -> runCatching { Color.parseColor(text) }.getOrDefault(default) }) { null }.read(design)
    private val plan = WffPlanner().plan(design, animation)

    private val assets = WffAssets(
        statics = mapOf(0 to WffAssets.Picture("static_0", DesignRect.FULL), 1 to WffAssets.Picture("static_1", DesignRect(10f, 10f, 380f, 380f))),
        loops = listOf(
            WffAssets.Loop(layers = setOf(2), frames = listOf("loop0_000", "loop0_001"), fps = 15, box = DesignRect(0f, 80f, 400f, 40f), loopCount = 3, blend = "PLUS", condition = "[MONTH] == 12 && [DAY] >= 24"),
            WffAssets.Loop(layers = emptySet(), frames = listOf("sky_000", "sky_001"), fps = 6, box = DesignRect(0f, 0f, 400f, 200f), overRun = 0)
        )
    )

    private fun valid(xml: String, version: Int): Boolean = WatchFaceXmlValidator().validate(File.createTempFile("watchface", ".xml").apply { writeText(xml) }.path, version.toString())

    @Test
    fun aSmallDesignGivesAValidFace() {
        val face = WffFaceWriter(providerApp = "info.nightscout.androidaps").write(design, animation, plan, assets)
        val file = File.createTempFile("watchface", ".xml").apply { writeText(face.xml) }
        assertWithMessage("%s", face.xml).that(WatchFaceXmlValidator().validate(file.path, "4")).isTrue()
        val xml = face.xml
        // The pictures in paint order: the bottom one, the data picture, the one over it, and the loop on top
        val order = listOf("\"static_0\"", "WffDataComplication", "\"static_1\"", "\"loop0_000\"").map { xml.indexOf(it) }
        assertThat(order).isInOrder()
        assertThat(order.first()).isAtLeast(0)
        assertThat(xml).contains("""<PartAnimatedImage x="0" y="80" width="400" height="40" blendMode="PLUS">""")
        assertThat(xml).contains("""<Expression name="days">[MONTH] == 12 &amp;&amp; [DAY] &gt;= 24</Expression>""")
        // The bottom picture in motion lies right over it, under the data picture
        assertThat(xml.indexOf("\"sky_000\"")).isGreaterThan(xml.indexOf("\"static_0\""))
        assertThat(xml.indexOf("\"sky_000\"")).isLessThan(xml.indexOf("WffDataComplication"))
        // The glucose in its slot, coloured by the thresholds, twice (the view and the text layer); the arrow, the delta
        assertThat(xml).contains("""slotId="10"""")
        assertThat(xml).contains("info.nightscout.androidaps/app.aaps.wear.complications.wff.WffGlucoseComplication")
        assertThat(xml).contains("[COMPLICATION.MONOCHROMATIC_IMAGE]")
        assertThat(xml).contains("[COMPLICATION.TITLE]")
        assertThat(xml).contains("[COMPLICATION.RANGED_VALUE_VALUE] &gt; 180")
        assertThat(Regex("COMPLICATION.TEXT").findAll(xml).count()).isAtLeast(6)
        // The age: whole minutes with the design's sign, white below 10 minutes and red from there
        assertThat(xml).contains("WffGlucoseAgeComplication")
        assertThat(xml).contains("<Template>%s'<Parameter expression=\"floor([COMPLICATION.RANGED_VALUE_VALUE])\" />")
        assertThat(xml).contains("[COMPLICATION.RANGED_VALUE_VALUE] &lt; 10</Expression>")
        assertThat(xml).contains(">--</Font>")
        // The live parts
        // The time view is a text of the hour and the minute (a TimeText spaces its signs wider), the clock() layer a TimeText
        assertThat(Regex("<TimeText").findAll(xml).count()).isEqualTo(1)
        assertThat(xml).contains("[MINUTE_Z]")
        // The wearer's slot starts with the heart rate, as on the code-based face, because the design turns it on.
        // It comes from the watch itself: AAPS has no heart rate provider any more.
        assertThat(xml).contains("""slotId="1"""")
        assertThat(xml).contains("""<DefaultProviderPolicy defaultSystemProvider="HEART_RATE" defaultSystemProviderType="SHORT_TEXT" />""")
        assertThat(xml).doesNotContain("app.aaps.wear.health")
        assertThat(xml).contains("<Upper>")
        assertThat(xml).contains("<HourHand")
        assertThat(face.strings.keys).containsAtLeast("app_name", "slot_1", "glucose", "glucose_age", "data")
        assertThat(face.notWritten).isEmpty()
    }

    @Test
    fun versionTwoLeavesOutTheFramesThatNeedABlend() {
        val face = WffFaceWriter(providerApp = "info.nightscout.androidaps", version = WffFaceWriter.VERSION_2).write(design, animation, plan, assets)
        assertWithMessage("%s", face.xml).that(valid(face.xml, 2)).isTrue()
        // The face of version 4 has the blend, which the schema of version 2 does not allow
        assertThat(valid(WffFaceWriter(providerApp = "info.nightscout.androidaps").write(design, animation, plan, assets).xml, 2)).isFalse()
        assertThat(face.xml).doesNotContain("blendMode")
        assertThat(face.xml).doesNotContain("\"loop0_000\"")
        // Frames without a blend stay, and so does the rest of the face
        assertThat(face.xml).contains("\"sky_000\"")
        assertThat(face.xml).contains("WffGlucoseComplication")
        assertThat(face.xml).contains("""defaultSystemProvider="HEART_RATE"""")
        assertThat(face.notWritten).containsExactly("the wake frames of the layers [2]: they add light, and WFF 2 has no blendMode")
    }

    @Test
    fun theDataZipLoadsAsADesignZipAndKeepsTheArrows() {
        val png = WffDesignParts.CLEAR_PNG
        val res = listOf("CustomWatchface", "Background", "BackgroundHigh", "ArrowFlat", "ArrowDoubleUp", "Frame").associateWith { ResData(byteArrayOf(1, 2, 3), ResFormat.PNG) }
        val source = CwfData(design.toString(), mutableMapOf(), res.toMutableMap())
        val bytes = WffFaceZip.dataZip(source, WffDesignParts(design, animation, plan))
        val data = ZipWatchfaceFormat.loadCustomWatchface(bytes, WffFaceZip.DATA_ZIP, authorization = true)?.cwfData
        assertThat(data).isNotNull()
        // The arrows stay, though the arrow of this design is live in the glucose slot
        assertThat(data!!.resData.keys).containsExactly("CustomWatchface", "Background", "ArrowFlat", "ArrowDoubleUp", "Frame")
        assertThat(data.resData.getValue("Background").value).isEqualTo(png)
        val json = JSONObject(data.json)
        assertThat(json.getJSONObject("metadata").getString("name")).isEqualTo("Small")
        assertThat(json.getJSONObject("chart").getString("visibility")).isEqualTo("visible")
        assertThat(json.getJSONObject("sgv").getString("visibility")).isEqualTo("gone")
        assertThat(json.getJSONObject("direction").getString("visibility")).isEqualTo("gone")
    }

    @Test
    fun theFaceZipHoldsTheEntriesOfFormatOne() {
        val dir = Files.createTempDirectory("facezip").toFile()
        val apk4 = File(dir, "a4.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val apk2 = File(dir, "a2.apk").apply { writeBytes(byteArrayOf(4, 5)) }
        val file = File(dir, WffFaceZip.nameOf("Small.zip"))
        val app = "info.nightscout.aapsclient"
        WffFaceZip.write(file, "Small", byteArrayOf(9), listOf(WffFaceZip.Face(app, 4, apk4, "token4"), WffFaceZip.Face(app, 2, apk2, null)))
        assertThat(file.name).isEqualTo("Small-wff.zip")
        val entries = ZipFile(file).use { zip -> zip.entries().asSequence().toList().associate { it.name to zip.getInputStream(it).readBytes() } }
        assertThat(entries.keys).containsExactly("face.json", "data.zip", "wff4/$app.apk", "wff4/$app.token", "wff2/$app.apk").inOrder()
        val faceJson = JSONObject(String(entries.getValue("face.json"), Charsets.UTF_8))
        assertThat(faceJson.getInt("format")).isEqualTo(1)
        assertThat(faceJson.getString("name")).isEqualTo("Small")
        assertThat(String(entries.getValue("wff4/$app.token"), Charsets.UTF_8)).isEqualTo("token4")
        assertThat(entries.getValue("wff2/$app.apk")).isEqualTo(byteArrayOf(4, 5))
        assertThat(WffFaceZip.packageOf(app)).isEqualTo("info.nightscout.aapsclient.watchfacepush.design")
        dir.deleteRecursively()
    }

    @Test
    fun aLoopWithItsFadeWritesTheFadeInTheLoopAndTheFadeOut() {
        val box = DesignRect(0f, 80f, 400f, 40f)
        val assets = WffAssets(
            statics = emptyMap(),
            loops = listOf(
                WffAssets.Loop(layers = setOf(2), frames = listOf("loop0_000", "loop0_001"), fps = 15, box = box),
                WffAssets.Loop(layers = setOf(2), frames = listOf("loop1_000", "loop1_001"), fps = 15, box = box, loopCount = 2, delaySeconds = 1.5f),
                WffAssets.Loop(layers = setOf(2), frames = listOf("loop2_000", "loop2_001"), fps = 15, box = box, delaySeconds = 4.5f)
            )
        )
        val xml = WffFaceWriter(providerApp = "info.nightscout.androidaps").write(design, animation, plan, assets).xml
        val file = File.createTempFile("watchface", ".xml").apply { writeText(xml) }
        assertWithMessage("%s", xml).that(WatchFaceXmlValidator().validate(file.path, "4")).isTrue()
        // All three, in the order they play
        val order = listOf("loop0_000", "loop1_000", "loop2_000").map { xml.indexOf("\"$it\"") }
        assertThat(order.first()).isAtLeast(0)
        assertThat(order).isInOrder()
        assertThat(xml).contains("loopCount=\"2\" delayPlay=\"1.5\"")
        assertThat(xml).contains("loopCount=\"1\" delayPlay=\"4.5\"")
    }

    @Test
    fun eachCopyOfTheDesignShowsOnlyItsParts() {
        val parts = WffDesignParts(design, animation, plan)
        assertThat(parts.isUsable).isTrue()
        fun shown(copy: JSONObject) = copy.keys().asSequence().filter { copy.optJSONObject(it)?.optString("visibility") == "visible" }.toSet()
        fun visibleLayers(copy: JSONObject) = copy.getJSONObject("animation").getJSONArray("layers").let { l -> (0 until l.length()).filter { l.getJSONObject(it).optString("visible") != "0" } }

        val bottom = parts.static(0)
        // The time stays for its frame picture, without its text
        assertThat(shown(bottom)).containsExactly("background", "time")
        assertThat(bottom.getJSONObject("time").getString("fontColor")).isEqualTo("#00000000")
        assertThat(visibleLayers(bottom)).isEmpty()

        assertThat(shown(parts.static(1))).containsExactly("background", "cover_plate")
        assertThat(parts.static(1).getJSONObject("background").has("color")).isFalse()

        val data = parts.data()
        assertThat(shown(data)).containsExactly("background", "iob1", "chart")
        assertThat(visibleLayers(data)).containsExactly(3)

        assertThat(visibleLayers(parts.layers(setOf(2)))).containsExactly(2)
        assertThat(shown(parts.layers(setOf(2)))).containsExactly("background")
    }

    @Test
    fun aSeeThroughCopyGetsAClearBackgroundPicture() {
        val source = CwfData(design.toString(), mutableMapOf(), mutableMapOf())
        val data = WffDesignParts.dataOf(source)!!
        assertThat(data.resData.keys).contains("Background")
        // The picture is really clear: a wrong pixel here once put a blue wash over the whole face
        val pixel = ImageIO.read(ByteArrayInputStream(data.resData.getValue("Background").value))
        assertThat(pixel.width).isEqualTo(1)
        assertThat(pixel.getRGB(0, 0) ushr 24).isEqualTo(0)
        assertThat(JSONObject(data.json).getJSONObject("sgv").getString("visibility")).isEqualTo("gone")
        // A design with nothing for the data picture has none
        val plain = JSONObject("""{"background": ${view(0, 0, 400, 400)}, "time": ${view(100, 60, 200, 70)}}""")
        assertThat(WffDesignParts.dataOf(CwfData(plain.toString(), mutableMapOf(), mutableMapOf()))).isNull()
    }
}
