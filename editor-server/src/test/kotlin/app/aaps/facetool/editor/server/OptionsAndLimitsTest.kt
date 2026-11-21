package app.aaps.facetool.editor.server

import android.graphics.BitmapFactory
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.File

/**
 * A design with eight options, and the scene keys `ic`, `isf`, `max_iob` and `max_carbs` (the values
 * `v_ic`, `v_isf`, `v_max_iob` and `v_max_carbs`). The design draws one dot per fact, so the test
 * reads the picture and not a hash.
 */
class OptionsAndLimitsTest : ServerTestBase() {

    private fun sets(vararg pairs: String): Map<String, JsonElement> = mapOf("set" to JsonArray(pairs.map { JsonPrimitive(it) }))

    /** A dot of 30 units round ([x], 200) that shows while [visible] is true. */
    private fun dot(x: Int, visible: String): String =
        """{"shape": "circle", "color": "$DOT", "slot": "front", "rest": 0, "x": $x, "y": 200, "radius": 15, "visible": "$visible"}"""

    private fun project(layers: String): File {
        val project = File(temp, "projects/Probe")
        run("new", mapOf("name" to JsonPrimitive("Probe"), "size" to JsonPrimitive(400), "dir" to JsonPrimitive(project.path)), null)
        val options = (1..8).joinToString(", ") { """{"name": "o$it", "title": "Option $it", "choices": ["A", "B", "C"], "default": 0}""" }
        run("set", mapOf("path" to JsonPrimitive("options"), "value" to Json.parseToJsonElement("[$options]")), project)
        run("set", mapOf("path" to JsonPrimitive("animation.layers"), "value" to Json.parseToJsonElement("[$layers]")), project)
        return project
    }

    /** Which of the dots at [xs] the picture of the scene with [pairs] shows. */
    private fun dots(project: File, xs: List<Int>, vararg pairs: String): List<Boolean> {
        val file = text(run("render", sets("size=400", *pairs), project), "file")
        val bitmap = BitmapFactory.decodeFile(file)
        try {
            return xs.map { x -> bitmap.getPixel(x, 200) == DOT_ARGB }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun aChoiceOfEachOfTheEightOptionsIsDrawn() {
        val xs = (1..8).map { it * 44 }
        val project = project((1..8).joinToString(", ") { dot(it * 44, "v_opt_o$it == 2") })
        // The page lists all eight
        assertThat((run("list", mapOf("kind" to JsonPrimitive("options")), project).getValue("items") as JsonArray)).hasSize(8)
        // At the defaults no dot shows
        assertThat(dots(project, xs)).doesNotContain(true)
        // Each option shows its own dot, also the options 5 to 8
        for (option in 1..8) {
            assertWithMessage("option %s", option).that(dots(project, xs, "options.o$option=2")).isEqualTo((1..8).map { it == option })
        }
        assertThat(dots(project, xs, "options.o5=2", "options.o8=2", "options.o7=1")).isEqualTo((1..8).map { it == 5 || it == 8 })
        // The sheet of `options` has a picture for every choice of all eight
        assertThat((run("options", emptyMap(), project).getValue("options") as JsonArray)).hasSize(8)
        // A choice the option does not have is wrong use
        val bad = answer("render", sets("options.o8=3"), project)
        assertThat(bad.exitCode).isEqualTo(2)
        assertThat(text(bad.json, "text")).contains("choices 0 to 2")
    }

    @Test
    fun theRatiosAndLimitsOfTheSceneReachTheDesign() {
        val keys = listOf("ic", "isf", "max_iob", "max_carbs")
        // Per value: a dot when it is missing, a dot at its default, a dot at 12.5
        val xs = (0 until 12).map { 30 + it * 31 }
        val layers = keys.flatMapIndexed { i, key ->
            listOf(dot(xs[i * 3], "missing(v_$key)"), dot(xs[i * 3 + 1], "v_$key == ${DEFAULTS[i]}"), dot(xs[i * 3 + 2], "v_$key == 12.5"))
        }.joinToString(", ")
        val project = project(layers)

        fun expected(state: (String) -> Int): List<Boolean> = keys.flatMap { key -> (0..2).map { it == state(key) } }

        // The defaults of the scene: ic 10, isf 50, max_iob 7, max_carbs 70
        val scene = run("scene", emptyMap(), project).getValue("scene") as JsonObject
        assertThat(keys.map { scene.getValue(it).toString() }).containsExactly("10", "50", "7", "70").inOrder()
        assertThat(dots(project, xs)).isEqualTo(expected { 1 })
        for (key in keys) {
            // The scene value shows
            assertWithMessage(key).that(dots(project, xs, "$key=12.5")).isEqualTo(expected { if (it == key) 2 else 1 })
            // Left out (null), and 0 as the watch reads it: missing
            assertWithMessage("$key null").that(dots(project, xs, "$key=null")).isEqualTo(expected { if (it == key) 0 else 1 })
            assertWithMessage("$key 0").that(dots(project, xs, "$key=0")).isEqualTo(expected { if (it == key) 0 else 1 })
        }
        // The face without a change shows the defaults again
        assertThat(dots(project, xs)).isEqualTo(expected { 1 })
    }

    private companion object {

        const val DOT = "#FF00FF"
        const val DOT_ARGB = 0xFFFF00FF.toInt()
        val DEFAULTS = listOf(10, 50, 7, 70)
    }
}
