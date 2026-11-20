package app.aaps.watchfaces.wff

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.shared.impl.weardata.toDrawable
import app.aaps.wear.watchfaces.animation.LayerBlend
import app.aaps.wear.watchfaces.animation.WakeAnimationJson
import app.aaps.wear.watchfaces.wff.WffAssets
import app.aaps.wear.watchfaces.wff.WffDesignParts
import app.aaps.wear.watchfaces.wff.WffFaceWriter
import app.aaps.wear.watchfaces.wff.WffPlanner
import app.aaps.wear.watchfaces.wff.WffTier
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * Turns a design zip into a Watch Face Format face APK, see `docs/wff-apk.md`: plan, bake the static
 * pictures and the wake loops with the watch's own engine, write `watchface.xml`, build the APK with
 * Pack, and check it with the Watch Face Push validator.
 *
 * @param client the application id of the wear app that installs the face and feeds its slots
 * @param packCli the `pack-cli` binary, see `tools/build-pack.sh`
 * @param key the PEM file with the signing certificate and key, see `tools/build-pack.sh`
 */
class WffApkBuilder(private val app: Application, private val baker: WffBaker, private val client: String, private val packCli: File, private val key: File) {

    /**
     * What came out for one design.
     *
     * @param apk the face, or null when it was not built (the lint says why)
     * @param token the validator's token, or null when the face did not pass
     */
    class Result(val lint: String, val apk: File?, val token: String?, val design: CwfData)

    fun build(zip: File, dir: File): Result {
        val cwf = ZipWatchfaceFormat.loadCustomWatchface(zip.readBytes(), zip.name, authorization = true)?.cwfData ?: error("${zip.name} is not a design zip")
        val design = JSONObject(cwf.json)
        val animation = WakeAnimationJson({ text, default -> runCatching { Color.parseColor(text) }.getOrDefault(default) }) { null }.read(design)
        val planner = WffPlanner()
        val first = planner.plan(design, animation)
        dir.deleteRecursively()
        dir.mkdirs()
        val firstParts = WffDesignParts(design, animation, first)
        if (!first.canBuild || !firstParts.isUsable) {
            val lint = first.lint() + if (!firstParts.isUsable) "!! the layers of the animation have errors, so no picture can be made\n" else ""
            File(dir, "lint.txt").writeText(lint)
            return Result(lint, null, null, cwf)
        }
        val drawable = File(dir, "face/res/drawable")
        val notes = ArrayList<String>()

        // A wake layer that also shows at rest is a part of the static art: only its rest pose is kept
        val moving = first.items.filter { it.loop }.mapNotNull { it.layer }
        val still = moving.filter { layer -> baker.rest(WffDesignParts.asCwf(cwf, firstParts.layers(listOf(layer)), transparent = true)).let { rest -> (WffBaker.inkBox(rest, 1) != null).also { rest.recycle() } } }.toSet()
        val plan = planner.settle(first, still)
        val parts = WffDesignParts(design, animation, plan)

        // The static pictures, each cut to what it shows
        val statics = HashMap<Int, WffAssets.Picture>()
        for (run in 0 until plan.staticRuns) {
            val picture = baker.rest(WffDesignParts.asCwf(cwf, parts.static(run), transparent = run > 0))
            val box = WffBaker.inkBox(picture, SNAP) ?: continue
            WffBaker.crop(picture, box).savePng(File(drawable, "static_$run.png"))
            statics[run] = WffAssets.Picture("static_$run", WffBaker.designRect(box, baker.size))
        }

        // The wake loops of the layers that show only in the wake
        val wakeMs = (animation?.durationMs ?: 0L)
        val loops = ArrayList<WffAssets.Loop>()
        var budget = MAX_FRAMES
        // Layers that add light are framed on black and played with that blend
        val blendOf = (moving - still).associateWith { layer -> animation?.layers?.getOrNull(layer)?.blend?.takeIf { it == LayerBlend.ADD || it == LayerBlend.SCREEN }?.wff }
        val spans = (moving - still).associateWith { layer -> span(WffDesignParts.asCwf(cwf, parts.layers(listOf(layer)), transparent = true), wakeMs, blendOf[layer] != null) }
        val shown = spans.filterValues { it != null }.mapValues { it.value!! }
        (moving - still - shown.keys).forEach { notes += "L$it: it never shows in the wake, so it has no frames" }
        // Layers that show for the whole wake share one short loop per slot; a layer that shows for a part of it plays once
        val whole = shown.filterValues { (from, to) -> to - from >= wakeMs * WHOLE_WAKE }.keys
        val groups = whole.groupBy { layer -> plan.items.first { it.layer == layer }.slot to blendOf[layer] }.values.map { it.toSet() to null } +
            (shown.keys - whole).map { setOf(it) to shown.getValue(it) }
        for ((layers, span) in groups.sortedBy { it.first.min() }) {
            val copy = WffDesignParts.asCwf(cwf, parts.layers(layers), transparent = true)
            val seconds = if (span == null) minOf(LOOP_MS, wakeMs) / 1000.0 else (span.second - span.first) / 1000.0
            val fps = if (span == null) LOOP_FPS else (budget / maxOf(seconds, 0.1)).toInt().coerceIn(1, LOOP_FPS)
            val count = minOf(budget, ceil(seconds * fps).toInt())
            if (count < 2) {
                notes += "the layers $layers have no frames: the budget of $MAX_FRAMES frames is used up"
                continue
            }
            // A loop is taken after the fade in, so it has the full strength
            val start = span?.first ?: minOf(animation?.fadeMs ?: 0L, wakeMs / 4)
            val blend = blendOf[layers.first()]
            val frames = baker.wake(copy, (0 until count).map { start + it * 1000L / fps }, onBlack = blend != null).filterNotNull()
            // On black the box is where the frames are not black
            val boxes = frames.map { if (blend != null) WffBaker.lightBox(it, SNAP) else WffBaker.inkBox(it, SNAP) }
            val box = boxes.reduceOrNull(WffBaker::union)
            if (box == null || frames.size < 2) {
                notes += "the layers $layers show nothing in their frames"
                continue
            }
            val index = loops.size
            val names = frames.mapIndexed { i, frame -> "loop${index}_%03d".format(i).also { WffBaker.crop(frame, box).savePng(File(drawable, "$it.png")); frame.recycle() } }
            budget -= names.size
            loops += WffAssets.Loop(
                layers = layers, frames = names, fps = fps, box = WffBaker.designRect(box, baker.size),
                loopCount = if (span == null) ceil(wakeMs / (names.size * 1000.0 / fps)).toInt().coerceAtLeast(1) else 1,
                delaySeconds = if (span == null) 0f else span.first / 1000f,
                blend = blend
            )
        }

        val face = WffFaceWriter(providerApp = client).write(design, animation, plan, WffAssets(statics, loops))
        val lint = plan.lint() + notes.joinToString("") { " note: $it\n" } + face.notWritten.joinToString("") { " ! not written: $it\n" } +
            "Pictures: ${statics.size} static, ${loops.size} loops with ${loops.sumOf { it.frames.size }} frames of $MAX_FRAMES\n"
        File(dir, "lint.txt").writeText(lint)

        // The face folder that Pack reads
        val res = File(dir, "face/res")
        File(res, "raw").mkdirs()
        File(res, "raw/watchface.xml").writeText(face.xml)
        File(res, "xml").mkdirs()
        File(res, "xml/watch_face_info.xml").writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<WatchFaceInfo><Preview value=\"@drawable/preview\" /><MultipleInstancesAllowed value=\"false\" /><Editable value=\"true\" /></WatchFaceInfo>\n"
        )
        File(res, "values").mkdirs()
        File(res, "values/strings.xml").writeText("<resources>\n" + face.strings.entries.joinToString("") { (name, text) -> "    <string name=\"$name\">${escape(text)}</string>\n" } + "</resources>\n")
        baker.rest(cwf).savePng(File(drawable, "preview.png"))
        for ((file, resource) in face.hands) {
            val hand = cwf.resData[file]?.toDrawable(app.resources) ?: continue
            Bitmap.createBitmap(baker.size, baker.size, Bitmap.Config.ARGB_8888).also { b ->
                hand.setBounds(0, 0, baker.size, baker.size)
                hand.draw(Canvas(b))
            }.savePng(File(drawable, "$resource.png"))
        }
        for ((file, resource) in face.fonts) {
            val font = cwf.resData[file] ?: continue
            File(res, "font/$resource.${font.format.extension}").apply { parentFile?.mkdirs() }.writeBytes(font.value)
        }
        File(dir, "face/AndroidManifest.xml").writeText(manifest())

        // Pack: an aligned and signed APK. It prints "Error:" and still ends with 0 on a mistake.
        val pack = run(listOf(packCli.path, File(dir, "face").path, File(dir, "face").path, key.path))
        val apk = File(dir, "face.apk")
        File(dir, "face.aab").delete()
        if (!pack.contains("Compiled, aligned & signed successfully") || !apk.isFile) {
            File(dir, "lint.txt").appendText("!! Pack failed:\n$pack\n")
            return Result(lint + "!! Pack failed:\n$pack\n", null, null, cwf)
        }

        // The validator of Watch Face Push, as the AAPS wear build runs it for its own faces
        val validator = run(listOf(File(System.getProperty("java.home"), "bin/java").path, "-cp", validatorJar(), "com.google.android.wearable.watchface.validator.cli.DwfValidation", "-a", apk.path, "-p", client))
        val token = TOKEN.find(validator)?.groupValues?.get(1)
        File(dir, "validator.txt").writeText(validator)
        token?.let { File(dir, "token.txt").writeText(it) }
        return Result(lint, apk, token, cwf)
    }

    /** From when to when [design] shows something in its wake, in ms, looked at every [SPAN_STEP_MS]; null when it never does. */
    private fun span(design: CwfData, wakeMs: Long, onBlack: Boolean): Pair<Long, Long>? {
        val times = (0..wakeMs / SPAN_STEP_MS).map { it * SPAN_STEP_MS }
        val seen = baker.wake(design, times, onBlack).mapIndexedNotNull { i, frame ->
            frame?.let { f -> times[i].takeIf { (if (onBlack) WffBaker.lightBox(f, 1) else WffBaker.inkBox(f, 1)) != null }.also { f.recycle() } }
        }
        if (seen.isEmpty()) return null
        return maxOf(0L, seen.first() - SPAN_STEP_MS) to minOf(wakeMs, seen.last() + SPAN_STEP_MS)
    }

    private fun manifest() = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$client.watchfacepush.design" android:versionCode="1" android:versionName="1">
    <uses-sdk android:minSdkVersion="33" android:targetSdkVersion="36" />
    <uses-feature android:name="android.hardware.type.watch" />
    <application android:label="@string/app_name" android:hasCode="false">
        <property android:name="com.google.wear.watchface.format.version" android:value="4" />
        <property android:name="com.google.wear.watchface.format.publisher" android:value="AAPS" />
    </application>
</manifest>
"""

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(5, TimeUnit.MINUTES)
        return output
    }

    /** The validator jar is on the test class path (a test dependency of this project). */
    private fun validatorJar(): String =
        System.getProperty("java.class.path").split(File.pathSeparator).firstOrNull { "validator-push-cli" in it } ?: error("the validator is not on the class path")

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "\\'")

    private companion object {

        /** All frames of the wake loops of one face together. */
        const val MAX_FRAMES = 150

        /** The length and the rate of a loop of layers that show for the whole wake. */
        const val LOOP_MS = 2000L
        const val LOOP_FPS = 15

        /** A layer that shows for at least this part of the wake counts as showing for the whole wake. */
        const val WHOLE_WAKE = 0.8

        /** How closely the wake is looked at to find when a layer shows. */
        const val SPAN_STEP_MS = 500L

        /** Pictures are cut on a grid of this many pixels: 9 of 450 pixels are 8 design pixels, so every box is whole. */
        const val SNAP = 9

        val TOKEN = Regex("generated token: (\\S+)")
    }
}
