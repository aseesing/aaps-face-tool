package app.aaps.facetool.wff

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
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
 * @param editXml only for a test: changes the `watchface.xml` of each face before it is packed. The
 *   default leaves it as the converter wrote it, as for every real face.
 */
class WffApkBuilder(
    private val app: Application, private val baker: WffBaker, private val client: String, private val packCli: File, private val key: File,
    private val editXml: (String) -> String = { it }
) {

    /**
     * What came out for one design.
     *
     * @param apk the face, or null when it was not built (the lint says why)
     * @param token the validator's token, or null when the face did not pass
     */
    class Result(val lint: String, val apk: File?, val token: String?, val design: CwfData, val faceZip: File? = null)

    /**
     * What goes into the face zip ([WffFaceZip]) besides the face of [client].
     *
     * @param appIds the wear apps to make faces for, each in WFF 4 and 2
     * @param file where the zip goes
     */
    class FaceZip(val appIds: List<String>, val file: File)

    /**
     * A design as a face folder, before Pack: what [convert] made.
     *
     * @param face the face folder that Pack reads (WFF 4, for the wear app of the builder), or null when
     *   the design cannot be a face (the lint says why)
     */
    class Converted internal constructor(
        val lint: String, val face: File?, val design: CwfData,
        internal val parts: WffDesignParts?, internal val written: WffFaceWriter.Face?, internal val writer: ((String, Int) -> WffFaceWriter.Face)?
    )

    fun build(zip: File, dir: File, faceZip: FaceZip? = null): Result =
        build(ZipWatchfaceFormat.loadCustomWatchface(zip.readBytes(), zip.name, authorization = true)?.cwfData ?: error("${zip.name} is not a design zip"), dir, faceZip)

    /**
     * The face of the design [cwf] for [client] in WFF 4 (`face.apk`), see [build]. With [faceZip] also
     * the faces of its other wear apps and of WFF 2, and the face zip. [Result.faceZip] is null when a
     * face of the zip could not be made (the lint says why).
     */
    fun build(cwf: CwfData, dir: File, faceZip: FaceZip? = null): Result = pack(convert(cwf, dir), dir, faceZip)

    /**
     * The first half of [build]: plans the design [cwf], bakes its pictures and writes the face folder
     * `face` and `lint.txt` into [dir]. No Pack and no validator, so it needs neither of them.
     */
    fun convert(cwf: CwfData, dir: File): Converted {
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
            return Converted(lint, null, cwf, null, null, null)
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

        // The memory of the face: the frames of the wake must leave it under the limit of the validator
        val memory = WffMemory()

        // The static pictures, each cut to what it shows
        val statics = HashMap<Int, WffAssets.Picture>()
        val rests = HashMap<Int, Bitmap>()
        for (run in 0 until plan.staticRuns) {
            val picture = baker.rest(WffDesignParts.asCwf(cwf, parts.static(run), transparent = run > 0))
            val box = WffPictures.inkBox(WffPictures.pixelsOf(picture), WffPictures.SNAP) ?: continue
            save(WffBaker.crop(picture, box), face, "static_$run")
            statics[run] = WffAssets.Picture("static_$run", WffPictures.designRect(box, baker.size))
            rests[run] = picture
            memory.add(box.width, box.height)
        }
        save(baker.rest(cwf), face, PREVIEW)
        memory.add(baker.size, baker.size)

        val wakeMs = (animation?.durationMs ?: 0L)
        val frameRules = WffLoops(wakeMs, animation?.fadeMs ?: 0L)
        val loops = ArrayList<WffAssets.Loop>()

        // A static picture with layers that also move in the wake: the whole picture in motion, from the
        // start of the wake to its end, so it starts and ends at rest
        val movingPictures = ArrayList<MovingPicture>()
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
            // Counted at its lowest rate for now: the loops come first, see WffMemory
            memory.reserveRun(frames.size, box.width, box.height, fps)
            val names = frames.mapIndexed { i, frame -> "run${run}_%03d".format(i).also { save(WffBaker.crop(frame, box), face, it); frame.recycle() } }
            frameRules.spend(names.size)
            movingPictures += MovingPicture(run, loops.size, notes.size, fps)
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
            val step = memory.fit(frames.size, box.width, box.height, take.fps)
            if (step == null) {
                frames.forEach { it.recycle() }
                warnings += "the layers $layers have no frames: they do not fit in the memory of a face (${WffMemory.LIMIT_MB} MB of the ${WffMemory.VALIDATOR_MB} MB a face may have), they do not show"
                return
            }
            if (step > 1) warnings += "the loop of the layers $layers plays at ${take.fps / step} frames a second, not ${take.fps}: the memory of a face (${WffMemory.LIMIT_MB} MB) has no room for more"
            val index = loops.size
            // With fewer frames at a lower rate the loop lasts as long, so it repeats as often as with all of them
            val loopCount = frameRules.loopCount(take, frames.size)
            val names = thinned(frames, step).mapIndexed { i, frame -> "loop${index}_%03d".format(i).also { save(WffBaker.crop(frame, box), face, it, addsLight = blend != null); frame.recycle() } }
            frameRules.spend(names.size)
            loops += WffAssets.Loop(
                layers = layers, frames = names, fps = take.fps / step, box = WffPictures.designRect(box, baker.size),
                loopCount = loopCount, delaySeconds = take.delaySeconds, blend = blend, condition = condition
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

        // Now that the loops are made: each picture in motion gets the highest rate the memory still has
        // room for. The frames it leaves out are removed with the pictures the xml does not show.
        memory.settleRuns().forEachIndexed { k, step ->
            if (step == 1) return@forEachIndexed
            val moving = movingPictures[k]
            val loop = loops[moving.loop]
            val kept = loop.frames.filterIndexed { i, _ -> i % step == 0 }
            loops[moving.loop] = loop.copy(frames = kept, fps = moving.fps / step)
            notes[moving.note] = "picture ${moving.run} moves in the wake: ${kept.size} frames at ${moving.fps / step} a second"
            warnings += "picture ${moving.run} moves at ${moving.fps / step} frames a second, not ${moving.fps}: the memory of a face (${WffMemory.LIMIT_MB} MB of the ${WffMemory.VALIDATOR_MB} MB a face may have) has no room for more"
        }

        val assets = WffAssets(statics, loops)
        fun writtenFor(appId: String, version: Int) =
            WffFaceWriter(providerApp = appId, measure = PaintFontMeasure(cwf), faceSize = baker.size, version = version).write(design, animation, plan, assets)
        val written = writtenFor(client, WffFaceWriter.VERSION_4)
        val lint = plan.lint() + notes.joinToString("") { " note: $it\n" } + warnings.joinToString("") { " ! $it\n" } + written.notWritten.joinToString("") { " ! not written: $it\n" } +
            "Pictures: ${statics.size} static, ${loops.size} loops with ${loops.sumOf { it.frames.size }} frames of ${WffLoops.MAX_FRAMES}\n"
        File(dir, "lint.txt").writeText(lint)

        // The face folder that Pack reads
        write(
            face, WffResources.WATCH_FACE_INFO_XML,
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<WatchFaceInfo><Preview value=\"@drawable/$PREVIEW\" /><MultipleInstancesAllowed value=\"false\" /><Editable value=\"true\" /></WatchFaceInfo>\n"
        )
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
        writeFace(face, written, client, WffFaceWriter.VERSION_4)
        return Converted(lint, face, cwf, parts, written, ::writtenFor)
    }

    /**
     * The face folder of [converted] for the wear app [appId] in WFF [version], written into [folder]:
     * the same pictures, with the xml, the strings and the manifest of that app and version.
     */
    fun faceFolder(converted: Converted, folder: File, appId: String, version: Int): WffFaceWriter.Face {
        val face = converted.face ?: error("the design has no face folder")
        val other = converted.writer!!(appId, version)
        folder.deleteRecursively()
        face.copyRecursively(folder)
        writeFace(folder, other, appId, version)
        return other
    }

    /** The parts of the data picture of [converted] as a design zip (`data.zip` of the face zip). */
    fun dataZip(converted: Converted): ByteArray = WffFaceZip.dataZip(converted.design, converted.parts ?: error("the design has no face folder"))

    /**
     * The second half of [build]: packs the face folder of [converted] in [dir] with Pack, checks it
     * with the validator, and makes the face zip when [faceZip] is given.
     */
    fun pack(converted: Converted, dir: File, faceZip: FaceZip? = null): Result {
        val cwf = converted.design
        val face = converted.face ?: return Result(converted.lint, null, null, cwf)
        val written = converted.written!!
        var lint = converted.lint
        val apk = File(dir, "face.apk")
        pack(face, apk)?.let { failure ->
            File(dir, "lint.txt").appendText(failure)
            return Result(lint + failure, null, null, cwf)
        }
        val (validator, token) = validate(apk, client)
        File(dir, "validator.txt").writeText(validator)
        token?.let { File(dir, "token.txt").writeText(it) }
        if (faceZip == null) return Result(lint, apk, token, cwf)

        // The face zip: a face per wear app and per WFF version, made from the same pictures
        val faces = ArrayList<WffFaceZip.Face>()
        val zipLines = LinkedHashSet<String>()
        for (appId in faceZip.appIds) for (version in listOf(WffFaceWriter.VERSION_4, WffFaceWriter.VERSION_2)) {
            if (appId == client && version == WffFaceWriter.VERSION_4) {
                if (token != null) faces += WffFaceZip.Face(appId, version, apk, token)
                else zipLines += " !! no WFF $version face for $appId: it is not valid, see validator.txt\n"
                continue
            }
            val where = File(dir, "faces/$appId")
            val folder = File(where, "wff$version")
            val other = faceFolder(converted, folder, appId, version)
            if (version == WffFaceWriter.VERSION_2) other.notWritten.filter { it !in written.notWritten }.forEach { zipLines += " ! WFF 2 (Wear OS 5), not written: $it\n" }
            val otherApk = File(where, "wff$version.apk")
            val packed = pack(folder, otherApk)
            if (packed != null) {
                zipLines += " !! no WFF $version face for $appId: Pack failed, see faces/$appId/pack-wff$version.txt\n"
                File(where, "pack-wff$version.txt").writeText(packed)
                continue
            }
            // The validator also checks the schema of version 2; its token is only needed for 4
            val (otherValidator, otherToken) = validate(otherApk, appId)
            File(where, "validator-wff$version.txt").writeText(otherValidator)
            if (otherToken == null) {
                zipLines += " !! no WFF $version face for $appId: it is not valid, see faces/$appId/validator-wff$version.txt\n"
                continue
            }
            faces += WffFaceZip.Face(appId, version, otherApk, otherToken.takeIf { version == WffFaceWriter.VERSION_4 })
        }
        // The data picture's parts, as the watch draws them, and a check that AAPS can load them
        val data = dataZip(converted)
        val dataLoads = ZipWatchfaceFormat.loadCustomWatchface(data, WffFaceZip.DATA_ZIP, authorization = true) != null
        if (!dataLoads) zipLines += " !! data.zip does not load as a design zip\n"
        val name = cwf.metadata[CwfMetadataKey.CWF_NAME] ?: faceZip.file.name.removeSuffix("-wff.zip")
        WffFaceZip.write(faceZip.file, name, data, faces)
        val zipLint = zipLines.joinToString("") + "Face zip: ${faceZip.file.name}, ${faces.size} faces of ${faceZip.appIds.size * 2}, data.zip ${data.size / 1024} KB\n"
        lint += zipLint
        File(dir, "lint.txt").appendText(zipLint)
        return Result(lint, apk, token, cwf, faceZip.file.takeIf { dataLoads && faces.size == faceZip.appIds.size * 2 })
    }

    /**
     * Writes into the face folder [folder] what depends on the wear app and the version: the xml, its
     * strings and the manifest. A picture the xml does not show is removed (frames that WFF 2 leaves out).
     */
    private fun writeFace(folder: File, written: WffFaceWriter.Face, appId: String, version: Int) {
        val xml = editXml(written.xml)
        write(folder, WffResources.WATCH_FACE_XML, xml)
        write(folder, WffResources.STRINGS_XML, "<resources>\n" + written.strings.entries.joinToString("") { (name, text) -> "    <string name=\"$name\">${escape(text)}</string>\n" } + "</resources>\n")
        write(folder, WffResources.MANIFEST, manifest(appId, version))
        File(folder, WffResources.picture(PREVIEW, WffPictures.Format.PNG)).parentFile?.listFiles()
            ?.filter { it.nameWithoutExtension != PREVIEW && "resource=\"${it.nameWithoutExtension}\"" !in xml }
            ?.forEach { it.delete() }
    }

    /** Pack: an aligned and signed [apk] of [folder]. Null when it worked, else what Pack said. */
    private fun pack(folder: File, apk: File): String? {
        // It prints "Error:" and still ends with 0 on a mistake
        val output = run(listOf(packCli.path, folder.path, apk.path.removeSuffix(".apk"), key.path))
        File(apk.path.removeSuffix(".apk") + ".aab").delete()
        return if (output.contains("Compiled, aligned & signed successfully") && apk.isFile) null else "!! Pack failed:\n$output\n"
    }

    /** The validator of Watch Face Push, as the AAPS wear build runs it for its own faces: its output, and the token when the face passed. */
    private fun validate(apk: File, appId: String): Pair<String, String?> {
        val output = run(listOf(File(System.getProperty("java.home"), "bin/java").path, "-cp", validatorJar(), "com.google.android.wearable.watchface.validator.cli.DwfValidation", "-a", apk.path, "-p", appId))
        return output to TOKEN.find(output)?.groupValues?.get(1)
    }

    /** A static picture in motion: its run, where its loop and its note are, and its full rate. */
    private class MovingPicture(val run: Int, val loop: Int, val note: Int, val fps: Int)

    /** Every [step]th frame of [frames], from the first; the frames left out are freed. */
    private fun thinned(frames: List<Bitmap>, step: Int): List<Bitmap> {
        if (step == 1) return frames
        return frames.filterIndexed { i, frame -> (i % step == 0).also { kept -> if (!kept) frame.recycle() } }
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

    /** The manifest of a face for the wear app [appId] in WFF [version]. API 33 is the first with WFF; the watches here run 34 (WFF 2) and 36 (WFF 4). */
    private fun manifest(appId: String, version: Int) = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="${WffFaceZip.packageOf(appId)}" android:versionCode="1" android:versionName="1">
    <uses-sdk android:minSdkVersion="33" android:targetSdkVersion="36" />
    <uses-feature android:name="android.hardware.type.watch" />
    <application android:label="@string/app_name" android:hasCode="false">
        <property android:name="com.google.wear.watchface.format.version" android:value="$version" />
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

    /** The validator jar is on the class path (a dependency of this project). */
    private fun validatorJar(): String =
        System.getProperty("java.class.path").split(File.pathSeparator).firstOrNull { "validator-push-cli" in it } ?: error("the validator is not on the class path")

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "\\'")

    private companion object {

        val TOKEN = Regex("generated token: (\\S+)")

        /** The preview picture of the face, see `WatchFaceInfo`. */
        const val PREVIEW = "preview"

        /** The hands of the wear app that our engine draws when a design has none of its own. */
        val DEFAULT_HANDS = mapOf("HourHand" to R.drawable.hour_hand, "MinuteHand" to R.drawable.minute_hand, "SecondHand" to R.drawable.second_hand)
    }
}
