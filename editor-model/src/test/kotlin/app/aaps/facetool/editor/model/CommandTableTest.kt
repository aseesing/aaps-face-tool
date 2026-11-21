package app.aaps.facetool.editor.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/** The parts of test 12.9 of the design that need no server: one command table for the parser and the help. */
class CommandTableTest {

    private val test = TestProjects()
    private val table = test.table
    private val parser = ArgParser(table, File("/work"))

    @Test
    fun `the table has every command of the design`() {
        assertThat(table.commands.map { it.name }).containsExactly(
            "open", "new", "save", "info", "status", "serve", "stop", "gui",
            "show", "list", "expand", "keys", "manual",
            "set", "unset", "apply", "layer add", "layer move", "layer copy", "layer remove", "res add", "res replace", "res remove", "res rename", "undo", "redo", "history",
            "render", "wake", "options", "looks", "pick", "snapshot", "scene",
            "lint", "diff",
            "wff plan", "wff build", "wff render", "wff wake", "wff parts", "wff compare", "wff xml", "wff apk", "wff emulator",
            "help"
        )
        val server = table.commands.filter { it.server }.map { it.name }
        assertThat(server).containsExactly(
            "status", "serve", "stop", "gui", "expand", "keys", "manual", "render", "wake", "options", "looks", "pick", "snapshot", "scene", "lint",
            "wff plan", "wff build", "wff render", "wff wake", "wff parts", "wff compare", "wff xml", "wff apk", "wff emulator"
        )
        assertThat(table.find("diff")!!.needsServer(JsonObject(emptyMap()))).isFalse()
        assertThat(table.find("diff")!!.needsServer(JsonObject(mapOf("render" to JsonPrimitive(true))))).isTrue()
        assertThat(table.find("nothing")).isNull()
    }

    @Test
    fun `every command has help, an answer shape and an example, and the parser takes each example`() {
        for (spec in table.commands) {
            assertWithMessage(spec.name).that(spec.help).isNotEmpty()
            assertWithMessage(spec.name).that(spec.answer).isNotEmpty()
            assertWithMessage(spec.name).that(spec.examples).isNotEmpty()
            for (arg in spec.args) assertWithMessage("${spec.name} ${arg.name}").that(arg.help).isNotEmpty()
            for (example in spec.examples) {
                val words = CommandLines.split(example)
                assertWithMessage(example).that(words.first()).isEqualTo("facetool")
                val parsed = parser.parse(words.drop(1))
                assertWithMessage(example).that(parsed.spec.name).isEqualTo(spec.name)
            }
        }
    }

    @Test
    fun `every picture command takes a scene file, set pairs and out`() {
        for (name in listOf("render", "wake", "options", "looks", "pick", "snapshot", "wff render", "wff wake", "wff parts", "wff compare")) {
            val args = table.find(name)!!.args.associateBy { it.name }
            assertWithMessage(name).that(args.keys).containsAtLeast("scene", "set", "out")
            assertWithMessage(name).that(args.getValue("set").repeat).isTrue()
            assertWithMessage(name).that(args.getValue("scene").type).isEqualTo(ArgType.FILE)
        }
    }

    @Test
    fun `every command without the server has local code`() {
        for (spec in table.commands.filter { !it.server }) {
            val answer = test.commands.run(spec.name, JsonObject(emptyMap()), null)
            assertWithMessage(spec.name).that(answer.text).doesNotContain("has no local code")
            assertWithMessage(spec.name).that(answer.json).containsKey("text")
        }
        val server = test.commands.run("render", JsonObject(emptyMap()), null)
        assertThat(server.exitCode).isEqualTo(ExitCodes.WRONG_USE)
        assertThat(server.text).contains("needs the server")
        assertThat(server.json["exitCode"]).isEqualTo(JsonPrimitive(2))
        assertThat(test.commands.run("paint", JsonObject(emptyMap()), null).exitCode).isEqualTo(ExitCodes.WRONG_USE)
    }

    @Test
    fun `help comes from the table`() {
        val overview = test.ok(null, "help").text("text")
        for (spec in table.commands) assertWithMessage(spec.name).that(overview).contains("  ${spec.name} ")
        assertThat(test.ok(null).text("text")).isEqualTo(overview)

        val one = test.ok(null, "help", "layer", "add").text("text")
        assertThat(one).startsWith("facetool layer add <json> [--at <int>]\n  Adds a layer; default at the end (on top).")
        assertThat(one).contains("Runs: local, without the server")
        for (example in table.find("layer add")!!.examples) assertThat(one).contains(example)
        assertThat(test.ok(null, "help", "render").text("text")).contains("Runs: in the server")
        assertThat(test.run(null, "help", "paint").exitCode).isEqualTo(ExitCodes.WRONG_USE)

        // help --json: the answer is the table
        val json = test.ok(null, "help", "--json")
        assertThat(JsonObject(json - "text")).isEqualTo(table.toJson())
        val commands = json["commands"] as JsonArray
        assertThat(commands.map { (it as JsonObject).text("name") }).isEqualTo(table.commands.map { it.name })
        val render = commands.first { (it as JsonObject).text("name") == "render" } as JsonObject
        assertThat((render["args"] as JsonArray).map { (it as JsonObject).text("name") }).containsExactly("scene", "set", "out", "wake", "only", "hide", "annotate", "grid", "zoom").inOrder()
        assertThat(render["server"]).isEqualTo(JsonPrimitive(true))

        val markdown = test.ok(null, "help", "--markdown").text("text")
        for (spec in table.commands) {
            assertWithMessage(spec.name).that(markdown).contains("#### `${spec.name}`")
            for (example in spec.examples) assertWithMessage(example).that(markdown).contains(example)
        }
    }

    @Test
    fun `the parser gives the arguments as one object with types`() {
        fun args(vararg words: String): String = parser.parse(words.toList()).args.compact()
        assertThat(args("set", "a.b", "{\"x\": 1}")).isEqualTo("""{"path":"a.b","value":{"x":1}}""")
        assertThat(args("set", "a.b", "-5")).isEqualTo("""{"path":"a.b","value":-5}""")
        assertThat(args("set", "--text", "a.b", "5")).isEqualTo("""{"path":"a.b","value":"5","text":true}""")
        assertThat(args("layer", "add", "{}", "--at=3")).isEqualTo("""{"json":{},"at":3}""")
        assertThat(args("render", "--set", "sgv=180", "--set", "options.moon=1", "--wake", "2.5", "--grid", "--out", "face.png", "--scene", "/s/scene.json"))
            .isEqualTo("""{"scene":"\/s\/scene.json","set":["sgv=180","options.moon=1"],"out":"\/work\/face.png","wake":2.5,"grid":true}""")
        assertThat(args("wake")).isEqualTo("""{"fps":8,"from":0}""")
        assertThat(args("wff", "plan", "--version", "2")).isEqualTo("""{"version":"2"}""")
        assertThat(args("diff", "history:3", "sub/dir", "--render")).isEqualTo("""{"a":"history:3","b":"\/work\/sub\/dir","render":true}""")
        assertThat(args("apply", "-")).isEqualTo("""{"patch":"-"}""")
        assertThat(args("open", "../a.zip", "--force")).isEqualTo("""{"zip":"\/a.zip","force":true}""")
        assertThat(args("help", "wff", "plan")).isEqualTo("""{"command":["wff","plan"]}""")
        assertThat(args("show", "--", "--odd")).isEqualTo("""{"path":"--odd"}""")

        val parsed = parser.parse(listOf("--project", "p", "list", "layers", "--json", "--no-start"))
        assertThat(parsed.spec.name).isEqualTo("list")
        assertThat(parsed.json).isTrue()
        assertThat(parsed.noStart).isTrue()
        assertThat(parsed.project).isEqualTo(File("/work/p"))
        assertThat(parser.parse(emptyList()).spec.name).isEqualTo("help")
    }

    @Test
    fun `wrong use gives exit code 2 with a hint`() {
        fun error(vararg words: String): String {
            val e = assertThrows(FacetoolException::class.java) { parser.parse(words.toList()) }
            assertThat(e.exitCode).isEqualTo(ExitCodes.WRONG_USE)
            return e.message!!
        }
        assertThat(error("rendr")).contains("Did you mean 'render'?")
        assertThat(error("layer")).contains("'layer' needs one of: add, move, copy, remove")
        assertThat(error("render", "--wak", "2")).contains("Did you mean --wake?")
        assertThat(error("render", "--wake")).contains("--wake needs a value")
        assertThat(error("render", "--wake", "soon")).contains("needs a number")
        assertThat(error("set", "a.b")).contains("<value> is missing")
        assertThat(error("set", "a.b", "text")).contains("is not JSON")
        assertThat(error("unset", "a", "b")).contains("too many arguments")
        assertThat(error("list", "things")).contains("is one of layers, views")
        assertThat(error("wff", "plan", "--version", "3")).contains("is one of 4, 2")
        assertThat(error("layer", "remove", "x")).contains("needs a whole number")
        assertThat(error("render", "-x")).contains("two dashes")
        assertThat(error("render", "--out", "a", "--out", "b")).contains("given twice")
    }

    @Test
    fun `a command line is written back from its arguments`() {
        for (line in listOf(
            "facetool set sgv.fontColor '\"#FF0000\"'",
            "facetool set sgv.fontColor '#FF0000' --text",
            "facetool layer add '{\"use\":\"rain\"}' --at 0",
            "facetool unset 'animation.layers[3].blend'",
            "facetool render --set sgv=180 --set 'direction=a b' --wake 2.5 --grid",
            "facetool wff plan --version 2",
            "facetool res rename Face Letters --force"
        )) {
            val parsed = parser.parse(CommandLines.split(line).drop(1))
            assertThat(CommandLines.format(parsed.spec, parsed.args)).isEqualTo(line)
        }
        assertThat(CommandLines.split("""a 'b c' "d \"e\"" f\ g ''""")).containsExactly("a", "b c", "d \"e\"", "f g", "").inOrder()
    }
}
