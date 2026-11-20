package app.aaps.facetool.wff

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.shared.impl.weardata.toDrawable
import app.aaps.wear.R
import app.aaps.wear.watchfaces.animation.WakeAnimationJson
import app.aaps.wear.watchfaces.wff.WffDesignParts
import app.aaps.wear.watchfaces.wff.WffPlanner
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.MonthDay
import java.time.ZoneId
import java.util.concurrent.TimeUnit

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

    fun build(zip: File, dir: File): Result =
        build(ZipWatchfaceFormat.loadCustomWatchface(zip.readBytes(), zip.name, authorization = true)?.cwfData ?: error("${zip.name} is not a design zip"), dir)

    /** The face of the design [cwf], see [build]. */
    fun build(cwf: CwfData, dir: File): Result {
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
        val face = File(dir, "face")
        val notes = ArrayList<String>()
        // Lines of the lint where the face loses something the design shows
        val warnings = ArrayList<String>()

        // A wake layer that also shows at rest is a part of the static art: only its rest pose is kept
        val moving = first.items.filter { it.loop }.mapNotNull { it.layer }
        val still = moving.filter { layer -> baker.rest(WffDesignParts.asCwf(cwf, firstParts.layers(listOf(layer)), transparent = true)).let { rest -> (WffPictures.inkBox(WffPictures.pixelsOf(rest), 1) != null).also { rest.recycle() } } }.toSet()
        val plan = planner.settle(first, still)
        val parts = WffDesignParts(design, animation, plan)

        // The static pictures, each cut to what it shows
        val statics = HashMap<Int, WffAssets.Picture>()
        val rests = HashMap<Int, Bitmap>()
        for (run in 0 until plan.staticRuns) {
            val picture = baker.rest(WffDesignParts.asCwf(cwf, parts.static(run), transparent = run > 0))
            val box = WffPictures.inkBox(WffPictures.pixelsOf(picture), WffPictures.SNAP) ?: continue
            save(WffBaker.crop(picture, box), face, "static_$run")
            statics[run] = WffAssets.Picture("static_$run", WffPictures.designRect(box, baker.size))
            rests[run] = picture
        }
        save(baker.rest(cwf), face, "preview")

        val wakeMs = (animation?.durationMs ?: 0L)
        val frameRules = WffLoops(wakeMs, animation?.fadeMs ?: 0L)
        val loops = ArrayList<WffAssets.Loop>()

        // A static picture with layers that also move in the wake: the whole picture in motion, from the
        // start of the wake to its end, so it starts and ends at rest
        val movingRuns = plan.items.filter { it.layer in still && it.run != null }.groupBy { it.run!! }
        for ((run, items) in movingRuns.toSortedMap()) {
            val rest = rests[run] ?: continue
            if (!WffLoops.runCanMove(run)) {
                notes += "picture $run: it lies over other parts, so its moving layers keep their rest pose"
                continue
            }
            val take = frameRules.runTake()
            if (take == null) {
                notes += "picture $run: no frames left in the budget, so its moving layers keep their rest pose"
                continue
            }
            val fps = take.fps
            val frames = baker.wake(WffDesignParts.asCwf(cwf, parts.static(run), transparent = false), take.atMs, keepBackground = true).filterNotNull()
            val restPixels = WffPictures.pixelsOf(rest)
            val box = frames.map { WffPictures.diffBox(WffPictures.pixelsOf(it), restPixels, WffPictures.SNAP) }.reduceOrNull(WffPictures::union)
            if (box == null || frames.size < 2) {
                frames.forEach { it.recycle() }
                continue
            }
            val names = frames.mapIndexed { i, frame -> "run${run}_%03d".format(i).also { save(WffBaker.crop(frame, box), face, it); frame.recycle() } }
            frameRules.spend(names.size)
            loops += WffAssets.Loop(layers = items.mapNotNull { it.layer }.toSet(), frames = names, fps = fps, box = WffPictures.designRect(box, baker.size), overRun = run)
            notes += "picture $run moves in the wake: ${names.size} frames at $fps a second"
        }
        rests.values.forEach { it.recycle() }

        // The wake loops of the layers that show only in the wake. A layer that shows only on some days
        // (Santa at Christmas) is made last, on such a day, and plays only on those days.
        val inWake = moving - still
        val days = inWake.associateWith { layer -> animation?.layers?.getOrNull(layer)?.let { WffShowDays.of(design, animation, it) } }
        days.filterValues { it != null && it.isEmpty() }.keys.forEach { notes += "L$it: with the default options it never shows, so it has no frames" }
        val onDays = days.filterValues { !it.isNullOrEmpty() }.mapValues { it.value!! }
        // Layers that add light are framed on black and played with that blend
        val blendOf = inWake.associateWith { layer -> WffPictures.lightBlend(animation?.layers?.getOrNull(layer)?.blend) }
        val spans = (inWake - days.keys.filter { days[it] != null }.toSet()).associateWith { layer -> span(WffDesignParts.asCwf(cwf, parts.layers(listOf(layer)), transparent = true), frameRules, blendOf[layer] != null) }
        val shown = spans.filterValues { it != null }.mapValues { it.value!! }
        spans.filterValues { it == null }.keys.forEach { notes += "L$it: it never shows in the wake, so it has no frames" }
        /** Draws the frames of [take] for [layers] and adds them as a loop. */
        fun frames(layers: Set<Int>, take: WffLoops.Take, condition: String?) {
            val blend = blendOf[layers.first()]
            val frames = baker.wake(WffDesignParts.asCwf(cwf, parts.layers(layers), transparent = true), take.atMs, onBlack = blend != null).filterNotNull()
            val box = frames.map { WffPictures.shownBox(WffPictures.pixelsOf(it), WffPictures.SNAP, onBlack = blend != null) }.reduceOrNull(WffPictures::union)
            if (box == null || frames.size < 2) {
                frames.forEach { it.recycle() }
                notes += "the layers $layers show nothing in their frames"
                return
            }
            val index = loops.size
            val names = frames.mapIndexed { i, frame -> "loop${index}_%03d".format(i).also { save(WffBaker.crop(frame, box), face, it, addsLight = blend != null); frame.recycle() } }
            frameRules.spend(names.size)
            loops += WffAssets.Loop(
                layers = layers, frames = names, fps = take.fps, box = WffPictures.designRect(box, baker.size),
                loopCount = frameRules.loopCount(take, names.size), delaySeconds = take.delaySeconds, blend = blend, condition = condition
            )
        }
        fun frameLoop(layers: Set<Int>, span: WffLoops.Span?, condition: String?) {
            val take = frameRules.take(span)
            if (take == null) {
                warnings += "the layers $layers have no frames: the budget of ${WffLoops.MAX_FRAMES} frames is used up, they do not show"
                return
            }
            if (take.cut) warnings += "the loop of the layers $layers is cut to ${take.atMs.size} frames (${"%.2f".format(take.atMs.size.toDouble() / take.fps)} s): the budget of ${WffLoops.MAX_FRAMES} frames is used up"
            // A loop of the whole wake gets the fade of the wake: frames of the fade in before it and of the fade out after it
            val faded = frameRules.faded(take)
            if (faded != null) notes += "the layers $layers fade in and out with the wake: ${faded.fadeIn.atMs.size} frames each"
            else if (span == null && (animation?.fadeMs ?: 0L) > 0L)
                warnings += "the layers $layers lose the fade in and out of the wake: the budget of ${WffLoops.MAX_FRAMES} frames has no room for it, so their loop shows at full strength from the start to the end"
            for (part in faded?.let { listOf(it.fadeIn, it.loop, it.fadeOut) } ?: listOf(take)) frames(layers, part, condition)
        }

        for (group in frameRules.groups(shown, plan, blendOf)) frameLoop(group.layers, group.span, null)
        for ((layer, showDays) in onDays) {
            // The next of its days, at noon: the clock of the baker only goes forward. These layers are
            // made last, so nothing else of this design is drawn on that day; each design is made in
            // a test JVM of its own (build.gradle.kts), so the next design starts at the real time again.
            val now = Instant.ofEpochMilli(baker.now).atZone(ZoneId.systemDefault()).toLocalDate()
            // Within 8 years, for a layer of 29 February
            val day = (0L..366L * 8).asSequence().map { now.plusDays(it) }.firstOrNull { MonthDay.from(it) in showDays }
            if (day == null) {
                notes += "L$layer: none of its days comes within 8 years, so it has no frames"
                continue
            }
            baker.moveTo(day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            val span = span(WffDesignParts.asCwf(cwf, parts.layers(listOf(layer)), transparent = true), frameRules, blendOf[layer] != null)
            if (span == null) notes += "L$layer: it shows nothing on $day either" else {
                // A layer that shows for the whole wake on its days repeats a short loop, as the others
                frameLoop(setOf(layer), span.takeUnless { frameRules.isWholeWake(it) }, WffShowDays.condition(showDays))
                notes += "L$layer: shows only on ${showDays.size} days of the year: its frames play only then"
            }
        }

        val written = WffFaceWriter(providerApp = client, measure = PaintFontMeasure(cwf), faceSize = baker.size).write(design, animation, plan, WffAssets(statics, loops))
        val lint = plan.lint() + notes.joinToString("") { " note: $it\n" } + warnings.joinToString("") { " ! $it\n" } + written.notWritten.joinToString("") { " ! not written: $it\n" } +
            "Pictures: ${statics.size} static, ${loops.size} loops with ${loops.sumOf { it.frames.size }} frames of ${WffLoops.MAX_FRAMES}\n"
        File(dir, "lint.txt").writeText(lint)

        // The face folder that Pack reads
        write(face, WffResources.WATCH_FACE_XML, written.xml)
        write(
            face, WffResources.WATCH_FACE_INFO_XML,
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<WatchFaceInfo><Preview value=\"@drawable/preview\" /><MultipleInstancesAllowed value=\"false\" /><Editable value=\"true\" /></WatchFaceInfo>\n"
        )
        write(face, WffResources.STRINGS_XML, "<resources>\n" + written.strings.entries.joinToString("") { (name, text) -> "    <string name=\"$name\">${escape(text)}</string>\n" } + "</resources>\n")
        for ((file, resource) in written.hands) {
            // A design without its own hand picture shows the hand of the wear app, as our engine does
            val hand = cwf.resData[file]?.toDrawable(app.resources) ?: DEFAULT_HANDS[file]?.let { app.resources.getDrawable(it, null) } ?: continue
            save(Bitmap.createBitmap(baker.size, baker.size, Bitmap.Config.ARGB_8888).also { b ->
                hand.setBounds(0, 0, baker.size, baker.size)
                hand.draw(Canvas(b))
            }, face, resource)
        }
        for ((file, resource) in written.fonts) {
            val font = cwf.resData[file] ?: continue
            File(face, WffResources.font(resource, font.format.extension)).apply { parentFile?.mkdirs() }.writeBytes(font.value)
        }
        write(face, WffResources.MANIFEST, manifest())

        // Pack: an aligned and signed APK. It prints "Error:" and still ends with 0 on a mistake.
        val pack = run(listOf(packCli.path, face.path, face.path, key.path))
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

    /** When [design] shows something in its wake, looked at at the moments of [frameRules]; null when it never does. */
    private fun span(design: CwfData, frameRules: WffLoops, onBlack: Boolean): WffLoops.Span? {
        val times = frameRules.spanTimes()
        val seen = baker.wake(design, times, onBlack).mapIndexedNotNull { i, frame ->
            frame?.let { f -> times[i].takeIf { WffPictures.shownBox(WffPictures.pixelsOf(f), 1, onBlack) != null }.also { f.recycle() } }
        }
        return frameRules.span(seen)
    }

    /** Writes [bitmap] into the face folder [face] as the picture [name], in the format and the place the converter wants. */
    private fun save(bitmap: Bitmap, face: File, name: String, addsLight: Boolean = false) {
        val encoded = WffPictures.encode(bitmap, addsLight)
        write(face, WffResources.picture(name, encoded.format), encoded.bytes)
    }

    private fun write(face: File, path: String, text: String) = write(face, path, text.toByteArray())

    private fun write(face: File, path: String, bytes: ByteArray) {
        File(face, path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
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

        val TOKEN = Regex("generated token: (\\S+)")

        /** The hands of the wear app that our engine draws when a design has none of its own. */
        val DEFAULT_HANDS = mapOf("HourHand" to R.drawable.hour_hand, "MinuteHand" to R.drawable.minute_hand, "SecondHand" to R.drawable.second_hand)
    }
}
