package app.aaps.facetool.editor.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import java.io.File

/** One key of a scene: its name, its default and what it means. */
class SceneKey(val name: String, val default: JsonElement, val help: String)

/**
 * Scenes (design section 7): everything outside the design that changes the picture, as one JSON
 * object. A scene is built from layers, each one over the one before:
 * the defaults, the scene of the project, a `--scene` file, the `--set key=value` pairs.
 */
object Scenes {

    /** The keys of a scene with their defaults. The values are the ones `SnapshotEngine` shows today. */
    val KEYS: List<SceneKey> = listOf(
        SceneKey("time", JsonPrimitive("2026-11-17T10:08:00"), "The local time."),
        SceneKey("zone", JsonPrimitive("Europe/Amsterdam"), "The time zone."),
        SceneKey("size", JsonPrimitive(450), "Pixels of the picture (the face is square)."),
        SceneKey("sgv", JsonPrimitive(121), "The glucose in mg/dl. null is no reading."),
        SceneKey("delta", JsonPrimitive(1.8), "The change of the glucose in mg/dl."),
        SceneKey("avgDelta", JsonPrimitive(1.4), "The average change in mg/dl."),
        SceneKey("direction", JsonPrimitive("→"), "The trend arrow."),
        SceneKey("units", JsonPrimitive("mmol"), "The units the face shows: mmol or mgdl."),
        SceneKey("readingAgeMin", JsonPrimitive(2), "Minutes since the reading."),
        SceneKey("iob", JsonPrimitive(1.25), "Insulin on board in U."),
        SceneKey("iobBolus", JsonPrimitive(0.90), "The bolus part of the insulin on board."),
        SceneKey("iobBasal", JsonPrimitive(0.35), "The basal part of the insulin on board."),
        SceneKey("cob", JsonPrimitive(12), "Carbs on board in g."),
        SceneKey("basalRate", JsonPrimitive(0.85), "The basal rate in U/h."),
        SceneKey("bgi", JsonPrimitive(-7.2), "The glucose impact in mg/dl."),
        SceneKey("reservoir", JsonPrimitive(120), "The pump reservoir in U."),
        SceneKey("tempTarget", JsonPrimitive(100), "The temporary target in mg/dl."),
        SceneKey("phoneBattery", JsonPrimitive(78), "The phone battery in percent."),
        SceneKey("watchBattery", JsonPrimitive(64), "The watch battery in percent."),
        SceneKey("rigBattery", JsonPrimitive("--"), "The rig battery, as the phone writes it."),
        SceneKey("heartRate", JsonPrimitive(72), "The heart rate."),
        SceneKey("steps", JsonPrimitive(6482), "The steps of today."),
        SceneKey("weather", JsonPrimitive("clear"), "The weather: clear, partly, cloudy or rain."),
        SceneKey("temperature", JsonPrimitive(7.5), "The temperature outside."),
        SceneKey("latitude", JsonPrimitive(52.1), "The place, for the sky and the moon."),
        SceneKey("longitude", JsonPrimitive(5.1), "The place, for the sky and the moon."),
        SceneKey("decimalSign", JsonPrimitive(","), "The decimal sign of the phone's language."),
        SceneKey("graph", JsonPrimitive("wave"), "The shape of the graph history: wave, flat, high, low, or a list of values."),
        SceneKey("options", JsonObject(emptyMap()), "The choice per option name, for example {\"moon\": 1}. Empty: the design's defaults."),
        SceneKey("colours", JsonObject(emptyMap()), "colour1 and colour2 of the watch settings. Empty: the design's own."),
        SceneKey("watch", JsonObject(emptyMap()), "Other watch settings by name. Empty: the defaults of a new watch."),
        SceneKey("wake", JsonNull, "Seconds since the wake began. null is the rest picture."),
        SceneKey("ambient", JsonPrimitive(false), "Always-on: the dim face of a watch that is not in use. It has no wake animation."),
        SceneKey("tilt", buildJsonArray { add(0); add(0) }, "The tilt of the watch in degrees, x and y (WFF player only)."),
        SceneKey("visibleSec", JsonPrimitive(5), "WFF player: seconds since the screen came on."),
        SceneKey("tapSec", JsonNull, "WFF player: seconds since the last tap."),
        SceneKey("sgvBefore", JsonNull, "WFF player: the glucose before the last reading."),
        SceneKey("transition", JsonNull, "WFF player, WFF 4: 0 to 1, how far the change out of always-on is.")
    )

    /** The ready scenes of `facetool scene --presets`: the keys each one changes. */
    val PRESETS: Map<String, JsonObject> = linkedMapOf(
        "default" to JsonObject(emptyMap()),
        "high" to buildJsonObject { put("sgv", 250); put("delta", 6.0); put("avgDelta", 5.2); put("direction", "↑"); put("graph", "high") },
        "low" to buildJsonObject { put("sgv", 58); put("delta", -4.0); put("avgDelta", -3.5); put("direction", "↓"); put("graph", "low") },
        "no-data" to buildJsonObject { put("sgv", JsonNull); put("delta", JsonNull); put("avgDelta", JsonNull); put("direction", ""); put("readingAgeMin", JsonNull); put("graph", JsonArray(emptyList())) },
        "old-reading" to buildJsonObject { put("readingAgeMin", 25) },
        "night" to buildJsonObject { put("time", "2026-11-17T23:30:00") },
        "christmas" to buildJsonObject { put("time", "2026-12-25T18:30:00") },
        "low-battery" to buildJsonObject { put("phoneBattery", 12); put("watchBattery", 9) }
    )

    /** The keys that hold an object of single choices. A layer changes single choices of them, not the whole object. */
    private val merged = setOf("options", "colours", "watch")

    /** The scene with every default. */
    fun defaults(): JsonObject = JsonObject(KEYS.associate { it.name to it.default })

    /**
     * One scene from its layers: the defaults, then [project] (the scene of the project), then
     * [file] (a `--scene` file), then [sets] (`--set key=value` pairs, in their order).
     */
    fun merge(project: JsonObject?, file: JsonObject?, sets: List<String>): JsonObject {
        var scene = defaults()
        for (layer in listOfNotNull(project, file)) scene = over(scene, layer)
        for (pair in sets) scene = set(scene, pair)
        return scene
    }

    /** [scene] with the keys of [layer] over it. */
    fun over(scene: JsonObject, layer: JsonObject): JsonObject {
        val result = LinkedHashMap(scene)
        for ((key, value) in layer) {
            val old = result[key]
            result[key] = if (key in merged && old is JsonObject && value is JsonObject) JsonObject(old + value) else value
        }
        return JsonObject(result)
    }

    /**
     * [scene] with one `key=value` pair set. The value is read as JSON (`180`, `true`, `null`,
     * `[1, 2]`); a value that is not JSON is taken as text (`weather=rain`). The key may be a
     * path: `options.moon=1`.
     */
    fun set(scene: JsonObject, pair: String): JsonObject {
        val at = pair.indexOf('=')
        if (at <= 0) wrongUse("--set needs key=value, got '$pair'")
        val value = pair.substring(at + 1)
        val element = runCatching { AndroidJson.parse(value) }.getOrElse { JsonPrimitive(value) }
        return JsonPaths.set(scene, JsonPaths.parse(pair.substring(0, at)), element) as JsonObject
    }

    /** The `--set` pairs of command arguments: the key `set` as a list of texts, or one text. */
    fun sets(args: JsonObject): List<String> = when (val set = args["set"]) {
        is JsonArray     -> set.map { (it as JsonPrimitive).content }
        is JsonPrimitive -> listOf(set.content)
        else             -> emptyList()
    }

    /**
     * The scene for a command: the defaults, the project's scene, the file of the argument `scene`
     * and the pairs of the argument `set`.
     */
    fun forCommand(project: ProjectFolder?, args: JsonObject): JsonObject {
        val file = (args["scene"] as? JsonPrimitive)?.content?.let { path ->
            val file = File(path)
            if (!file.isFile) wrongUse("The scene file $path is missing")
            AndroidJson.parseObject(file.readText(), path)
        }
        return merge(project?.meta()?.get("scene") as? JsonObject, file, sets(args))
    }
}

/** The named scenes of a project, in `.facetool/scenes/<name>.json`. A file holds only the keys that differ from the defaults. */
class SceneStore(private val project: ProjectFolder) {

    private val dir: File get() = File(project.toolDir, "scenes")

    fun names(): List<String> = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.map { it.name.removeSuffix(".json") }.sorted()

    /** The keys of the named scene, or of the preset with that name. */
    fun load(name: String): JsonObject {
        val file = File(dir, "$name.json")
        if (file.isFile) return AndroidJson.parseObject(file.readText(), file.path)
        return Scenes.PRESETS[name] ?: wrongUse("There is no scene '$name'. Scenes: ${(names() + Scenes.PRESETS.keys).joinToString(", ")}")
    }

    /** Keeps [scene] under [name]: only the keys that differ from the defaults are written. */
    fun save(name: String, scene: JsonObject) {
        if (!name.matches(Regex("[A-Za-z0-9_-]+"))) wrongUse("A scene name has only letters, digits, - and _")
        dir.mkdirs()
        File(dir, "$name.json").writeText(AndroidJson.write(changes(scene)))
    }

    /** Makes [scene] the current scene of the project. */
    fun use(scene: JsonObject) = project.updateMeta(mapOf("scene" to changes(scene)))

    private fun changes(scene: JsonObject): JsonObject {
        val defaults = Scenes.defaults()
        return JsonObject(scene.filter { defaults[it.key] != it.value })
    }
}
