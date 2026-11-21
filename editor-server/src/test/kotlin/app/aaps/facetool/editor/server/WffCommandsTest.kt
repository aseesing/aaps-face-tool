package app.aaps.facetool.editor.server

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The WFF commands on one design (steps 9 and 10): `wff build` with its cache, `wff plan`, `wff xml`,
 * and the player commands `wff render`, `wff wake`, `wff parts` and `wff compare`. The pictures of
 * the player are checked by hash against a golden file.
 */
abstract class WffCommandsBase(private val design: String) : ServerTestBase() {

    @Test
    fun buildPlanAndPlayer() {
        val project = open(design)
        val lines = StringBuilder()
        fun hash(path: String) = sha256(File(path).readBytes())

        val built = run("wff build", emptyMap(), project)
        assertThat(text(built, "built")).isEqualTo("true")
        assertThat(text(built, "fromCache")).isEqualTo("false")
        assertThat(File(text(built, "xml")).isFile).isTrue()
        assertThat(File(text(built, "dataZip")).isFile).isTrue()
        // The second time, and for the other version, the face comes from the cache
        assertThat(text(run("wff build", mapOf("version" to JsonPrimitive("2")), project), "fromCache")).isEqualTo("true")

        val plan = run("wff plan", emptyMap(), project)
        assertThat(text(plan, "text")).contains("Pictures:")
        lines.append("xml 4 ${sha256(text(run("wff xml", emptyMap(), project), "text").toByteArray())}\n")
        lines.append("xml 2 ${sha256(text(run("wff xml", mapOf("version" to JsonPrimitive("2")), project), "text").toByteArray())}\n")

        for (version in listOf("4", "2")) {
            val render = run("wff render", mapOf("version" to JsonPrimitive(version)), project)
            val problems = (render.getValue("problems") as JsonArray).map { (it as JsonPrimitive).content }
            assertWithMessage("WFF %s", version).that(problems.filter { "cannot show" in it }).isEmpty()
            lines.append("render $version ${hash(text(render, "file"))}\n")
            problems.forEach { lines.append("  $it\n") }
        }
        // Always-on is another picture: the parts with a Variant for it are gone
        val ambient = run("wff render", mapOf("set" to JsonArray(listOf(JsonPrimitive("ambient=true")))), project)
        lines.append("render 4 ambient ${hash(text(ambient, "file"))}\n")
        // The player's keys do not make a new build
        assertThat(text(run("wff build", emptyMap(), project), "fromCache")).isEqualTo("true")

        val wake = run("wff wake", mapOf("seconds" to JsonPrimitive(1), "fps" to JsonPrimitive(4)), project)
        assertThat((wake.getValue("frames") as JsonArray)).hasSize(5)
        lines.append("wake sheet ${hash(text(wake, "file"))}\n")

        val parts = run("wff parts", emptyMap(), project)
        assertThat((parts.getValue("statics") as JsonArray).size).isGreaterThan(0)

        val compare = run("wff compare", emptyMap(), project)
        val shares = compare.getValue("changedPercent") as JsonObject
        lines.append("compare ${hash(text(compare, "file"))}\n")
        for (version in listOf("wff4", "wff2")) {
            val share = (shares.getValue(version) as JsonPrimitive).content.toDouble()
            lines.append("compare $version %.2f %% of the pixels differ from the engine\n".format(Locale.ROOT, share))
            // The WFF face must look like the engine's face: a small share may differ (live text is placed by WFF)
            assertWithMessage("%s differs from the engine, see %s", version, text(compare, "file")).that(share).isLessThan(MAX_DIFFERENT_PERCENT)
        }
        GoldenFile("wff/$design.txt").check(lines.toString())

        // Step 13: the face APK, when Pack is built on this computer
        if (File(System.getProperty("user.home"), ".cache/aaps-wff/pack/target/release/pack-cli").isFile) {
            val apk = run("wff apk", emptyMap(), project)
            assertThat(text(apk, "valid")).isEqualTo("true")
            assertThat(File(text(apk, "apk")).length()).isGreaterThan(10_000L)
        }
    }

    private companion object {

        /**
         * How much of the player's face may differ from the engine's face. Not 0: live texts are drawn
         * by the player in the place WFF gives them, and the wearer's slots show samples.
         */
        const val MAX_DIFFERENT_PERCENT = 5.0
    }
}

class WffCommandsD2Test : WffCommandsBase("D2")
class WffCommandsCyberpunkTest : WffCommandsBase("Cyberpunk_Tech")
