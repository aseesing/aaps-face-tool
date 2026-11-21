package app.aaps.facetool.editor.server

import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.facetool.editor.model.ProjectFolder
import app.aaps.facetool.editor.model.unreadable
import app.aaps.facetool.editor.model.wrongUse
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.wear.watchfaces.animation.Templates
import app.aaps.wear.watchfaces.animation.WakeAnimationJson
import app.aaps.wear.watchfaces.animation.WakeAnimationSpec
import app.aaps.wear.watchfaces.wff.WffDesignParts
import org.json.JSONArray
import org.json.JSONObject

/** Reads the design of a project folder the way the phone reads a zip the user picks. */
class DesignLoader {

    private var lastHash: String? = null
    private var last: CwfData? = null

    fun load(project: ProjectFolder): CwfData {
        if (!project.designFile.isFile) unreadable("${project.dir} is not a project folder: it has no CustomWatchface.json. Use facetool open <zip>")
        val hash = project.hash()
        last?.takeIf { hash == lastHash }?.let { return it }
        val cwf = runCatching { ZipWatchfaceFormat.loadCustomWatchface(project.zipBytes(), "${project.dir.name}.zip", authorization = true)?.cwfData }.getOrNull()
            ?: unreadable("The design of ${project.dir} does not read as a design zip (the JSON or a resource is broken)")
        lastHash = hash
        last = cwf
        return cwf
    }
}

/**
 * Copies of a design that show only some of its layers and views, made the way the converter makes
 * its copies (`WffDesignParts` of AAPS): a view that is not wanted is `gone`, a layer that is not
 * wanted has `visible` 0. The engine itself is not changed.
 *
 * A layer is named by its index in `animation.layers` of the JSON, as in the paths of the editor
 * (`animation.layers[24]`). A layer with `use` stands for all the plain layers its template makes.
 */
class DesignParts(private val source: CwfData) {

    private val json = JSONObject(source.json)
    private val animation: JSONObject? = json.optJSONObject(WakeAnimationSpec.JSON_KEY)

    /** What the templates said while the layers were expanded. */
    val errors = ArrayList<String>()

    /** Per layer of the JSON: the plain layers the engine reads for it. */
    val expanded: List<List<JSONObject>> = run {
        val layers = animation?.optJSONArray(WakeAnimationJson.LAYERS) ?: JSONArray()
        val templates = Templates(own = animation?.optJSONObject(WakeAnimationJson.TEMPLATES))
        (0 until layers.length()).map { i -> layers.optJSONObject(i)?.let { templates.expand(JSONArray().put(it), errors) }.orEmpty() }
    }

    val layerCount: Int get() = expanded.size

    /** The classic views of the design: every key with a `visibility`, but not the background. */
    val views: List<String> = json.keys().asSequence().filter { key -> key != BACKGROUND && json.optJSONObject(key)?.has(VISIBILITY) == true }.toList()

    /** The views that the design shows. */
    val shownViews: List<String> = views.filter { json.getJSONObject(it).optString(VISIBILITY) == "visible" }

    /**
     * The design with only [layers] and [views]; null leaves all of that kind as they are.
     * A [transparent] copy has a clear background, to see what the parts alone draw.
     */
    fun with(layers: Set<Int>?, views: Set<String>?, transparent: Boolean): CwfData {
        if (layers == null && views == null && !transparent) return source
        val copy = JSONObject(source.json)
        if (views != null) for (key in this.views) if (key !in views) copy.getJSONObject(key).put(VISIBILITY, "gone")
        if (transparent) copy.put(BACKGROUND, JSONObject().put("width", 400).put("height", 400).put("topmargin", 0).put("leftmargin", 0).put(VISIBILITY, "visible"))
        if (layers != null) copy.optJSONObject(WakeAnimationSpec.JSON_KEY)?.put(WakeAnimationJson.LAYERS, JSONArray().apply {
            expanded.forEachIndexed { index, plain ->
                for (layer in plain) put(JSONObject(layer.toString()).also { if (index !in layers) it.put(VISIBLE, "0") })
            }
        })
        return WffDesignParts.asCwf(source, copy, transparent)
    }

    /**
     * Reads a list as `--only` and `--hide` take it: layer indexes and view names with commas
     * (`3,5,sgv`). Gives the layers and the views it names.
     */
    fun parse(list: String): Pair<Set<Int>, Set<String>> {
        val layers = LinkedHashSet<Int>()
        val views = LinkedHashSet<String>()
        for (word in list.split(",").map { it.trim().removePrefix("L") }.filter { it.isNotEmpty() }) {
            val index = word.toIntOrNull()
            when {
                index != null && index in expanded.indices -> layers += index
                index != null                              -> wrongUse("There is no layer $index: the design has ${expanded.size} layers")
                word in this.views                         -> views += word
                else                                       -> wrongUse("There is no view '$word'. Views: ${this.views.joinToString(", ")}")
            }
        }
        return layers to views
    }

    private companion object {

        const val VISIBLE = "visible"
        const val VISIBILITY = "visibility"
        const val BACKGROUND = "background"
    }
}
