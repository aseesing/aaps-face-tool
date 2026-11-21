package app.aaps.facetool.editor.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Test 12.4 of the design, the diff part: known pairs of designs give a golden text. */
class DiffTest {

    private val test = TestProjects()

    private fun change(dir: File) {
        test.ok(dir, "set", "animation.durationMs", "9000")
        test.ok(dir, "set", "metadata.author", "\"me\"")
        test.ok(dir, "unset", "sgv.fontColor")
        test.ok(dir, "layer", "add", """{"use": "rain"}""", "--at", "1")
        test.ok(dir, "layer", "move", "4", "0")
        test.ok(dir, "set", "animation.layers[3].count", "120")
        File(test.root, "Star.png").writeBytes(test.png(8, 6))
        test.ok(dir, "res", "replace", "Star", File(test.root, "Star.png").path)
        test.ok(dir, "res", "add", File(test.root, "Star.png").path, "--name", "Moon")
        test.ok(dir, "res", "remove", "LICENCE.txt")
    }

    private fun golden(dir: File): String = """
        ~ metadata.created_at: "21/11/26" -> "22/11/26"
        + metadata.author: "me"
        - sgv.fontColor: "#4FD98A"
        ~ animation.durationMs: 6000 -> 9000
        ~ animation.layers[1].count (now animation.layers[3].count): 12 -> 120
        > animation.layers[3] -> animation.layers[0]: {"view":"sgv"}
        + animation.layers[2]: {"use":"rain"}
        res - LICENCE.txt: 11 B
        res + Moon.png: ${File(dir, "Moon.png").length()} B, 8x6
        res ~ Star.png: ${test.zip("x.zip").let { DesignZip.read(it.readBytes())[1].bytes.size }} B, 4x3 -> ${File(dir, "Star.png").length()} B, 8x6
    """.trimIndent()

    @Test
    fun `diff of the design at open and the changed folder`() {
        val zip = test.zip()
        val dir = test.open(zip)
        change(dir)
        test.ok(dir, "set", "metadata.created_at", "\"22/11/26\"")
        assertThat(test.ok(dir, "diff", "history:0", dir.path).text("text")).isEqualTo(golden(dir))
        // The zip is the same source as history:0, and a folder the same as the last entry
        assertThat(test.ok(dir, "diff", zip.path, dir.path).text("text")).isEqualTo(golden(dir))
        assertThat(test.ok(dir, "diff", zip.path, "history:10").text("text")).isEqualTo(golden(dir))
        assertThat(test.ok(null, "diff", zip.path, dir.path).text("text")).isEqualTo(golden(dir))
        assertThat(test.ok(dir, "diff", dir.path, dir.path).text("text")).isEqualTo("no differences")
        assertThat(test.ok(dir, "diff", "history:9", "history:10").text("text")).isEqualTo("~ metadata.created_at: \"21/11/26\" -> \"22/11/26\"")
        assertThat(test.run(dir, "diff", "history:11", dir.path).exitCode).isEqualTo(ExitCodes.WRONG_USE)
        assertThat(test.run(null, "diff", "history:0", dir.path).exitCode).isEqualTo(ExitCodes.WRONG_USE)
        assertThat(test.run(dir, "diff", File(test.root, "nothing").path, dir.path).exitCode).isEqualTo(ExitCodes.WRONG_USE)
    }

    @Test
    fun `the JSON answer of diff names each change`() {
        val dir = test.open()
        test.ok(dir, "layer", "move", "0", "3")
        test.ok(dir, "res", "remove", "LICENCE.txt")
        val answer = test.ok(dir, "diff", "history:0", dir.path)
        assertThat(answer["json"]!!.compact()).isEqualTo("""[{"op":"move","path":"animation.layers[0]","pathAfter":"animation.layers[3]","before":{"image":"Star","x":0.10}}]""")
        assertThat(answer["resources"]!!.compact()).isEqualTo("""[{"op":"remove","name":"LICENCE.txt","before":{"bytes":11,"hash":"${Hashes.sha256("Free to use".toByteArray())}"}}]""")
    }

    @Test
    fun `lists of values and a removed layer`() {
        fun diff(a: String, b: String): String = DesignDiff.text(DesignDiff.json(AndroidJson.parse(a), AndroidJson.parse(b)), emptyList())
        assertThat(diff("""{"p": [1, 2, 3, 4]}""", """{"p": [1, 3, 4, 5]}""")).isEqualTo("- p[1]: 2\n+ p[3]: 5")
        assertThat(diff("""{"p": [1, 2, 3]}""", """{"p": [1, 9, 3]}""")).isEqualTo("~ p[1]: 2 -> 9")
        assertThat(diff("""{"l": [{"a": 1}, {"b": 2}, {"c": 3}]}""", """{"l": [{"a": 1}, {"c": 4}]}""")).isEqualTo("- l[1]: {\"b\":2}\n~ l[2].c (now l[1].c): 3 -> 4")
        assertThat(diff("""{"a": {"b": 1}}""", """{"a": [1]}""")).isEqualTo("~ a: {\"b\":1} -> [1]")
        assertThat(diff("""{"a": 1}""", """{"a": 1.0}""")).isEqualTo("~ a: 1 -> 1.0")
    }

    @Test
    fun `diff against a git commit of the project folder`() {
        val dir = test.open()
        fun git(vararg args: String): Boolean = runCatching {
            ProcessBuilder(listOf("git", "-C", dir.path, "-c", "user.name=test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false") + args)
                .redirectErrorStream(true).redirectOutput(File(test.root, "git.log")).start().let { it.waitFor(60, TimeUnit.SECONDS) && it.exitValue() == 0 }
        }.getOrDefault(false)
        assumeTrue("git does not run here", git("init", "-q"))
        File(dir, ".gitignore").writeText(".facetool/\n")
        assumeTrue(git("add", ".") && git("commit", "-q", "-m", "first"))
        File(dir, ".gitignore").delete()
        test.ok(dir, "set", "sgv.width", "151")
        test.ok(dir, "res", "remove", "LICENCE.txt")
        assertThat(test.ok(dir, "diff", "git:HEAD", dir.path).text("text")).isEqualTo("~ sgv.width: 150 -> 151\nres - .gitignore: 11 B\nres - LICENCE.txt: 11 B")
        assertThat(test.run(dir, "diff", "git:nothing", dir.path).exitCode).isEqualTo(ExitCodes.WRONG_USE)
    }
}
