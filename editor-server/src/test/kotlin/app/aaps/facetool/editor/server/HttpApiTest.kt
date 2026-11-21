package app.aaps.facetool.editor.server

import app.aaps.facetool.editor.model.AndroidJson
import app.aaps.facetool.editor.model.CommandTable
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The HTTP API (design section 9) and the one command table (12.9): the token, the address, the file
 * rule, and that the GUI page sends only commands of the table to routes the server has.
 */
class HttpApiTest : ServerTestBase() {

    private val http = HttpClient.newHttpClient()

    private fun get(port: Int, path: String, token: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET()
        token?.let { request.header("X-Facetool-Token", it) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(port: Int, body: String, token: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/run")).POST(HttpRequest.BodyPublishers.ofString(body))
        token?.let { request.header("X-Facetool-Token", it) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun theApiNeedsTheTokenAndServesOnlyOutFiles() {
        val project = open("D2")
        val port = server.start()
        try {
            val token = server.apiToken
            // server.json names the port and the token, as the CLI reads it
            val info = AndroidJson.parseObject(File(temp, "cache").walkTopDown().first { it.name == "server.json" }.readText(), "server.json")
            assertThat((info.getValue("port") as JsonPrimitive).content.toInt()).isEqualTo(port)
            assertThat((info.getValue("token") as JsonPrimitive).content).isEqualTo(token)

            assertThat(get(port, "/api/ping", null).statusCode()).isEqualTo(403)
            assertThat(get(port, "/api/ping", "wrong").statusCode()).isEqualTo(403)
            assertThat(post(port, """{"command": "info"}""", null).statusCode()).isEqualTo(403)
            assertThat(get(port, "/api/ping", token).body()).contains("\"ok\":true")

            // The command table over HTTP is the table of the CLI
            assertThat(AndroidJson.parseObject(get(port, "/api/commands", token).body(), "commands")).isEqualTo(CommandTable.standard().toJson())

            // A local command through the API, with the same answer as in the CLI
            val answer = AndroidJson.parseObject(post(port, """{"command": "list", "args": {"kind": "layers"}, "project": "${project.path}"}""", token).body(), "answer")
            assertThat((answer.getValue("items") as JsonArray).size).isGreaterThan(0)
            val unknown = AndroidJson.parseObject(post(port, """{"command": "nothing"}""", token).body(), "answer")
            assertThat((unknown.getValue("exitCode") as JsonPrimitive).content).isEqualTo("2")

            // Only files under .facetool/out of a project
            val out = File(project, ".facetool/out/render/x.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            fun file(path: String) = get(port, "/api/file?path=" + URLEncoder.encode(path, "UTF-8"), token).statusCode()
            assertThat(file(out.path)).isEqualTo(200)
            assertThat(file(File(project, "CustomWatchface.json").path)).isEqualTo(404)
            assertThat(file(File(project, ".facetool/out/render/../../project.json").path)).isEqualTo(404)
            assertThat(file("/etc/hosts")).isEqualTo(404)

            // The page itself needs no token; it holds no data
            assertThat(get(port, "/", null).body()).contains("facetool")
            // The two help pages, and no other file of the checkouts
            System.getProperty("aaps.dir")?.let { assertThat(get(port, "/docs/manual.html", null).body()).contains("id=\"wff-faces\"") }
            System.getProperty("editor.checkout")?.let { assertThat(get(port, "/docs/making-a-design.html", null).body()).contains("Making a watch face design with facetool") }
            for (other in listOf("/docs/face-editor.md", "/docs/../README.md", "/docs/manual.html/../../CLAUDE.md", "/docs/", "/aaps-commit.txt")) assertWithMessage(other).that(get(port, other, null).statusCode()).isEqualTo(404)
        } finally {
            server.stop()
        }
        assertThat(File(temp, "cache").walkTopDown().none { it.name == "server.json" }).isTrue()
    }

    @Test
    fun theGuiSendsOnlyCommandsOfTheTable() {
        val page = javaClass.classLoader!!.getResourceAsStream("gui/index.html")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val table = CommandTable.standard()
        val commands = Regex("(?:run|edit)\\('([^']+)'").findAll(page).map { it.groupValues[1] }.toSet()
        assertThat(commands).containsAtLeast("render", "list", "show", "set", "lint", "scene", "save", "undo", "redo", "pick", "expand", "status", "info", "wake", "wff render", "wff compare", "wff parts", "diff", "open", "new")
        for (command in commands) assertWithMessage("the page sends '%s', which is not in the command table", command).that(table.find(command)).isNotNull()
        // Every command goes through the one function run(): no other request to /api/run
        assertThat(Regex("fetch\\('/api/run'").findAll(page).count()).isEqualTo(1)
        val routes = Regex("'/api/([a-z]+)").findAll(page).map { it.groupValues[1] }.toSet()
        assertThat(routes).containsExactly("run", "file", "events", "projects", "upload", "ping", "cancel", "drive")
    }

    @Test
    fun everyCommandThatNeedsTheEngineHasCodeOrSaysSo() {
        // A command of the table that the server has no code for yet answers with exit code 2, never with a crash
        for (spec in CommandTable.standard().commands.filter { it.server }) {
            val answer = answer(spec.name, emptyMap(), null)
            assertWithMessage("%s: %s", spec.name, answer.json).that(answer.exitCode).isAnyOf(0, 1, 2, 3)
            assertThat(answer.json).containsKey("text")
        }
    }
}
