package app.aaps.facetool.editor.server

import android.graphics.Bitmap
import app.aaps.core.interfaces.rx.weardata.CwfData
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
import java.io.File
import java.util.BitSet

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
        val scene = sceneOf(project, args)
        val values = scenes.read(scene)
        val cwf = loader.load(project)
        val parts = DesignParts(cwf)
        val shown = shown(parts, args)
        val layers = shown.layers
        val views = shown.views
        var bitmap = preview.picture(faceKey(project), parts.with(layers, views, transparent = false), values)
        if (args.flag("grid") || args.flag("annotate")) bitmap = pictures.editable(bitmap).also { bitmap.recycle() }
        if (args.flag("grid")) pictures.grid(bitmap)
        if (args.flag("annotate")) pictures.annotate(bitmap, partBoxes(project, cwf, parts, values, layers, views))
        args.text("zoom")?.let { box -> bitmap = pictures.zoom(bitmap, box).also { if (it !== bitmap) bitmap.recycle() } }
        val file = args.file("out") ?: File(project.outDir("render"), "render.png")
        pictures.save(bitmap, file)
        val size = "${bitmap.width} x ${bitmap.height}"
        bitmap.recycle()
        return picture(
            file, scene, "Wrote ${file.path} ($size, ${if (values.wakeMs == null) "at rest" else "${values.wakeMs / 1000.0} s into the wake"})",
            extraWarnings = listOfNotNull(parts.wakeTextWarning.takeIf { values.wakeMs != null })
        )
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
        val base = "${project.hash()}|${values.feedKey}|${values.wakeMs}|${values.size}|${values.options}|${values.colours}|${values.watch}"
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
            put("text", (if (differs.isEmpty()) "The scene is the default scene." else "The scene differs from the defaults in:\n" + differs.entries.joinToString("\n") { "  ${it.key} = ${it.value}" }) +
                (args.text("save")?.let { "\nKept as '$it'." } ?: ""))
        }
    }
}
