package app.aaps.facetool.editor.server

import android.graphics.Color
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.facetool.editor.model.EditorConfig
import app.aaps.facetool.editor.model.ExitCodes
import app.aaps.facetool.editor.model.FacetoolException
import app.aaps.facetool.editor.model.ProjectFolder
import app.aaps.facetool.editor.model.Projects
import app.aaps.facetool.editor.model.Scenes
import app.aaps.facetool.editor.model.wrongUse
import app.aaps.shared.impl.weardata.JsonKeyValues
import app.aaps.shared.impl.weardata.JsonKeys
import app.aaps.shared.impl.weardata.ResFileMap
import app.aaps.shared.impl.weardata.ViewKeys
import app.aaps.wear.watchfaces.animation.AnimLayerSpec
import app.aaps.wear.watchfaces.animation.Formula
import app.aaps.wear.watchfaces.animation.LayerAnimator
import app.aaps.wear.watchfaces.animation.LayerProp
import app.aaps.wear.watchfaces.animation.WakeAnimationJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import java.time.Clock

/** One line of the lint (design 8.3). */
class LintFinding(val severity: String, val source: String, val path: String, val text: String)

/**
 * `lint`, `expand`, `keys` and `manual` (design 8.2, 8.3). The engine side of the lint is what the
 * engine's own readers say; the editor adds checks of keys, resources and limits, and checks by
 * drawing. The lint of the WFF converter is added by [wffLint] (step 9).
 *
 * @param wffLint the WFF lint lines of a design, or null while it is not built
 */
class LintCommands(
    private val preview: EnginePreview, private val loader: DesignLoader, private val scenes: SceneReader, private val render: PictureCommands,
    private val manual: EngineManual, private val config: EditorConfig, private val clock: Clock,
    private val wffLint: ((ProjectFolder, CwfData) -> List<LintFinding>)?
) {

    fun lint(project: ProjectFolder, args: Args): JsonObject {
        val findings = ArrayList<LintFinding>()
        val cwf = try {
            loader.load(project)
        } catch (e: FacetoolException) {
            findings += LintFinding("error", "engine", "", e.message ?: "the design does not read")
            return answer(findings, ExitCodes.DESIGN_UNREADABLE, args.flag("full"))
        }
        val json = JSONObject(cwf.json)
        val parts = DesignParts(cwf)
        findings += engineErrors(json)
        findings += unknownKeys(json)
        findings += resources(project, json, cwf)
        findings += limits(json, parts)
        findings += wffForms(json, parts)
        val scene = Scenes.forCommand(project, args.json)
        val values = scenes.read(scene)
        if (findings.none { it.severity == "error" }) {
            findings += byDrawing(project, cwf, parts, values)
            findings += wffKeysChangeNothing(project, cwf, values)
        }
        if (!args.flag("no-wff")) wffLint?.let { findings += it(project, cwf) }
        return answer(findings, if (findings.any { it.severity == "error" }) ExitCodes.CHECK_ERRORS else ExitCodes.OK, args.flag("full"))
    }

    /** The engine's errors of a design, for `save`: an empty list when the engine reads it. */
    fun engineCheck(project: ProjectFolder): List<String> {
        val cwf = try {
            DesignLoader().load(project)
        } catch (e: FacetoolException) {
            return listOf(e.message ?: "the design does not read")
        }
        return engineErrors(JSONObject(cwf.json)).map { it.text }
    }

    private fun answer(findings: List<LintFinding>, exitCode: Int, full: Boolean): JsonObject = buildJsonObject {
        put("findings", buildJsonArray {
            for (f in findings) add(buildJsonObject { put("severity", f.severity); put("source", f.source); put("path", f.path); put("text", f.text) })
        })
        put("errors", findings.count { it.severity == "error" })
        put("warnings", findings.count { it.severity == "warning" })
        if (exitCode != ExitCodes.OK) put("exitCode", exitCode)
        put("text", if (findings.isEmpty()) "No findings." else if (full) findings.joinToString("\n") { f -> "${f.severity} [${f.source}] ${f.path.ifEmpty { "-" }}: ${f.text}" } else compact(findings))
    }

    /**
     * The text of the answer without repeats: layers (or pictures) of one source with the same
     * message share one line, and the same line of WFF 4 and WFF 2 is one line with both sources.
     * The `findings` list of the JSON keeps every finding.
     */
    private fun compact(findings: List<LintFinding>): String {
        val layer = Regex("^(L\\d+)\\s+\\S+\\s+(.*)$", RegexOption.DOT_MATCHES_ALL)
        val picture = Regex("^(picture \\d+): (.*)$", RegexOption.DOT_MATCHES_ALL)
        // Step 1: per source, one group for each severity, kind of id and message; the group keeps its ids
        class Group(val severity: String, val source: String, val kind: String, val message: String, val ids: MutableList<String> = ArrayList())
        val groups = LinkedHashMap<List<String>, Group>()
        for (f in findings) {
            val l = layer.find(f.text)
            val p = picture.find(f.text)
            val (kind, id, message) = when {
                l != null -> Triple("layer", l.groupValues[1], l.groupValues[2].replace(Regex("\\s+"), " "))
                p != null -> Triple("picture", p.groupValues[1], p.groupValues[2])
                else      -> Triple("path:" + f.path, "", f.text)
            }
            groups.getOrPut(listOf(f.severity, f.source, kind, message)) { Group(f.severity, f.source, kind, message) }.ids += id
        }
        // Step 2: groups with the same ids and message of other sources join as one line
        val lines = LinkedHashMap<List<String>, MutableList<Group>>()
        for (g in groups.values) lines.getOrPut(listOf(g.severity, g.kind, g.message, g.ids.joinToString(",")), { ArrayList() }) += g
        return lines.values.joinToString("\n") { same ->
            val g = same.first()
            val sources = same.joinToString(",") { it.source }
            val where = when {
                g.kind.startsWith("path:") -> g.kind.removePrefix("path:").ifEmpty { "-" }
                g.kind == "picture"        -> (if (g.ids.size == 1) "" else "pictures ") + g.ids.joinToString(", ") { it.removePrefix("picture ").let { n -> if (g.ids.size == 1) it else n } }
                g.ids.size == 1            -> g.ids.first()
                else                       -> g.ids.joinToString(", ") + " (${g.ids.size})"
            }
            "${g.severity} [$sources] $where: ${g.message}"
        }
    }

    /**
     * What the engine itself says: its reader of the animation (`WakeAnimationJson`: templates, layer
     * sources, own functions) and its animator (`LayerAnimator`: formulas, `let`, the limits of layers
     * and copies). A layer number in these texts counts the plain layers (see `expand`).
     */
    private fun engineErrors(json: JSONObject): List<LintFinding> {
        val spec = WakeAnimationJson({ text, default -> runCatching { Color.parseColor(text) }.getOrDefault(default) }) { null }.read(json) ?: return emptyList()
        val animator = LayerAnimator(spec.layers, spec.lets, functions = spec.functions)
        return (spec.errors + animator.errors).map { LintFinding("error", "engine", "animation", it) }
    }

    /** Keys the engine does not read, by the key lists of the manual and the engine's enums, with the nearest known key. */
    private fun unknownKeys(json: JSONObject): List<LintFinding> {
        val result = ArrayList<LintFinding>()
        val tables = manual.keyTables
        fun table(title: String) = tables.entries.firstOrNull { it.key.startsWith(title) }?.value.orEmpty()
        val layerKeys = (table("In a layer") + LayerProp.entries.map { it.key } + listOf("use", "wff", "if")).toSet()
        val clipKeys = table("In clip").toSet()
        val animationKeys = table("In animation").toSet()
        val viewNames = ViewKeys.entries.map { it.key }.toSet()
        val topKeys = (table("At the top") + JsonKeys.entries.map { it.key } + viewNames + listOf("dynData", "dynPref", "wff")).toSet()
        // Without the lists of the manual there is nothing to check against
        if (table("In a layer").isEmpty()) return listOf(LintFinding("info", "editor", "", "the key reference of the manual was not found: unknown keys are not checked"))
        for (key in json.keys()) if (key !in topKeys) result += unknown(key, key, topKeys, "the design")
        json.optJSONObject("metadata")?.let { meta ->
            val known = CwfMetadataKey.entries.map { it.key }.toSet()
            for (key in meta.keys()) if (key !in known && !key.startsWith("key_")) result += unknown("metadata.$key", key, known, "the metadata")
        }
        val animation = json.optJSONObject("animation") ?: return result
        for (key in animation.keys()) if (key !in animationKeys) result += unknown("animation.$key", key, animationKeys, "the animation")
        val templates = animation.optJSONObject("templates")
        fun layers(array: JSONArray?, path: String) {
            for (i in 0 until (array?.length() ?: 0)) {
                val layer = array!!.optJSONObject(i) ?: continue
                val at = "$path[$i]"
                val use = layer.optString("use")
                if (layer.has("use")) {
                    // The keys of a use are the parameters of its template
                    val params = templates?.optJSONObject(use)?.optJSONObject("params")?.keys()?.asSequence()?.toSet() ?: continue
                    // wff on a use entry is no parameter: the engine hands it to the layers the template makes
                    for (key in layer.keys()) if (key != "use" && key != "wff" && key !in params) result += unknown("$at.$key", key, params, "the template '$use'")
                } else {
                    for (key in layer.keys()) if (key !in layerKeys) result += unknown("$at.$key", key, layerKeys, "a layer")
                    layer.optJSONObject("clip")?.let { clip -> for (key in clip.keys()) if (key !in clipKeys) result += unknown("$at.clip.$key", key, clipKeys, "a clip") }
                }
            }
        }
        layers(animation.optJSONArray("layers"), "animation.layers")
        templates?.keys()?.forEach { name -> layers(templates.optJSONObject(name)?.optJSONArray("layers"), "animation.templates.$name.layers") }
        return result
    }

    private fun unknown(path: String, key: String, known: Set<String>, where: String): LintFinding {
        val near = known.minByOrNull { distance(it.lowercase(), key.lowercase()) }?.takeIf { distance(it.lowercase(), key.lowercase()) <= 2 }
        return LintFinding("warning", "editor", path, "the key '$key' is not a key of $where that the manual lists" + (near?.let { " (did you mean '$it'?)" } ?: ""))
    }

    /** The edit distance of two words. */
    private fun distance(a: String, b: String): Int {
        var row = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val next = IntArray(b.length + 1)
            next[0] = i
            for (j in 1..b.length) next[j] = minOf(row[j] + 1, next[j - 1] + 1, row[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            row = next
        }
        return row[b.length]
    }

    /** Resources: named but missing and the zip size (the checks of `save`), and present but not used. */
    private fun resources(project: ProjectFolder, json: JSONObject, cwf: CwfData): List<LintFinding> {
        val result = ArrayList<LintFinding>()
        val zipSize = project.zipBytes().size
        for (finding in Projects(config, clock).check(project, project.design(), zipSize)) result += LintFinding(finding.severity, "editor", finding.path ?: "", finding.text)
        val text = cwf.json
        val fixed = ResFileMap.entries.map { it.fileName }.toSet()
        for (key in cwf.resData.keys.sorted()) {
            if (key in fixed || "\"$key\"" in text) continue
            result += LintFinding("warning", "editor", key, "the resource '$key' is in the folder, but the design does not name it")
        }
        return result
    }

    /** The limits of the engine: what is over them is cut off without an error on the watch. */
    private fun limits(json: JSONObject, parts: DesignParts): List<LintFinding> {
        val result = ArrayList<LintFinding>()
        // The engine's reader cuts these off before its animator can say so
        val plain = parts.expanded.sumOf { it.size }
        if (plain > AnimLayerSpec.MAX_LAYERS) result += LintFinding("error", "engine", "animation.layers", "$plain layers after the templates are expanded: the engine reads only the first ${AnimLayerSpec.MAX_LAYERS}")
        parts.expanded.forEachIndexed { index, layers ->
            for (layer in layers) {
                val count = layer.opt("count") as? Number ?: continue
                if (count.toInt() > AnimLayerSpec.MAX_COPIES) result += LintFinding("warning", "engine", "animation.layers[$index].count", "${count.toInt()} copies: the engine draws at most ${AnimLayerSpec.MAX_COPIES} of one layer")
            }
        }
        val lets = json.optJSONObject("animation")?.optJSONObject("let")?.length() ?: 0
        if (lets > AnimLayerSpec.MAX_LETS) result += LintFinding("error", "engine", "animation.let", "$lets names: the engine reads only the first ${AnimLayerSpec.MAX_LETS}")
        return result
    }

    /** The forms of the `wff` key (motion doc 4.2): `false`, `"still"` or an object. */
    private fun wffForms(json: JSONObject, parts: DesignParts): List<LintFinding> {
        val result = ArrayList<LintFinding>()
        fun check(value: Any?, path: String) {
            if (value == null || value == false || value == "still" || value is JSONObject) return
            result += LintFinding("error", "editor", path, "wff must be false, \"still\" or an object, not $value")
        }
        parts.expanded.forEachIndexed { index, layers -> for (layer in layers) check(layer.opt("wff"), "animation.layers[$index].wff") }
        for (view in parts.views) check(json.optJSONObject(view)?.opt("wff"), "$view.wff")
        return result
    }

    /** Checks by drawing each part alone at rest: a part that draws only outside the round screen, or nothing. */
    private fun byDrawing(project: ProjectFolder, cwf: CwfData, parts: DesignParts, values: SceneValues): List<LintFinding> {
        val result = ArrayList<LintFinding>()
        val rest = SceneValues(values.timeMs, values.size, null, values.data, values.options, values.colours, values.watch, values.ambient)
        val radius = values.size / 2.0
        val inks = render.partInks(project, parts, rest, null, null)
        val hidden = ArrayList<Int>()
        for (ink in inks) {
            val path = ink.layer?.let { "animation.layers[$it]" } ?: ink.view!!
            if (ink.box == null) {
                ink.layer?.let { hidden += it }
                continue
            }
            var inside = false
            var i = ink.mask.nextSetBit(0)
            while (i >= 0 && !inside) {
                val dx = i % ink.width + 0.5 - radius
                val dy = i / ink.width + 0.5 - radius
                inside = dx * dx + dy * dy <= radius * radius
                i = ink.mask.nextSetBit(i + 1)
            }
            if (!inside) result += LintFinding("warning", "editor", path, "it draws only outside the round screen in this scene (at rest)")
        }
        // A layer that draws nothing at rest may show in the wake: look at three moments of it
        if (hidden.isNotEmpty()) {
            val wakeMs = JSONObject(cwf.json).optJSONObject("animation")?.optLong("durationMs", 6000L) ?: 6000L
            val seen = HashSet<Int>()
            for (share in listOf(0.25, 0.5, 0.75)) {
                val moment = SceneValues(values.timeMs, values.size, (wakeMs * share).toLong(), values.data, values.options, values.colours, values.watch, values.ambient)
                for (ink in render.partInks(project, parts, moment, hidden.toSet() - seen, emptySet())) if (ink.box != null) seen += ink.layer!!
            }
            for (layer in hidden) if (layer !in seen) result += LintFinding("warning", "editor", "animation.layers[$layer]", "it never shows in this scene: not at rest and not at 3 moments of the wake")
        }
        return result
    }

    /**
     * Design 12.10: the `wff` keys must change nothing in the engine (but `only`, which the engine
     * does not know yet): the design with and without them draws the same pictures.
     */
    private fun wffKeysChangeNothing(project: ProjectFolder, cwf: CwfData, values: SceneValues): List<LintFinding> {
        if ("\"wff\"" !in cwf.json) return emptyList()
        val stripped = JSONObject(cwf.json)
        fun strip(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val wff = value.opt("wff")
                    if (wff != null && (wff as? JSONObject)?.optBoolean("only") != true) value.remove("wff")
                    for (key in value.keys().asSequence().toList()) strip(value.opt(key))
                }
                is JSONArray  -> for (i in 0 until value.length()) strip(value.opt(i))
            }
        }
        strip(stripped)
        val without = CwfData(stripped.toString(), cwf.metadata, cwf.resData)
        val wakeMs = JSONObject(cwf.json).optJSONObject("animation")?.optLong("durationMs", 6000L) ?: 6000L
        for (wake in listOf(null, wakeMs / 3, wakeMs * 2 / 3)) {
            val moment = SceneValues(values.timeMs, values.size, wake, values.data, values.options, values.colours, values.watch, values.ambient)
            val a = preview.picture(render.faceKey(project), cwf, moment)
            val b = preview.picture(render.faceKey(project), without, moment)
            val same = a.sameAs(b)
            a.recycle()
            b.recycle()
            if (!same) return listOf(LintFinding("error", "engine", "", "the wff keys change what the engine draws (${wake?.let { "${it / 1000.0} s into the wake" } ?: "at rest"}): they must not"))
        }
        return emptyList()
    }

    /** `expand`: the plain layers the engine reads for each layer of the JSON, after its templates are expanded. */
    fun expand(project: ProjectFolder, args: Args): JsonObject {
        val parts = DesignParts(loader.load(project))
        val only = args.text("path")?.let { path ->
            Regex("^animation\\.layers\\[(\\d+)]$").find(path.trim())?.groupValues?.get(1)?.toInt()?.takeIf { it < parts.layerCount } ?: wrongUse("expand takes a layer path like animation.layers[3]; the design has ${parts.layerCount} layers")
        }
        var number = 0
        val rows = parts.expanded.mapIndexed { index, layers -> Triple(index, number, layers).also { number += layers.size } }.filter { only == null || it.first == only }
        return buildJsonObject {
            put("layers", buildJsonArray {
                for ((index, first, layers) in rows) add(buildJsonObject {
                    put("path", "animation.layers[$index]")
                    put("firstPlainLayer", first)
                    put("plain", JsonArray(layers.map { JsonPrimitive(it.toString()) }))
                })
            })
            put("errors", JsonArray(parts.errors.map { JsonPrimitive(it) }))
            put("text", (rows.joinToString("\n") { (index, first, layers) ->
                "animation.layers[$index] -> " + (if (layers.isEmpty()) "nothing" else "plain layer${if (layers.size > 1) "s" else ""} L$first${if (layers.size > 1) " to L${first + layers.size - 1}" else ""} (the numbers of the planner and the WFF lint)") +
                    layers.joinToString("") { "\n  $it" }
            } + parts.errors.joinToString("") { "\nerror: $it" }).ifEmpty { "The design has no layers." })
        }
    }

    /** `keys`: the names the engine knows, from its own enums and the key reference of its manual. */
    fun keys(args: Args): JsonObject {
        val tables = manual.keyTables
        fun table(title: String) = tables.entries.firstOrNull { it.key.startsWith(title) }?.value.orEmpty()
        val all = linkedMapOf(
            "layer" to (table("In a layer") + LayerProp.entries.map { it.key }).distinct(),
            "view" to (ViewKeys.entries.map { it.key } + JsonKeys.entries.map { it.key } + JsonKeyValues.entries.map { it.key }).distinct(),
            "metadata" to CwfMetadataKey.entries.map { it.key },
            "values" to Regex("v_[A-Za-z0-9_]+").findAll(runCatching { manual.text("values") }.getOrDefault("")).map { it.value }.distinct().toList(),
            "functions" to Formula.FUNCTIONS.map { (name, values) -> "$name(${if (values.first == values.last) values.first.toString() else "${values.first} to ${values.last}"})" }
        )
        val kind = args.text("kind")
        val shown = if (kind == null) all else mapOf(kind to (all[kind] ?: wrongUse("keys takes layer, view, metadata, values or functions")))
        return buildJsonObject {
            for ((name, keys) in shown) put(name, JsonArray(keys.map { JsonPrimitive(it) }))
            put("text", shown.entries.joinToString("\n") { (name, keys) -> "$name: ${keys.joinToString(", ")}" } + "\nSee facetool manual reference for what each key means.")
        }
    }

    /** `manual`: a chapter, or a section of a chapter, of the engine manual of the AAPS checkout as plain text. */
    fun manual(args: Args): JsonObject {
        val topic = args.text("topic")
        if (args.flag("list") || topic == null) return buildJsonObject {
            put("topics", buildJsonArray {
                for (t in manual.topics) add(buildJsonObject {
                    put("id", t.id)
                    put("title", t.title)
                    put("sections", buildJsonArray { for (s in t.sections) add(buildJsonObject { put("id", s.id); put("title", s.title) }) })
                })
            })
            put("text", manual.topics.joinToString("\n") { t -> "${t.id}: ${t.title}" + t.sections.joinToString("") { "\n    ${it.id}: ${it.title}" } } +
                "\nRead one with facetool manual <id>: a chapter, or one section of it.")
        }
        return buildJsonObject {
            put("topic", topic)
            put("text", manual.text(topic))
        }
    }
}
