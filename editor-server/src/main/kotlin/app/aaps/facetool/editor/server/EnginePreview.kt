package app.aaps.facetool.editor.server

import android.app.Application
import android.graphics.Bitmap
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.facetool.editor.model.wrongUse
import app.aaps.facetool.snapshot.SnapshotEngine
import app.aaps.wear.R
import app.aaps.wear.watchfaces.options.DesignOptions
import app.aaps.wear.watchfaces.settingsync.CwfSettingNames
import org.json.JSONObject

/**
 * The engine preview (design section 4): draws a design for a scene with the real engine, through
 * the harness ([SnapshotEngine]). Call it only on the render thread.
 *
 * One face is kept per [draw] key (a project). Feeding the values waits for the data store of the
 * watch (2 s), so a kept face is fed only when the values or the time of the scene changed. The part
 * that draws is made new for each picture, so a picture never depends on the pictures before it.
 */
class EnginePreview(private val app: Application, private val engine: SnapshotEngine, private val clock: EngineClock) {

    private class Kept(val face: SnapshotEngine.Face) {

        var fedKey: String? = null
    }

    private val kept = HashMap<String, Kept>()
    private val settingNames by lazy { CwfSettingNames(app.resources) }

    /** The face of [scene]: at rest, or `scene.wakeMs` after the wake began. The caller owns the bitmap. */
    fun picture(key: String, design: CwfData, scene: SceneValues): Bitmap {
        val wake = scene.wakeMs ?: return draw(key, design, scene, scene.timeMs - REST_LEAD_MS, listOf(REST_LEAD_MS), rest = true).single()
        return draw(key, design, scene, scene.timeMs - wake, listOf(wake), rest = false).single()
    }

    /**
     * Frames of one wake that begins at the time of [scene], at each of [atMs] (rising) after its start.
     * [each] gets every frame with its index as soon as it is drawn.
     */
    fun wakeFrames(key: String, design: CwfData, scene: SceneValues, atMs: List<Long>, each: ((Int, Bitmap) -> Unit)? = null): List<Bitmap> =
        draw(key, design, scene, scene.timeMs, atMs, rest = false, each = each)

    /** As [wakeFrames], but no frame is kept: [each] gets it, then it is freed. For a wake of many frames. */
    fun eachWakeFrame(key: String, design: CwfData, scene: SceneValues, atMs: List<Long>, each: (Int, Bitmap) -> Unit) {
        draw(key, design, scene, scene.timeMs, atMs, rest = false, each = each, keep = false)
    }

    private fun draw(
        key: String, design: CwfData, scene: SceneValues, startMs: Long, atMs: List<Long>, rest: Boolean, each: ((Int, Bitmap) -> Unit)? = null, keep: Boolean = true
    ): List<Bitmap> {
        clock.moveTo(startMs)
        val entry = kept.getOrPut(key) { Kept(engine.Face("editor${kept.size}")) }
        val face = entry.face
        // A new part that draws: the wake starts again, as with a new face
        face.release()
        face.settingsAtDefaults()
        if (entry.fedKey != scene.feedKey) {
            face.feed(scene.data, scene.timeMs)
            entry.fedKey = scene.feedKey
        }
        face.show(SnapshotEngine.Design(key, design))
        val settings = settingsOf(design, scene)
        if (settings.isNotEmpty()) face.applyWatchSettings(settings)
        try {
            // The wake starts with the first frame drawn
            if (atMs.firstOrNull() != 0L) face.frame(scene.size, clock.now).recycle()
            val frames = ArrayList<Bitmap>()
            atMs.forEachIndexed { index, ms ->
                clock.moveTo(startMs + ms)
                val frame = face.frame(scene.size, clock.now)
                try {
                    each?.invoke(index, frame)
                } finally {
                    if (keep) frames += frame else frame.recycle()
                }
            }
            check(!rest || !face.isAnimating) { "The wake-up animation still plays after ${REST_LEAD_MS / 1000} s" }
            return frames
        } finally {
            face.release()
        }
    }

    /** The watch settings of [scene] for [design], by their stored names: option choices, colours and others. */
    private fun settingsOf(design: CwfData, scene: SceneValues): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (scene.options.isNotEmpty()) {
            val options = DesignOptions.read(JSONObject(design.json))
            for ((name, choice) in scene.options) {
                val place = options.indexOfFirst { it?.name == name }
                if (place < 0) wrongUse("The design has no option '$name'. Options: ${options.mapNotNull { it?.name }.joinToString(", ").ifEmpty { "none" }}")
                if (choice !in options[place]!!.choices.indices) wrongUse("The option '$name' has the choices 0 to ${options[place]!!.choices.size - 1}")
                result[app.getString(OPTION_KEYS[place])] = choice.toString()
            }
        }
        for ((slot, value) in scene.colours) {
            val name = when (slot) {
                "colour1" -> app.getString(R.string.key_cwf_colour_1)
                "colour2" -> app.getString(R.string.key_cwf_colour_2)
                else      -> wrongUse("The scene key colours has colour1 and colour2, not '$slot'")
            }
            result[name] = value
        }
        result.putAll(scene.watch)
        for ((name, value) in result) {
            if (!settingNames.isAllowed(name, value)) wrongUse("The watch setting '$name' cannot be '$value'. Settings: ${settingNames.settings.keys.joinToString(", ")}")
        }
        return result
    }

    /** The names of the watch settings the scene key `watch` can set, and the colour values. */
    fun settingNames(): List<String> = settingNames.settings.keys.toList()

    fun colourValues(): List<String> = app.resources.getStringArray(R.array.cwf_colour_values).toList()

    private companion object {

        /** Longer than the longest wake-up animation the engine allows (30 s), as the baker. */
        const val REST_LEAD_MS = 31_000L

        /** The watch settings of the options of a design, one per place in its list (as `DesignOptions`). */
        val OPTION_KEYS = listOf(R.string.key_cwf_option_1, R.string.key_cwf_option_2, R.string.key_cwf_option_3, R.string.key_cwf_option_4)
    }
}
