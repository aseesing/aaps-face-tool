package app.aaps.facetool.editor.server

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.facetool.editor.model.DesignSnapshot
import app.aaps.facetool.editor.model.DesignSources
import app.aaps.facetool.editor.model.GitReader
import app.aaps.facetool.editor.model.LocalCommands
import app.aaps.facetool.editor.model.ProjectFolder
import app.aaps.facetool.editor.model.Scenes
import app.aaps.facetool.editor.model.unreadable
import app.aaps.facetool.editor.model.wrongUse
import app.aaps.facetool.wff.WffPictures
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.wear.watchfaces.options.DesignOptions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import java.io.File
import java.time.Clock
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.math.roundToLong

/**
 * The picture commands of design 8.2 that make more than one picture: `wake`, `options`, `looks`,
 * `pick`, `snapshot` and `diff --render`. They run on the render thread.
 */
class SheetCommands(
    private val preview: EnginePreview, private val loader: DesignLoader, private val scenes: SceneReader, private val pictures: Pictures,
    private val render: PictureCommands, private val local: LocalCommands, private val clock: Clock, private val git: GitReader
) {

    /** Called for each frame of `wake` when its file is written: the project, the file, how many are done, how many come. */
    var onFrame: (ProjectFolder, File, Int, Int) -> Unit = { _, _, _, _ -> }

    /** Asked before each frame of `wake --no-sheet` with the folder of the frames: true stops the command there. */
    var stopAsked: (File) -> Boolean = { false }

    private class FramesStopped : RuntimeException()

    /** `wake`: the wake as frames, a contact sheet, and the facts for a GIF (the CLI writes the GIF). */
    fun wake(project: ProjectFolder, args: Args): JsonObject {
        val scene = Scenes.forCommand(project, args.json)
        val values = scenes.read(scene)
        val cwf = loader.load(project)
        val fps = args.int("fps") ?: 8
        if (fps !in 1..60) wrongUse("--fps must be 1 to 60")
        val from = args.number("from") ?: 0.0
        val to = args.number("to") ?: (wakeMsOf(cwf) / 1000.0)
        if (from < 0 || to <= from || to > 60) wrongUse("--from and --to must be 0 to 60 seconds, with --to after --from")
        val count = ((to - from) * fps).toInt() + 1
        // Without the sheet no frame is kept in memory, so a whole wake at the design's own rate fits
        val noSheet = args.flag("no-sheet")
        val most = if (noSheet) MAX_FRAMES_NO_SHEET else MAX_FRAMES
        if (count > most) wrongUse("That is $count frames; the most is $most. Use a lower --fps or a shorter time" + if (noSheet) "" else ", or --no-sheet (up to $MAX_FRAMES_NO_SHEET frames)")
        val atMs = (0 until count).map { ((from + it.toDouble() / fps) * 1000).roundToLong() }
        val shown = render.shownDesign(cwf, args)
        val began = System.nanoTime()
        val dir = args.file("frames") ?: File(project.outDir("wake"), "frames")
        dir.mkdirs()
        dir.listFiles { f -> f.name.matches(Regex("frame_\\d+\\.png")) }?.forEach { it.delete() }
        val files = atMs.indices.map { File(dir, "frame_%03d.png".format(it)) }
        // Each frame is written as soon as it is drawn, so the page can show the first ones while the rest is made
        var written = 0
        val save = { i: Int, frame: Bitmap ->
            if (noSheet && stopAsked(dir)) throw FramesStopped()
            if (noSheet) pictures.saveFast(frame, files[i]) else pictures.save(frame, files[i])
            written = i + 1
            onFrame(project, files[i], i + 1, files.size)
        }
        val file = if (noSheet) null else args.file("out") ?: File(project.outDir("wake"), "wake.png")
        try {
            if (file == null) preview.eachWakeFrame(render.faceKey(project), shown, values, atMs, save)
            else saveSheet(preview.wakeFrames(render.faceKey(project), shown, values, atMs, save), atMs.map { String.format(Locale.ROOT, "%.2f s", it / 1000.0) }, file)
        } catch (_: FramesStopped) {
            // The page asked to stop: the frames written so far are the answer
        }
        val seconds = (System.nanoTime() - began) / 1e9
        if (written < files.size) return buildJsonObject {
            put("stopped", true)
            put("frames", JsonArray(files.take(written).map { JsonPrimitive(it.absolutePath) }))
            put("frameMs", 1000 / fps)
            put("seconds", seconds)
            put("text", "Stopped after $written of ${files.size} frames.")
        }
        return buildJsonObject {
            file?.let { put("file", it.absolutePath) }
            put("frames", JsonArray(files.map { JsonPrimitive(it.absolutePath) }))
            put("frameMs", 1000 / fps)
            put("times", JsonArray(atMs.map { JsonPrimitive(it / 1000.0) }))
            put("seconds", seconds)
            put("scene", scene)
            val warnings = listOfNotNull(PictureCommands.AMBIENT_WAKE_WARNING.takeIf { values.ambient }) + PictureCommands.designWins(cwf, scene)
            put("warnings", JsonArray(warnings.map { JsonPrimitive(it) }))
            put("text", (file?.let { "Wrote the contact sheet ${it.path}: ${files.size} frames" } ?: "Wrote ${files.size} frames") +
                " from $from s to $to s at $fps a second. Single frames in ${dir.path}" + warnings.joinToString("") { "\nwarning: $it" })
        }
    }

    /** `options`: one picture per choice of each option and per colour of each colour slot, as one sheet. */
    fun options(project: ProjectFolder, args: Args): JsonObject {
        val scene = Scenes.forCommand(project, args.json)
        val cwf = loader.load(project)
        val json = JSONObject(cwf.json)
        val dir = project.outDir("options")
        val frames = ArrayList<Bitmap>()
        val labels = ArrayList<String>()
        val answer = buildJsonArray {
            for (option in DesignOptions.read(json).filterNotNull()) add(buildJsonObject {
                put("name", option.name)
                put("title", option.title)
                put("default", option.default)
                put("choices", buildJsonArray {
                    option.choices.forEachIndexed { index, title ->
                        val file = File(dir, "${option.name}-$index.png")
                        frames += draw(project, cwf, Scenes.set(scene, "options.${option.name}=$index"), file)
                        labels += "${option.name} $index: $title"
                        add(buildJsonObject { put("index", index); put("title", title); put("file", file.absolutePath) })
                    }
                })
            })
            val slots = json.optJSONObject("colourLooks")?.let { looks -> listOf("colour1", "colour2").filter { looks.has(it) } }.orEmpty()
            for (slot in slots) add(buildJsonObject {
                put("name", slot)
                put("title", if (slot == "colour1") "Main colour" else "Second colour")
                put("choices", buildJsonArray {
                    preview.colourValues().forEachIndexed { index, value ->
                        val file = File(dir, "$slot-$index.png")
                        frames += draw(project, cwf, Scenes.set(scene, "colours.$slot=\"$value\""), file)
                        labels += "$slot $value"
                        add(buildJsonObject { put("index", index); put("title", value); put("file", file.absolutePath) })
                    }
                })
            })
        }
        if (frames.isEmpty()) return buildJsonObject {
            put("options", answer)
            put("text", "The design has no options and no colour slots.")
        }
        val file = args.file("out") ?: File(dir, "options.png")
        saveSheet(frames, labels, file)
        return buildJsonObject {
            put("file", file.absolutePath)
            put("options", answer)
            put("text", "Wrote the sheet ${file.path}: ${labels.size} pictures (${labels.joinToString("; ")})")
        }
    }

    /**
     * `looks`: which looks change the picture in the scene, and for each look a scene that turns it
     * on, when one of the ready scenes does. The harness cannot ask the engine which looks are on, so
     * a look counts as on when the design without it draws another picture.
     */
    fun looks(project: ProjectFolder, args: Args): JsonObject {
        val scene = Scenes.forCommand(project, args.json)
        val cwf = loader.load(project)
        val json = JSONObject(cwf.json)
        val looks = json.optJSONArray("looks")
        val count = looks?.length() ?: 0
        if (count == 0) return buildJsonObject {
            put("active", JsonArray(emptyList()))
            put("looks", JsonArray(emptyList()))
            put("text", "The design has no looks.")
        }
        val dir = project.outDir("looks")
        // The scene of the command first, then the ready scenes, until every look was seen on
        val candidates = listOf("the scene" to scene) + Scenes.PRESETS.filterKeys { it != "default" }.map { (name, keys) -> name to Scenes.over(scene, keys) } +
            listOf("rain", "cloudy", "partly").map { "weather $it" to Scenes.set(scene, "weather=$it") }
        val found = HashMap<Int, Pair<String, JsonObject>>()
        val active = ArrayList<Int>()
        val files = HashMap<Int, File>()
        for ((index, candidate) in candidates.withIndex()) {
            if (index > 0 && found.size == count) break
            val values = scenes.read(candidate.second)
            val full = preview.picture(render.faceKey(project), cwf, values)
            for (look in 0 until count) {
                if (look in found) continue
                val without = JSONObject(cwf.json).also { it.getJSONArray("looks").put(look, JSONObject().put("when", "0")) }
                val other = preview.picture(render.faceKey(project), CwfData(without.toString(), cwf.metadata, cwf.resData), values)
                val on = !other.sameAs(full)
                other.recycle()
                if (!on) continue
                found[look] = candidate
                if (index == 0) active += look
                files[look] = File(dir, "look-$look.png").also { pictures.save(full, it) }
            }
            full.recycle()
        }
        val frames = (0 until count).mapNotNull { look -> files[look]?.readBytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
        val labels = (0 until count).filter { it in files }.map { "look $it: ${found.getValue(it).first}" }
        val file = args.file("out") ?: File(dir, "looks.png")
        if (frames.isNotEmpty()) saveSheet(frames, labels, file)
        return buildJsonObject {
            put("active", JsonArray(active.map { JsonPrimitive(it) }))
            if (frames.isNotEmpty()) put("file", file.absolutePath)
            put("looks", buildJsonArray {
                for (look in 0 until count) add(buildJsonObject {
                    put("index", look)
                    put("when", looks!!.optJSONObject(look)?.optString("when") ?: "")
                    put("active", look in active)
                    found[look]?.let { (name, sceneOn) -> put("sceneName", name); put("scene", sceneOn) }
                    files[look]?.let { put("file", it.absolutePath) }
                })
            })
            put("text", (0 until count).joinToString("\n") { look ->
                val whenText = looks!!.optJSONObject(look)?.optString("when").orEmpty().ifEmpty { "(always)" }
                "look $look when $whenText: " + when {
                    look in active -> "on in the scene"
                    look in found  -> "off in the scene; on with ${found.getValue(look).first}"
                    else           -> "off in the scene; none of the ready scenes turns it on (or it changes nothing that shows)"
                }
            } + if (frames.isNotEmpty()) "\nWrote the sheet ${file.path}" else "")
        }
    }

    /** `pick`: which layers and views draw the pixel at x, y (design units), top first. */
    fun pick(project: ProjectFolder, args: Args): JsonObject {
        val x = args.number("x") ?: wrongUse("pick needs x and y")
        val y = args.number("y") ?: wrongUse("pick needs x and y")
        if (x !in 0.0..DESIGN.toDouble() || y !in 0.0..DESIGN.toDouble()) wrongUse("x and y are design units, 0 to $DESIGN")
        val scene = render.sceneOf(project, args)
        val values = scenes.read(scene)
        val cwf = loader.load(project)
        val parts = DesignParts(cwf)
        val px = (x * values.size / DESIGN).toInt().coerceIn(0, values.size - 1)
        val py = (y * values.size / DESIGN).toInt().coerceIn(0, values.size - 1)
        val design = project.design()
        val layerJson = (((design["animation"] as? JsonObject)?.get("layers")) as? JsonArray).orEmpty()
        val hits = render.partInks(project, parts, values, null, null).filter { it.mask.get(py * it.width + px) }.reversed()
        return buildJsonObject {
            put("x", x)
            put("y", y)
            put("hits", buildJsonArray {
                for (hit in hits) add(buildJsonObject {
                    hit.layer?.let { put("layer", it); put("path", "animation.layers[$it]"); put("summary", layerJson.getOrNull(it)?.toString()?.take(120) ?: "") }
                    hit.view?.let { put("view", it); put("path", it); put("summary", design[it]?.toString()?.take(120) ?: "") }
                })
            })
            put("text", if (hits.isEmpty()) "Nothing but the background draws at $x, $y." else hits.joinToString("\n") { hit ->
                hit.layer?.let { "L$it  animation.layers[$it]  ${layerJson.getOrNull(it)?.toString()?.take(100)}" } ?: "view ${hit.view}  ${design[hit.view!!]?.toString()?.take(100)}"
            })
        }
    }

    /** `snapshot`: pictures at a fixed set of moments, for a before and after diff. */
    fun snapshot(project: ProjectFolder, args: Args): JsonObject {
        val scene = Scenes.forCommand(project, args.json)
        val cwf = loader.load(project)
        val name = args.text("name") ?: "default"
        if (!name.matches(Regex("[A-Za-z0-9_-]+"))) wrongUse("A snapshot name has only letters, digits, - and _")
        val dir = args.file("out") ?: File(project.outDir("snapshot"), name)
        val wake = wakeMsOf(cwf) / 1000.0
        val day = (scene["time"] as JsonPrimitive).content.substringBefore("T")
        val moments = listOf(
            "rest" to scene,
            "wake-1" to Scenes.set(scene, "wake=${wake * 0.25}"), "wake-2" to Scenes.set(scene, "wake=${wake * 0.5}"), "wake-3" to Scenes.set(scene, "wake=${wake * 0.75}"),
            "night" to Scenes.set(scene, "time=\"${day}T23:30:00\""),
            "always-on" to Scenes.set(Scenes.set(scene, "wake=null"), "ambient=true")
        )
        val files = moments.map { (moment, momentScene) -> moment to File(dir, "$moment.png").also { draw(project, cwf, momentScene, it).recycle() } }
        return buildJsonObject {
            put("dir", dir.absolutePath)
            put("files", buildJsonArray { for ((moment, file) in files) add(buildJsonObject { put("moment", moment); put("file", file.absolutePath) }) })
            put("text", "Wrote ${files.size} pictures to ${dir.path}: ${files.joinToString(", ") { it.first }}.")
        }
    }

    /** `diff --render`: the diff of the model, with the two designs side by side and the share of changed pixels. */
    fun diff(project: ProjectFolder?, args: Args): JsonObject {
        val answer = local.run("diff", JsonObject(args.json - "render"), project?.dir).json
        if ("error" in answer) return answer
        val sources = DesignSources(clock, git)
        val scene = Scenes.forCommand(project, args.json)
        val values = scenes.read(scene)
        val key = project?.let { render.faceKey(it) } ?: "diff"
        fun picture(source: String): Bitmap = preview.picture(key, cwfOf(sources.load(source, project), source), values)
        val a = picture(args.text("a") ?: wrongUse("diff needs two designs"))
        val b = picture(args.text("b") ?: wrongUse("diff needs two designs"))
        val pa = WffPictures.pixelsOf(a)
        val pb = WffPictures.pixelsOf(b)
        val marks = IntArray(pa.argb.size)
        var changed = 0
        for (i in marks.indices) {
            if (differs(pa.argb[i], pb.argb[i])) {
                changed++
                marks[i] = Color.RED
            } else marks[i] = dim(pb.argb[i])
        }
        val size = values.size
        val sheet = Bitmap.createBitmap(size * 3, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawBitmap(a, 0f, 0f, null)
        canvas.drawBitmap(b, size.toFloat(), 0f, null)
        canvas.drawBitmap(Bitmap.createBitmap(marks, size, size, Bitmap.Config.ARGB_8888), size * 2f, 0f, null)
        canvas.drawText("a | b | changed pixels in red", 6f, size - 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12f })
        a.recycle()
        b.recycle()
        val file = args.file("out") ?: File((project?.outDir("diff") ?: File(System.getProperty("java.io.tmpdir"), "facetool-diff").apply { mkdirs() }), "diff.png")
        pictures.save(sheet, file)
        sheet.recycle()
        val percent = changed * 100.0 / marks.size
        val line = String.format(Locale.ROOT, "Pictures: %.2f %% of the pixels differ. Side by side in %s", percent, file.path)
        return JsonObject(answer + mapOf(
            "file" to JsonPrimitive(file.absolutePath), "changedPercent" to JsonPrimitive(percent),
            "text" to JsonPrimitive(((answer["text"] as? JsonPrimitive)?.content.orEmpty() + "\n" + line).trim())
        ))
    }

    /** A design from the model as the engine reads it: packed as a zip, read as the phone reads a zip. */
    private fun cwfOf(snapshot: DesignSnapshot, name: String): CwfData {
        val folder = File.createTempFile("facetool", "").apply { delete(); mkdirs() }
        try {
            File(folder, "CustomWatchface.json").writeText(snapshot.json.toString())
            snapshot.resources.forEach { (file, bytes) -> File(folder, file).apply { parentFile?.mkdirs() }.writeBytes(bytes) }
            return runCatching { ZipWatchfaceFormat.loadCustomWatchface(ProjectFolder(folder).zipBytes(), "diff.zip", authorization = true)?.cwfData }.getOrNull()
                ?: unreadable("$name does not read as a design")
        } finally {
            folder.deleteRecursively()
        }
    }

    /** As the parity suite: a pixel counts as changed when one channel differs by more than 40 of 255. */
    private fun differs(a: Int, b: Int): Boolean {
        for (shift in intArrayOf(0, 8, 16, 24)) if (abs(((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF)) > 40) return true
        return false
    }

    private fun dim(argb: Int): Int = Color.argb(255, Color.red(argb) / 3, Color.green(argb) / 3, Color.blue(argb) / 3)

    /** Draws the design for [scene], saves it to [file], and gives the bitmap for a sheet. */
    private fun draw(project: ProjectFolder, cwf: CwfData, scene: JsonObject, file: File): Bitmap =
        preview.picture(render.faceKey(project), cwf, scenes.read(scene)).also { pictures.save(it, file) }

    private fun saveSheet(frames: List<Bitmap>, labels: List<String>, file: File) {
        val columns = if (frames.size <= 4) frames.size else ceil(sqrt(frames.size.toDouble() * 1.5)).toInt().coerceAtMost(8)
        val sheet = pictures.sheet(frames, labels, columns.coerceAtLeast(1), SHEET_CELL)
        frames.forEach { it.recycle() }
        pictures.save(sheet, file)
        sheet.recycle()
    }

    /** The length of the wake of the design, as the engine reads it (6 s when the design sets none). */
    private fun wakeMsOf(cwf: CwfData): Long = JSONObject(cwf.json).optJSONObject("animation")?.optLong("durationMs", DEFAULT_WAKE_MS) ?: DEFAULT_WAKE_MS

    private companion object {

        const val DESIGN = 400
        const val MAX_FRAMES = 240

        /** 60 seconds at the 30 frames a second that the engine allows at most. */
        const val MAX_FRAMES_NO_SHEET = 1800
        const val SHEET_CELL = 225
        const val DEFAULT_WAKE_MS = 6000L
    }
}
