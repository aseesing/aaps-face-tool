package app.aaps.facetool.editor.server

import android.graphics.Bitmap
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.facetool.editor.model.AndroidJson
import app.aaps.facetool.editor.model.JsonPaths
import app.aaps.facetool.editor.model.ProjectFolder
import app.aaps.facetool.editor.model.SceneStore
import app.aaps.facetool.editor.model.Scenes
import app.aaps.facetool.editor.model.wrongUse
import app.aaps.facetool.wff.WffPictures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.BitSet
import java.util.Locale

/**
 * What one layer or view draws alone in a scene.
 *
 * @param box where it draws, or null when it draws nothing
 * @param mask the pixels it draws, row by row, [width] pixels a row
 */
class PartInk(val layer: Int?, val view: String?, val box: LabelBox?, val mask: BitSet, val width: Int)

/**
 * The picture commands of the engine preview (design 8.2): `render` and `scene`. They run on the
 * render thread.
 */
class PictureCommands(
    private val preview: EnginePreview, private val loader: DesignLoader, private val scenes: SceneReader, private val pictures: Pictures, private val facts: ServerFacts
) {

    /** What single layers and views draw, by design, scene and part: drawing each one alone takes time. */
    private val inks = object : LinkedHashMap<String, PartInk>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PartInk>?): Boolean = size > 600
    }

    /** The scene of a picture command: the project's scene, `--scene`, `--set`, and `--wake` when given. */
    fun sceneOf(project: ProjectFolder, args: Args): JsonObject {
        var scene = Scenes.forCommand(project, args.json)
        args.number("wake")?.let { scene = Scenes.over(scene, JsonObject(mapOf("wake" to JsonPrimitive(it)))) }
        return scene
    }

    /** The key of the kept face of [project]. */
    fun faceKey(project: ProjectFolder): String = project.dir.canonicalPath

    /** The layers and views that `--only` and `--hide` leave; null is all of that kind. */
    class Shown(val layers: Set<Int>?, val views: Set<String>?)

    fun shown(parts: DesignParts, args: Args): Shown {
        var layers: Set<Int>? = null
        var views: Set<String>? = null
        args.text("only")?.let { only -> parts.parse(only).let { (l, v) -> layers = l; views = v } }
        args.text("hide")?.let { hide ->
            val (l, v) = parts.parse(hide)
            if (l.isNotEmpty()) layers = (layers ?: (0 until parts.layerCount).toSet()) - l
            if (v.isNotEmpty()) views = (views ?: parts.views.toSet()) - v
        }
        return Shown(layers, views)
    }

    /** [cwf] with only the layers and views that `--only` and `--hide` of [args] leave. */
    fun shownDesign(cwf: CwfData, args: Args): CwfData {
        if (args.text("only") == null && args.text("hide") == null) return cwf
        val parts = DesignParts(cwf)
        return shown(parts, args).let { parts.with(it.layers, it.views, transparent = false) }
    }

    fun render(project: ProjectFolder, args: Args): JsonObject {
        args.file("batch")?.let { return batch(project, args, it) }
        val scene = sceneOf(project, args)
        val file = args.file("out") ?: File(project.outDir("render"), "render.png")
        val cwf = loader.load(project)
        val drawn = draw(project, cwf, scene, args, file)
        return picture(file, scene, drawn.text(file), extraWarnings = listOfNotNull(drawn.warning) + designWins(cwf, scene))
    }

    /**
     * Draws the preview picture of the design: the picture the phone shows in its list of designs. It is
     * the face at rest in the default scene, [PREVIEW_SIZE] pixels, written over the preview picture the
     * folder has (`CustomWatchface.png` or `.jpg`, in its own format), or as a new PNG. Gives its file name.
     */
    fun previewPicture(project: ProjectFolder): String? {
        val existing = project.dir.listFiles()?.firstOrNull { it.isFile && it.nameWithoutExtension == PREVIEW_NAME && it.extension.lowercase() in setOf("png", "jpg", "jpeg") }
        val file = existing ?: File(project.dir, "$PREVIEW_NAME.png")
        val scene = Scenes.over(Scenes.defaults(), JsonObject(mapOf("size" to JsonPrimitive(PREVIEW_SIZE))))
        val parts = DesignParts(loader.load(project))
        val bitmap = preview.picture(faceKey(project), parts.with(null, null, transparent = false), scenes.read(scene))
        val bytes = ByteArrayOutputStream().also { out ->
            val jpeg = file.extension.lowercase() != "png"
            bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, if (jpeg) 90 else 100, out)
        }.toByteArray()
        bitmap.recycle()
        if (!file.isFile || !file.readBytes().contentEquals(bytes)) file.writeBytes(bytes)
        return file.name
    }

    /** What [draw] made: the size of the picture, the moment of the wake, always-on or not, and the warning for a wake moment of always-on. */
    private class Drawn(val size: String, val wakeMs: Long?, val ambient: Boolean, val warning: String?) {

        fun text(file: File): String = "Wrote ${file.path} ($size, ${if (ambient) "always-on" else if (wakeMs == null) "at rest" else "${wakeMs / 1000.0} s into the wake"})"
    }

    /**
     * `render --batch <file>`: many pictures in one call, from the one kept face of the project. The
     * file is a JSON list; each entry holds scene keys (as `--set`; they go over the scene of the
     * command) and may name its picture with `out`. Each picture is made exactly as a single
     * `render` with that scene makes it.
     */
    private fun batch(project: ProjectFolder, args: Args, batchFile: File): JsonObject {
        if (!batchFile.isFile) wrongUse("The batch file ${batchFile.path} is missing")
        val list = runCatching { AndroidJson.parse(batchFile.readText()) }.getOrNull() as? JsonArray
            ?: wrongUse("The batch file is a JSON list of scenes, for example [{\"sgv\": 250}, {\"wake\": 2.5, \"out\": \"wake.png\"}]")
        if (list.isEmpty()) wrongUse("The batch file holds no scene")
        val dir = args.file("out") ?: File(project.outDir("render"), "batch")
        if (dir.isFile) wrongUse("With --batch, --out names a folder; ${dir.path} is a file")
        val base = sceneOf(project, args)
        val known = Scenes.KEYS.map { it.name }.toSet()
        // Read every entry before the first picture: a wrong entry stops the batch with nothing drawn
        val jobs = list.mapIndexed { index, entry ->
            entry as? JsonObject ?: wrongUse("Entry $index of the batch is not an object of scene keys")
            var scene = base
            for ((key, value) in entry) {
                if (key == "out") continue
                if (key.substringBefore('.') !in known) wrongUse("Entry $index of the batch: '$key' is no scene key. Scene keys: ${known.joinToString(", ")}")
                scene = if ('.' in key) JsonPaths.set(scene, JsonPaths.parse(key), value) as JsonObject else Scenes.over(scene, JsonObject(mapOf(key to value)))
            }
            scenes.read(scene)
            val name = (entry["out"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { if ('.' in File(it).name) it else "$it.png" } ?: "%03d.png".format(index)
            Triple(File(name).let { if (it.isAbsolute) it else File(dir, name) }, scene, JsonObject(entry - "out"))
        }
        jobs.groupBy { it.first.absolutePath }.values.firstOrNull { it.size > 1 }?.let { wrongUse("Two entries of the batch write the same picture: ${it.first().first.path}") }
        val cwf = loader.load(project)
        val began = System.nanoTime()
        val warnings = LinkedHashSet(facts.warnings())
        val lines = ArrayList<String>()
        val files = jobs.map { (file, scene, keys) ->
            val start = System.nanoTime()
            val drawn = draw(project, cwf, scene, args, file)
            drawn.warning?.let { warnings += it }
            warnings += designWins(cwf, scene)
            lines += drawn.text(file)
            buildJsonObject {
                put("file", file.absolutePath)
                put("keys", keys)
                put("seconds", (System.nanoTime() - start) / 1e9)
            }
        }
        val seconds = (System.nanoTime() - began) / 1e9
        return buildJsonObject {
            put("dir", dir.absolutePath)
            put("files", JsonArray(files))
            put("seconds", seconds)
            put("aapsCommit", facts.aapsCommit ?: "unknown")
            put("warnings", JsonArray(warnings.map { JsonPrimitive(it) }))
            put("text", (lines + String.format(Locale.ROOT, "%d pictures in %.2f s.", files.size, seconds) + warnings.map { "warning: $it" }).joinToString("\n"))
        }
    }

    /** Draws the design [cwf] for [scene] into [file], with `--only`, `--hide`, `--annotate`, `--grid` and `--zoom` of [args]. */
    private fun draw(project: ProjectFolder, cwf: CwfData, scene: JsonObject, args: Args, file: File): Drawn {
        val values = scenes.read(scene)
        val parts = DesignParts(cwf)
        val shown = shown(parts, args)
        val layers = shown.layers
        val views = shown.views
        var bitmap = preview.picture(faceKey(project), parts.with(layers, views, transparent = false), values)
        if (args.flag("grid") || args.flag("annotate")) bitmap = pictures.editable(bitmap).also { bitmap.recycle() }
        if (args.flag("grid")) pictures.grid(bitmap)
        if (args.flag("annotate")) pictures.annotate(bitmap, partBoxes(project, cwf, parts, values, layers, views))
        args.text("zoom")?.let { box -> bitmap = pictures.zoom(bitmap, box).also { if (it !== bitmap) bitmap.recycle() } }
        pictures.save(bitmap, file)
        val size = "${bitmap.width} x ${bitmap.height}"
        bitmap.recycle()
        return Drawn(size, values.wakeMs, values.ambient, AMBIENT_WAKE_WARNING.takeIf { values.ambient && values.wakeMs != null })
    }

    /** The answer of a picture command (design 5.4: every answer says which AAPS commit drew it). */
    fun picture(file: File, scene: JsonObject, text: String, extraWarnings: List<String> = emptyList()): JsonObject = buildJsonObject {
        val warnings = facts.warnings() + extraWarnings
        put("file", file.absolutePath)
        put("scene", scene)
        put("aapsCommit", facts.aapsCommit ?: "unknown")
        put("warnings", JsonArray(warnings.map { JsonPrimitive(it) }))
        put("text", (listOf(text) + warnings.map { "warning: $it" }).joinToString("\n"))
    }

    /**
     * Where each shown layer and view of the design draws in the scene: each one is drawn alone on a
     * clear background. In paint order as far as the editor knows it: the views first, then the layers.
     */
    fun partInks(project: ProjectFolder, parts: DesignParts, values: SceneValues, layers: Set<Int>?, views: Set<String>?): List<PartInk> {
        val base = "${project.hash()}|${values.feedKey}|${values.wakeMs}|${values.ambient}|${values.size}|${values.options}|${values.colours}|${values.watch}"
        val result = ArrayList<PartInk>()
        for (view in parts.shownViews) if (views == null || view in views) result += ink("$base|v$view") { alone(project, parts.with(emptySet(), setOf(view), transparent = true), values, view, null, view) }
        for (index in 0 until parts.layerCount) if (layers == null || index in layers) result += ink("$base|l$index") { alone(project, parts.with(setOf(index), emptySet(), transparent = true), values, "L$index", index, null) }
        return result
    }

    /** The boxes of [partInks], for `--annotate`. A part that draws nothing has no box. */
    fun partBoxes(project: ProjectFolder, cwf: CwfData, parts: DesignParts, values: SceneValues, layers: Set<Int>?, views: Set<String>?): List<LabelBox> =
        partInks(project, parts, values, layers, views).mapNotNull { it.box }

    private fun ink(key: String, make: () -> PartInk): PartInk = inks.getOrPut(key, make)

    private fun alone(project: ProjectFolder, design: CwfData, values: SceneValues, label: String, layer: Int?, view: String?): PartInk {
        val bitmap = preview.picture(faceKey(project), design, values)
        val pixels = WffPictures.pixelsOf(bitmap)
        bitmap.recycle()
        val mask = BitSet(pixels.argb.size)
        for (i in pixels.argb.indices) if (pixels.argb[i] ushr 24 != 0) mask.set(i)
        val box = WffPictures.inkBox(pixels, 1)?.let { LabelBox(label, it.left, it.top, it.width, it.height) }
        return PartInk(layer, view, box, mask, pixels.width)
    }

    /** `scene`: shows the scene of the project, and changes, keeps or loads it. */
    fun scene(project: ProjectFolder?, args: Args): JsonObject {
        if (args.flag("presets")) return buildJsonObject {
            put("presets", JsonObject(Scenes.PRESETS))
            put("text", Scenes.PRESETS.entries.joinToString("\n") { (name, keys) -> "$name: $keys" })
        }
        project ?: wrongUse("No project: run facetool inside a project folder, or give --project <dir>")
        val store = SceneStore(project)
        if (args.flag("list")) return buildJsonObject {
            put("names", JsonArray(store.names().map { JsonPrimitive(it) }))
            put("text", store.names().joinToString("\n").ifEmpty { "No named scenes. Keep one with facetool scene --save <name>" })
        }
        var changed = false
        if (args.flag("reset")) {
            store.use(Scenes.defaults())
            changed = true
        }
        args.text("use")?.let { name ->
            store.use(Scenes.over(Scenes.defaults(), store.load(name)))
            changed = true
        }
        val scene = Scenes.forCommand(project, args.json)
        // Check it before it is kept: a scene that does not read must not become the project's scene
        scenes.read(scene)
        if (args.text("scene") != null || Scenes.sets(args.json).isNotEmpty()) {
            store.use(scene)
            changed = true
        }
        args.text("save")?.let { store.save(it, scene) }
        args.file("out")?.let { it.absoluteFile.parentFile?.mkdirs(); it.writeText(scene.toString()) }
        val defaults = Scenes.defaults()
        val differs = scene.filter { defaults[it.key] != it.value }
        return buildJsonObject {
            put("scene", scene)
            put("changed", changed)
            // For the page: what a slot shows when the scene key slots leaves it out
            put("slotDefaults", JsonObject(preview.slotDefaults().mapValues { JsonPrimitive(it.value) }))
            put("text", (if (differs.isEmpty()) "The scene is the default scene." else "The scene differs from the defaults in:\n" + differs.entries.joinToString("\n") { "  ${it.key} = ${it.value}" }) +
                (args.text("save")?.let { "\nKept as '$it'." } ?: ""))
        }
    }

    companion object {

        /** For a moment of the wake asked of a scene with `ambient`. */
        const val AMBIENT_WAKE_WARNING = "the scene has ambient=true: always-on has no wake animation, so this is the always-on face"

        /**
         * The scene keys `watch.X` that do nothing because the design sets the same switch in its metadata
         * (`metadata.key_X`): the face writes the design's value into the watch setting each time it reads
         * the design (`CustomWatchface.updatePref`), as on the watch. One warning per such key.
         */
        fun designWins(cwf: CwfData, scene: JsonObject): List<String> {
            val watch = (scene["watch"] as? JsonObject)?.keys.orEmpty()
            if (watch.isEmpty()) return emptyList()
            val metadata = runCatching { AndroidJson.parseObject(cwf.json, "the design")["metadata"] as? JsonObject }.getOrNull() ?: return emptyList()
            val byName = metadata.keys.filter { it.startsWith("key_") }.associateBy { plain(it.removePrefix("key_")) }
            return watch.mapNotNull { key ->
                byName[plain(key)]?.let { meta ->
                    "watch.$key does nothing here: the design sets metadata.$meta, and the design's switch wins, as on the watch. " +
                        "Change metadata.$meta to try another value, or remove it to let the wearer choose."
                }
            }
        }

        /** A setting name without `_` and in lower case: `show_complication_1` and `show_complication1` are the same. */
        private fun plain(name: String): String = name.replace("_", "").lowercase(Locale.ROOT)

        /** The preview picture of a design, as the designs of AAPS have it: `CustomWatchface.jpg`, 400 x 400. */
        const val PREVIEW_NAME = "CustomWatchface"
        const val PREVIEW_SIZE = 400
    }
}
