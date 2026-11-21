package app.aaps.facetool.editor.server

import app.aaps.facetool.editor.model.AndroidJson
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.File

/**
 * Design 12.8: the lint of each of the 12 designs as a golden file, so a change of the engine, the
 * converter or the editor that changes the lint shows as a diff.
 */
abstract class LintGoldenBase(private val designs: List<String>) : ServerTestBase() {

    @Test
    fun lintOfTheDesigns() {
        for (design in designs) {
            val project = open(design)
            val answer = answer("lint", emptyMap(), project)
            assertWithMessage("%s: %s", design, answer.json).that(answer.exitCode).isAnyOf(0, 1)
            // Design 12.6: the player knows every element and attribute of the faces of the 12 designs, WFF 4 and 2
            val findings = (answer.json.getValue("findings") as JsonArray).map { it as JsonObject }
            assertWithMessage("%s: the player cannot show a part of its WFF face", design).that(findings.filter { text(it, "source") == "player" && "cannot show" in text(it, "text") }).isEmpty()
            GoldenFile("lint/$design.json").check(AndroidJson.write(answer.json.getValue("findings")) + "\n")
        }
    }
}

class LintGoldenAuroraTest : LintGoldenBase(DESIGNS.filter { it.startsWith("Aurora") })
class LintGoldenCyberpunkTest : LintGoldenBase(DESIGNS.filter { it.startsWith("Cyberpunk") || it == "D2" || it == "Wilds_Weather" })
class LintGoldenGrandTest : LintGoldenBase(DESIGNS.filter { it.startsWith("Grand") })

/** The lint's own checks, `save` with the engine check, and `expand`, `keys` and `manual`. */
class LintChecksTest : ServerTestBase() {

    private fun findings(json: JsonObject): List<String> = (json.getValue("findings") as JsonArray).map { f -> (f as JsonObject).let { "${text(it, "severity")} ${text(it, "path")}: ${text(it, "text")}" } }

    @Test
    fun wffKeysChangeNothingInTheEngine() {
        // Design 12.10: a design with wff keys draws the same engine frames as without them
        val project = open("D2")
        // On plain layers: a layer with use takes only the parameters of its template
        run("layer add", mapOf("json" to AndroidJson.parseObject("""{"shape": "circle", "x": "200", "y": "200", "radius": 30, "rest": 1, "wff": "still"}""", "the layer"), "at" to JsonPrimitive(0)), project)
        run("layer add", mapOf("json" to AndroidJson.parseObject("""{"shape": "circle", "x": "100", "y": "200", "radius": 30, "rest": 1, "wff": {"transform": {"angle": "[SECOND] * 6"}}}""", "the layer"), "at" to JsonPrimitive(1)), project)
        val lint = findings(answer("lint", mapOf("no-wff" to JsonPrimitive(true)), project).json)
        assertThat(lint.filter { "wff" in it }).isEmpty()
        // A wrong form of the key is an error
        run("set", mapOf("path" to JsonPrimitive("animation.layers[0].wff"), "value" to JsonPrimitive(5)), project)
        val wrong = answer("lint", mapOf("no-wff" to JsonPrimitive(true)), project)
        assertThat(wrong.exitCode).isEqualTo(1)
        assertThat(findings(wrong.json).filter { "wff must be" in it }).hasSize(1)
    }

    @Test
    fun aBrokenFormulaIsAnEngineErrorAndStopsSave() {
        val project = open("D2")
        run("layer add", mapOf("json" to AndroidJson.parseObject("""{"shape": "circle", "x": "200 +* 3", "y": "nothing(3)"}""", "the layer")), project)
        val lint = answer("lint", mapOf("no-wff" to JsonPrimitive(true)), project)
        assertThat(lint.exitCode).isEqualTo(1)
        assertThat(findings(lint.json).filter { it.startsWith("error animation") }).isNotEmpty()
        val save = answer("save", mapOf("out" to JsonPrimitive(File(temp, "broken.zip").path)), project)
        assertThat(save.exitCode).isEqualTo(1)
        assertThat(File(temp, "broken.zip").exists()).isFalse()
    }

    @Test
    fun anUnknownKeyGetsTheNearestKnownKey() {
        val project = open("D2")
        run("layer add", mapOf("json" to AndroidJson.parseObject("""{"shape": "circle", "x": "200", "y": "200", "rotaton": "5"}""", "the layer")), project)
        val lint = findings(answer("lint", mapOf("no-wff" to JsonPrimitive(true)), project).json)
        assertThat(lint.filter { "rotaton" in it }.single()).contains("did you mean 'rotation'")
    }

    @Test
    fun aNewDesignReadsInTheEngine() {
        // The loop of step 15: a new design with a clock text, a glucose text and snow draws and has no error
        val project = File(temp, "projects/Probe")
        run("new", mapOf("name" to JsonPrimitive("Probe"), "size" to JsonPrimitive(400), "dir" to JsonPrimitive(project.path)), null)
        for (layer in listOf(
            """{"text": "clock()", "x": "200", "y": "120", "fontSize": 60, "align": "center", "color": "#FFFFFF", "rest": 1}""",
            """{"text": "glucose(v_sgv)", "x": "200", "y": "220", "fontSize": 80, "align": "center", "color": "#40E060", "rest": 1}""",
            """{"shape": "circle", "count": 60, "x": "hash(i) * 400", "y": "fract(hash(i + 7) + t * 0.2) * 400", "radius": 2, "color": "#FFFFFF"}"""
        )) run("layer add", mapOf("json" to AndroidJson.parseObject(layer, "the layer")), project)
        assertThat(File(text(run("render", emptyMap(), project), "file")).length()).isGreaterThan(1000L)
        val lint = answer("lint", mapOf("no-wff" to JsonPrimitive(true)), project)
        assertThat(findings(lint.json).filter { it.startsWith("error") }).isEmpty()
        val saved = File(temp, "Probe.zip")
        run("save", mapOf("out" to JsonPrimitive(saved.path)), project)
        assertThat(saved.length()).isGreaterThan(500L)
    }

    @Test
    fun expandKeysAndManual() {
        val project = open("Aurora_Town")
        val expand = run("expand", emptyMap(), project)
        assertThat((expand.getValue("layers") as JsonArray).size).isGreaterThan(5)
        assertThat(text(expand, "text")).contains("animation.layers[0] ->")
        val keys = run("keys", mapOf("kind" to JsonPrimitive("layer")), project)
        assertThat((keys.getValue("layer") as JsonArray).map { (it as JsonPrimitive).content }).containsAtLeast("rotation", "image", "blend")
        val topics = run("manual", mapOf("list" to JsonPrimitive(true)), project)
        assertThat(text(topics, "text")).contains("templates")
        assertThat(text(run("manual", mapOf("topic" to JsonPrimitive("templates")), project), "text")).contains("params")
    }
}
