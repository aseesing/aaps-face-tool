package app.aaps.facetool.editor.server

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.facetool.editor.model.ProjectFolder
import app.aaps.facetool.editor.model.Scenes
import app.aaps.facetool.editor.model.wrongUse
import app.aaps.facetool.player.WffPlayer
import app.aaps.facetool.player.WffScene
import app.aaps.facetool.player.WffSlotData
import app.aaps.facetool.player.WffSlots
import app.aaps.facetool.snapshot.SnapshotClock
import app.aaps.facetool.wff.WffFaceWriter
import app.aaps.facetool.wff.WffPictures
import app.aaps.wear.watchfaces.wff.WffDesignParts
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * The WFF preview commands (design section 11, step 10): `wff render`, `wff wake`, `wff parts` and
 * `wff compare`. The face folder comes from `wff build`; the player draws it for the scene.
 */
class PlayerCommands(
    private val app: Application, private val wff: WffCommands, private val preview: EnginePreview, private val loader: DesignLoader,
    private val scenes: SceneReader, private val pictures: Pictures, private val render: PictureCommands
) {

    /** Called for each frame of `wff wake` when its file is written: the project, the file, how many are done, how many come. */
    var onFrame: (ProjectFolder, File, Int, Int) -> Unit = { _, _, _, _ -> }

    /** Asked before each frame of `wff wake --no-sheet` with the folder of the frames: true stops the command there. */
    var stopAsked: (File) -> Boolean = { false }

    private fun version(args: Args): Int = when (args.text("version") ?: "4") {
        "4"  -> WffFaceWriter.VERSION_4
        "2"  -> WffFaceWriter.VERSION_2
        else -> wrongUse("--version is 4 or 2")
    }

    /** A face ready to be drawn for one scene: the player, the slots with the data picture, and the values. */
    private class Prepared(val player: WffPlayer, val slots: Map<Int, WffSlotData>, val values: SceneValues, val dataPicture: Bitmap?)

    private fun prepare(project: ProjectFolder, cwf: CwfData, scene: JsonObject, version: Int): Prepared {
        // The face is baked for the scene without the keys that only the player reads
        val build = wff.build(project, cwf, bakeScene(scene))
        if (!build.built) wrongUse("The design cannot be a WFF face:\n${build.lint(version)}")
        val values = scenes.read(scene)
        val player = WffPlayer(build.face(version), version)
        // The data picture is the engine's own picture of the data parts, as on the watch
        val dataPicture = WffDesignParts.dataOf(cwf)?.let { data ->
            preview.picture(render.faceKey(project) + "#data", data, SceneValues(values.timeMs, player.width, null, values.data, values.options, values.colours, values.watch, false))
        }
        return Prepared(player, WffSlots(app).of(build.face(version), cwf, values.data, dataPicture), values, dataPicture)
    }

    /** The picture of [prepared] with the player's keys of [scene] (always-on, the seconds since the screen came on). */
    private fun drawWith(prepared: Prepared, scene: JsonObject): Bitmap = prepared.player.draw(
        WffScene(
            timeMs = prepared.values.timeMs, zone = ZoneId.of(SnapshotClock.ZONE), ambient = (scene["ambient"] as? JsonPrimitive)?.content == "true", visibleSec = number(scene, "visibleSec") ?: 5.0,
            heartRate = prepared.values.data.heartRate, steps = prepared.values.data.steps, batteryPercent = prepared.values.data.watchBattery ?: 100, slots = prepared.slots
        )
    )

    private fun problemsOf(prepared: Prepared): List<String> =
        prepared.player.problems + listOf("player: the slots of the wearer show sample values, not the providers of the watch").filter { prepared.slots.any { it.key in 1..9 && it.value.type != "EMPTY" } }

    /** The player's picture of the WFF [version] face of [project] for [scene], and the player's problem lines. */
    fun draw(project: ProjectFolder, cwf: CwfData, scene: JsonObject, version: Int): Pair<Bitmap, List<String>> {
        val prepared = prepare(project, cwf, scene, version)
        val bitmap = drawWith(prepared, scene)
        prepared.dataPicture?.recycle()
        return bitmap to problemsOf(prepared)
    }

    /** [scene] without the keys that only the player reads: they must not make a new build of the face. */
    private fun bakeScene(scene: JsonObject): JsonObject {
        val defaults = Scenes.defaults()
        return JsonObject(scene.mapValues { (key, value) -> if (key in PLAYER_KEYS) defaults.getValue(key) else value })
    }

    fun render(project: ProjectFolder, args: Args): JsonObject {
        val version = version(args)
        val scene = Scenes.forCommand(project, args.json)
        val (bitmap, problems) = draw(project, loader.load(project), scene, version)
        val file = args.file("out") ?: File(project.outDir("wff render"), "wff$version.png")
        pictures.save(bitmap, file)
        bitmap.recycle()
        return buildJsonObject {
            put("file", file.absolutePath)
            put("version", version)
            put("scene", scene)
            put("problems", JsonArray(problems.map { JsonPrimitive(it) }))
            put("text", (listOf("Wrote ${file.path} (the WFF $version face as the player draws it)") + problems).joinToString("\n"))
        }
    }

    /** `wff wake`: the player's frames after the screen comes on. */
    fun wake(project: ProjectFolder, args: Args): JsonObject {
        val version = version(args)
        val scene = Scenes.forCommand(project, args.json)
        val seconds = args.number("seconds") ?: 10.0
        val fps = args.int("fps") ?: 10
        if (seconds <= 0 || seconds > 60 || fps !in 1..30) wrongUse("--seconds is up to 60 and --fps 1 to 30")
        val count = (seconds * fps).toInt() + 1
        // Without the sheet no frame is kept in memory
        val noSheet = args.flag("no-sheet")
        val most = if (noSheet) MAX_FRAMES_NO_SHEET else MAX_FRAMES
        if (count > most) wrongUse("That is $count frames; the most is $most" + if (noSheet) "" else ". Use a lower --fps or fewer seconds, or --no-sheet (up to $MAX_FRAMES_NO_SHEET frames)")
        val began = System.nanoTime()
        val cwf = loader.load(project)
        val dir = args.file("frames") ?: File(project.outDir("wff wake"), "frames")
        dir.mkdirs()
        dir.listFiles { f -> f.name.matches(Regex("frame_\\d+\\.png")) }?.forEach { it.delete() }
        val frames = ArrayList<Bitmap>()
        val files = ArrayList<File>()
        val labels = ArrayList<String>()
        val prepared = prepare(project, cwf, scene, version)
        var stopped = false
        for (i in 0 until count) {
            if (noSheet && stopAsked(dir)) {
                stopped = true
                break
            }
            val at = i.toDouble() / fps
            val bitmap = drawWith(prepared, Scenes.set(scene, "visibleSec=$at"))
            files += File(dir, "frame_%03d.png".format(i)).also { if (noSheet) pictures.saveFast(bitmap, it) else pictures.save(bitmap, it) }
            onFrame(project, files.last(), i + 1, count)
            if (noSheet) bitmap.recycle() else frames += bitmap
            labels += String.format(Locale.ROOT, "%.2f s", at)
        }
        prepared.dataPicture?.recycle()
        val file = if (noSheet) null else args.file("out") ?: File(project.outDir("wff wake"), "wff$version-wake.png")
        if (file != null) {
            val sheet = pictures.wakeSheet(frames, labels, args.flag("full-sheet"), 7)
            frames.forEach { it.recycle() }
            pictures.save(sheet, file)
            sheet.recycle()
        }
        val took = (System.nanoTime() - began) / 1e9
        return buildJsonObject {
            if (stopped) put("stopped", true)
            file?.let { put("file", it.absolutePath) }
            put("frames", JsonArray(files.map { JsonPrimitive(it.absolutePath) }))
            put("frameMs", 1000 / fps)
            put("seconds", took)
            put("scene", scene)
            put("text", if (stopped) "Stopped after ${files.size} of $count frames." else (file?.let { "Wrote the contact sheet ${it.path}: ${files.size} frames" } ?: "Wrote ${files.size} frames into ${dir.path}") +
                " of the WFF $version face, the first $seconds s after the screen comes on. The clock stands still in these frames.")
        }
    }

    /** `wff parts`: the pictures of the face folder, each alone: the statics, the first frame of each loop, the data picture. */
    fun parts(project: ProjectFolder, args: Args): JsonObject {
        val version = version(args)
        val scene = Scenes.forCommand(project, args.json)
        val cwf = loader.load(project)
        val build = wff.build(project, cwf, bakeScene(scene))
        if (!build.built) wrongUse("The design cannot be a WFF face:\n${build.lint(version)}")
        val drawable = File(build.face(version), "res/drawable")
        val files = drawable.listFiles().orEmpty().sortedBy { it.name }
        val statics = files.filter { it.name.startsWith("static_") || it.name.startsWith("preview") }
        val loops = files.filter { it.name.matches(Regex("(loop|run)\\d+_\\d+\\..*")) }.groupBy { it.name.substringBefore("_") }
        val others = files - statics.toSet() - loops.values.flatten().toSet()
        val values = scenes.read(scene)
        val dataFile = WffDesignParts.dataOf(cwf)?.let { data ->
            val bitmap = preview.picture(render.faceKey(project) + "#data", data, SceneValues(values.timeMs, values.size, null, values.data, values.options, values.colours, values.watch, false))
            File(project.outDir("wff parts"), "data-picture.png").also { pictures.save(bitmap, it); bitmap.recycle() }
        }
        return buildJsonObject {
            put("version", version)
            put("statics", JsonArray(statics.map { JsonPrimitive(it.absolutePath) }))
            put("loops", buildJsonArray { for ((name, frames) in loops) add(buildJsonObject { put("name", name); put("frames", frames.size); put("first", frames.first().absolutePath) }) })
            put("others", JsonArray(others.map { JsonPrimitive(it.absolutePath) }))
            dataFile?.let { put("dataPicture", it.absolutePath) }
            put("xml", build.xml(version).absolutePath)
            put("text", (listOf("The parts of the WFF $version face in ${drawable.path}:") +
                statics.map { "  static picture ${it.name} (${it.length() / 1024} KB)" } +
                loops.map { (name, frames) -> "  $name: ${frames.size} frames, ${frames.sumOf { it.length() } / 1024} KB, first ${frames.first().name}" } +
                others.map { "  ${it.name}" } +
                listOfNotNull(dataFile?.let { "  the data picture (drawn by the engine on the watch): ${it.path}" }) +
                listOf("  the live parts are in ${build.xml(version).path} (facetool wff xml)")).joinToString("\n"))
        }
    }

    /** `wff compare`: the engine face and the player's WFF 4 and WFF 2 faces side by side, with the changed pixels marked. */
    fun compare(project: ProjectFolder, args: Args): JsonObject {
        val scene = Scenes.forCommand(project, args.json)
        val cwf = loader.load(project)
        val values = scenes.read(scene)
        val engine = preview.picture(render.faceKey(project), cwf, SceneValues(values.timeMs, values.size, null, values.data, values.options, values.colours, values.watch, false))
        val size = values.size
        val sheet = Bitmap.createBitmap(size * 3, size * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.rgb(24, 24, 24))
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 19f }
        canvas.drawBitmap(engine, 0f, 0f, null)
        canvas.drawText("engine", 6f, 22f, label)
        val enginePixels = WffPictures.pixelsOf(engine)
        val shares = LinkedHashMap<Int, Double>()
        val problems = ArrayList<String>()
        for ((column, version) in listOf(WffFaceWriter.VERSION_4, WffFaceWriter.VERSION_2).withIndex()) {
            val (picture, lines) = draw(project, cwf, scene, version)
            problems += lines.map { "WFF $version: $it" }
            val shown = if (picture.width == size) picture else Bitmap.createScaledBitmap(picture, size, size, true)
            val pixels = WffPictures.pixelsOf(shown)
            val marks = IntArray(pixels.argb.size)
            var changed = 0
            for (i in marks.indices) {
                val differs = (0..16 step 8).any { shift -> abs(((pixels.argb[i] ushr shift) and 0xFF) - ((enginePixels.argb[i] ushr shift) and 0xFF)) > PIXEL_LIMIT }
                if (differs) changed++
                marks[i] = if (differs) Color.RED else Color.argb(255, Color.red(pixels.argb[i]) / 3, Color.green(pixels.argb[i]) / 3, Color.blue(pixels.argb[i]) / 3)
            }
            shares[version] = changed * 100.0 / marks.size
            canvas.drawBitmap(shown, size * (column + 1f), 0f, null)
            canvas.drawText("WFF $version (player)", size * (column + 1f) + 6f, 22f, label)
            canvas.drawBitmap(Bitmap.createBitmap(marks, size, size, Bitmap.Config.ARGB_8888), size * (column + 1f), size.toFloat(), null)
            canvas.drawText(String.format(Locale.ROOT, "WFF %d against the engine: %.2f %% differ", version, shares[version]), size * (column + 1f) + 6f, size + 22f, label)
            picture.recycle()
        }
        engine.recycle()
        val file = args.file("out") ?: File(project.outDir("wff compare"), "compare.png")
        // A picture costs tokens by its area: 2/3 of the size costs less than half
        if (args.flag("full-size")) pictures.save(sheet, file)
        else Bitmap.createScaledBitmap(sheet, sheet.width * 2 / 3, sheet.height * 2 / 3, true).also { pictures.save(it, file); it.recycle() }
        sheet.recycle()
        return buildJsonObject {
            put("file", file.absolutePath)
            put("changedPercent", buildJsonObject { shares.forEach { (version, share) -> put("wff$version", share) } })
            put("problems", JsonArray(problems.distinct().map { JsonPrimitive(it) }))
            put("scene", scene)
            put("text", (listOf("Wrote ${file.path}: the engine, WFF 4 and WFF 2 (player), and under them the pixels that differ from the engine in red.") +
                shares.map { (version, share) -> String.format(Locale.ROOT, "WFF %d: %.2f %% of the pixels differ from the engine", version, share) } + problems.distinct()).joinToString("\n"))
        }
    }

    /** The player's problem lines of both versions as lint findings. */
    fun lint(project: ProjectFolder, cwf: CwfData): List<LintFinding> {
        val result = ArrayList<LintFinding>()
        for (version in listOf(WffFaceWriter.VERSION_4, WffFaceWriter.VERSION_2)) {
            val (bitmap, problems) = runCatching { draw(project, cwf, project.scene(), version) }.getOrElse { return result }
            bitmap.recycle()
            for (line in problems) result += LintFinding(if ("cannot show" in line) "warning" else "info", "player", "", "WFF $version: ${line.removePrefix("player: ")}")
        }
        return result
    }

    private fun number(scene: JsonObject, key: String): Double? = (scene[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull

    private companion object {

        /** The scene keys that only the player reads. */
        val PLAYER_KEYS = setOf("ambient", "tilt", "visibleSec", "tapSec", "sgvBefore", "transition", "wake")
        const val MAX_FRAMES = 240
        const val MAX_FRAMES_NO_SHEET = 1800

        /** As the parity suite: a pixel counts as changed when one channel differs by more than 40 of 255. */
        const val PIXEL_LIMIT = 40
    }
}
