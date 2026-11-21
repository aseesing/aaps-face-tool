package app.aaps.facetool.editor.server

import android.graphics.Bitmap
import app.aaps.facetool.editor.model.Scenes
import app.aaps.facetool.snapshot.SnapshotEngine
import app.aaps.wear.R
import app.aaps.wear.watchfaces.options.DesignOption
import app.aaps.wear.watchfaces.options.DesignOptions
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Design 12.3, the engine preview identity: `render` through the server gives the same PNG bytes as
 * [SnapshotEngine] with a new face and the same scene. For each of the 12 designs: the rest picture,
 * 3 moments of the wake, each choice of each option, other colours, other values, another time and
 * always-on. This guards the server's own setup (the kept face, the settings, the values, the clock);
 * the engine is the same.
 */
abstract class RenderIdentityBase(private val designs: List<String>) : ServerTestBase() {

    private var faces = 0

    @Test
    fun serverPictureIsTheEnginePicture() {
        val engine = SnapshotEngine(app, logger)
        var compared = 0
        for (design in designs) {
            val project = open(design)
            val loaded = engine.loadDesign(zip(design))
            val json = JSONObject(File(project, "CustomWatchface.json").readText())
            val options = DesignOptions.read(json).filterNotNull()
            val scenes = ArrayList<List<String>>()
            scenes += listOf<String>()
            for (wake in listOf("1.0", "2.5", "4.0")) scenes += listOf("wake=$wake")
            for (option in options) for (choice in option.choices.indices) if (choice != option.default) scenes += listOf("options.${option.name}=$choice")
            if (loaded.colourSlots > 0) scenes += listOf("colours.colour1=#B6FF1A", "colours.colour2=#FF9A1A").take(loaded.colourSlots)
            // Other values and another time, after the pictures above, and then the first scene again: the kept
            // face must show them as a new face does (a face that is fed twice keeps old values in its text layers)
            scenes += listOf("sgv=250", "delta=6.0", "direction=↑")
            scenes += listOf("time=2026-12-25T18:30:00")
            // Always-on, and then the first scene again: the kept face must leave always-on as a new face is
            scenes += listOf("ambient=true")
            scenes += listOf<String>()
            for (sets in scenes) {
                val args = mapOf<String, JsonElement>("set" to JsonArray(sets.map { JsonPrimitive(it) }))
                val file = File(text(run("render", args, project), "file"))
                val values = SceneReader().read(Scenes.merge(null, null, sets))
                val expected = png(reference(engine, loaded, values, options))
                assertWithMessage("%s with %s: the server's picture %s is not the picture of a new face", design, sets, file).that(file.readBytes().contentEquals(expected)).isTrue()
                compared++
            }
        }
        println("compared $compared pictures of ${designs.size} designs")
    }

    /** The picture of a new face, made the way the harness makes its pictures. */
    private fun reference(engine: SnapshotEngine, design: SnapshotEngine.Design, values: SceneValues, options: List<DesignOption>): Bitmap {
        val start = values.timeMs - (values.wakeMs ?: REST_LEAD_MS)
        engineClock.moveTo(start)
        val face = engine.Face("reference${faces++}")
        try {
            face.ambient = values.ambient
            face.settingsAtDefaults()
            face.feed(values.data, values.timeMs)
            face.show(design)
            val settings = LinkedHashMap<String, String>()
            val keys = listOf(R.string.key_cwf_option_1, R.string.key_cwf_option_2, R.string.key_cwf_option_3, R.string.key_cwf_option_4)
            val all = DesignOptions.read(JSONObject(designJson(design)))
            values.options.forEach { (name, choice) -> settings[app.getString(keys[all.indexOfFirst { it?.name == name }])] = choice.toString() }
            values.colours["colour1"]?.let { settings[app.getString(R.string.key_cwf_colour_1)] = it }
            values.colours["colour2"]?.let { settings[app.getString(R.string.key_cwf_colour_2)] = it }
            if (settings.isNotEmpty()) face.applyWatchSettings(settings)
            face.frame(values.size, engineClock.now).recycle()
            engineClock.moveTo(values.timeMs)
            return face.frame(values.size, engineClock.now)
        } finally {
            face.release()
        }
    }

    private fun designJson(design: SnapshotEngine.Design): String = File(temp, "projects/${design.name}/CustomWatchface.json").readText()

    private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { bitmap.recycle() }

    private companion object {

        const val REST_LEAD_MS = 31_000L
    }
}

// Three test JVMs, so the 12 designs do not take one long run
class RenderIdentityAuroraTest : RenderIdentityBase(DESIGNS.filter { it.startsWith("Aurora") })
class RenderIdentityCyberpunkTest : RenderIdentityBase(DESIGNS.filter { it.startsWith("Cyberpunk") || it == "D2" || it == "Wilds_Weather" })
class RenderIdentityGrandTest : RenderIdentityBase(DESIGNS.filter { it.startsWith("Grand") })
