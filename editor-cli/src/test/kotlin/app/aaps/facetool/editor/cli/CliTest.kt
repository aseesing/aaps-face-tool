package app.aaps.facetool.editor.cli

import app.aaps.facetool.editor.model.AndroidJson
import app.aaps.facetool.editor.model.CommandLines
import app.aaps.facetool.editor.model.CommandTable
import app.aaps.facetool.editor.model.DesignZip
import app.aaps.facetool.editor.model.ExitCodes
import app.aaps.facetool.editor.model.ZipFileEntry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Test
import org.w3c.dom.Node
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Clock
import java.time.LocalDateTime
import javax.imageio.ImageIO

/**
 * The command line from its words to its output and exit code. The server is a small fake HTTP
 * server in this test, and the launcher is a fake that starts it: no real server runs.
 */
class CliTest {

    private val root: File = Files.createTempDirectory("facetool-cli").toFile()
    private val checkout = File(root, "checkout").apply { mkdirs() }
    private val files = ServerFiles(File(root, "cache"), checkout)
    private val token = "secret-token"
    private var server: HttpServer? = null
    private val requests = mutableListOf<JsonObject>()
    private var answers: (JsonObject) -> String = { """{"text": "ran ${(it["command"] as JsonPrimitive).content}"}""" }
    private var launches = 0
    private var cwd: File = root
    private var stdin = ""
    private var opened: String? = null
    private var openWorks = true

    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()

    /** Starts the fake server and writes `server.json`, as the real server does. */
    private fun startServer() {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun answer(exchange: HttpExchange, code: Int, body: String) {
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.createContext("/api/ping") { exchange ->
            if (exchange.requestHeaders.getFirst("X-Facetool-Token") != token) answer(exchange, 403, """{"error": "bad token", "text": "bad token"}""")
            else answer(exchange, 200, """{"ok": true}""")
        }
        http.createContext("/api/run") { exchange ->
            if (exchange.requestHeaders.getFirst("X-Facetool-Token") != token) answer(exchange, 403, """{"error": "bad token", "text": "bad token", "exitCode": 4}""")
            else {
                val request = AndroidJson.parseObject(exchange.requestBody.readBytes().decodeToString(), "request")
                requests += request
                val body = answers(request)
                answer(exchange, 200, body)
                if ((request["command"] as JsonPrimitive).content == "stop") Thread { http.stop(0) }.start()
            }
        }
        http.start()
        server = http
        files.write(ServerInfo(http.address.port, 4242, token, "2026-11-21T10:00:00Z", checkout.path))
    }

    private val launcher = object : ServerLauncher {
        override fun startBackground(checkout: File, log: File): ServerProcess {
            launches++
            startServer()
            return object : ServerProcess {
                override fun alive(): Boolean = true
            }
        }

        override fun runForeground(checkout: File): Int = 0
    }

    @After
    fun stop() {
        server?.stop(0)
    }

    private fun cli(vararg words: String): Int {
        out.reset()
        err.reset()
        val environment = CliEnvironment(cwd, checkout, File(root, "cache"), File(root, "editing"))
        return Cli(environment, PrintStream(out), PrintStream(err), Clock.systemUTC(), { stdin }, launcher, { opened = it; openWorks }, sleep = { Thread.sleep(minOf(it, 20)) }).run(words.toList())
    }

    private fun png(file: File, rgb: Int): File {
        val image = BufferedImage(6, 6, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until 6) for (y in 0 until 6) image.setRGB(x, y, rgb)
        ImageIO.write(image, "png", file)
        return file
    }

    private fun zip(): File {
        val time = LocalDateTime.of(2026, 11, 19, 16, 33)
        val json = """{"metadata":{"name":"Small","filename":"Small"},"animation":{"layers":[{"shape":"circle"}]}}"""
        return File(root, "Small.zip").apply { writeBytes(DesignZip.write(listOf(ZipFileEntry("CustomWatchface.json", json.toByteArray(), time)), time)) }
    }

    private fun openProject(): File {
        assertThat(cli("open", zip().path)).isEqualTo(0)
        return File(root, "editing/Small")
    }

    @Test
    fun `local commands run without a server and find the project above the working folder`() {
        val dir = openProject()
        assertThat(out.toString()).contains("Opened Small.zip in ${dir.path} (1 files)")
        cwd = File(dir, ".facetool/out").apply { mkdirs() }
        assertThat(cli("list", "layers")).isEqualTo(0)
        assertThat(out.toString()).isEqualTo("L0    shape circle\n")
        assertThat(cli("set", "animation.layers[0].radius", "20", "--json")).isEqualTo(0)
        assertThat(AndroidJson.parseObject(out.toString(), "out")["changed"]).isEqualTo(JsonPrimitive(true))
        assertThat(cli("show", "animation.layers[0]")).isEqualTo(0)
        assertThat(out.toString()).isEqualTo("{\n    \"shape\": \"circle\",\n    \"radius\": 20\n}\n")
        stdin = """[{"op": "unset", "path": "animation.layers[0].radius"}]"""
        assertThat(cli("apply", "-")).isEqualTo(0)
        cwd = dir
        assertThat(cli("diff", "history:0", ".")).isEqualTo(0)
        assertThat(out.toString()).isEqualTo("no differences\n")
        assertThat(launches).isEqualTo(0)
        assertThat(requests).isEmpty()

        // Outside a project: --project, or exit code 2
        cwd = root
        assertThat(cli("list", "layers")).isEqualTo(ExitCodes.WRONG_USE)
        assertThat(err.toString()).contains("needs a project")
        assertThat(cli("list", "layers", "--project", dir.path)).isEqualTo(0)
    }

    @Test
    fun `errors go to stderr with the exit code, as JSON with the json flag`() {
        assertThat(cli("paint")).isEqualTo(ExitCodes.WRONG_USE)
        assertThat(out.toString()).isEmpty()
        assertThat(err.toString()).contains("Unknown command 'paint'")
        assertThat(cli("paint", "--json")).isEqualTo(ExitCodes.WRONG_USE)
        val json = AndroidJson.parseObject(err.toString(), "err")
        assertThat(json["exitCode"]).isEqualTo(JsonPrimitive(2))
        assertThat((json["error"] as JsonPrimitive).content).contains("Unknown command 'paint'")

        val dir = openProject()
        File(dir, "CustomWatchface.json").writeText("{ oops")
        assertThat(cli("show", "--project", dir.path)).isEqualTo(ExitCodes.DESIGN_UNREADABLE)
        assertThat(err.toString()).contains("is not JSON")
        assertThat(cli("help")).isEqualTo(0)
        assertThat(out.toString()).contains("facetool help <command>")
        assertThat(cli()).isEqualTo(0)
    }

    @Test
    fun `a server command starts the server once and posts the command with the token`() {
        val dir = openProject()
        cwd = dir
        answers = { """{"file": "/tmp/face.png", "text": "Wrote /tmp/face.png"}""" }
        assertThat(cli("render", "--set", "sgv=180", "--wake", "2.5", "--out", "face.png")).isEqualTo(0)
        assertThat(out.toString()).isEqualTo("Wrote /tmp/face.png\n")
        assertThat(err.toString()).contains("Starting the server")
        assertThat(err.toString()).contains("The server runs after")
        assertThat(launches).isEqualTo(1)
        assertThat(AndroidJson.compact(requests.single())).isEqualTo(
            """{"command":"render","args":{"set":["sgv=180"],"out":"${File(dir, "face.png").path.replace("/", "\\/")}","wake":2.5},"project":"${dir.path.replace("/", "\\/")}"}"""
        )
        assertThat(cli("wff", "plan", "--version", "2", "--json")).isEqualTo(0)
        assertThat(launches).isEqualTo(1)
        assertThat((requests.last()["command"] as JsonPrimitive).content).isEqualTo("wff plan")
        assertThat(AndroidJson.parseObject(out.toString(), "out")["file"]).isEqualTo(JsonPrimitive("/tmp/face.png"))
        // diff goes to the server only with --render
        assertThat(cli("diff", "history:0", ".", "--render")).isEqualTo(0)
        assertThat((requests.last()["command"] as JsonPrimitive).content).isEqualTo("diff")
        assertThat(requests).hasSize(3)
    }

    @Test
    fun `the exit code of the server answer is the exit code of the CLI`() {
        cwd = openProject()
        answers = { """{"findings": [], "errors": 2, "exitCode": 1, "text": "2 errors"}""" }
        assertThat(cli("lint")).isEqualTo(ExitCodes.CHECK_ERRORS)
        assertThat(out.toString()).isEqualTo("2 errors\n")
        answers = { """{"error": "the design does not read", "exitCode": 3, "text": "the design does not read"}""" }
        assertThat(cli("render")).isEqualTo(ExitCodes.DESIGN_UNREADABLE)
        assertThat(err.toString()).isEqualTo("the design does not read\n")
        answers = { "this is no JSON" }
        assertThat(cli("render")).isEqualTo(ExitCodes.SERVER_FAILED)
    }

    @Test
    fun `no-start and a missing build give exit code 4`() {
        cwd = openProject()
        assertThat(cli("render", "--no-start")).isEqualTo(ExitCodes.SERVER_FAILED)
        assertThat(err.toString()).contains("--no-start")
        assertThat(launches).isEqualTo(0)
        // A server.json of a server that is gone counts as not running
        files.write(ServerInfo(1, 1, token, "2026-11-21T10:00:00Z", checkout.path))
        assertThat(cli("render", "--no-start")).isEqualTo(ExitCodes.SERVER_FAILED)

        val missing = Cli(CliEnvironment(cwd, checkout, File(root, "cache2"), root), PrintStream(out), PrintStream(err), Clock.systemUTC(), { "" })
        err.reset()
        assertThat(missing.run(listOf("render"))).isEqualTo(ExitCodes.SERVER_FAILED)
        assertThat(err.toString()).contains("./gradlew :editor-server:editorClasspath")
        val noHome = Cli(CliEnvironment(cwd, null, File(root, "cache2"), root), PrintStream(out), PrintStream(err), Clock.systemUTC(), { "" })
        assertThat(noHome.run(listOf("render"))).isEqualTo(ExitCodes.SERVER_FAILED)
        assertThat(noHome.run(listOf("list", "layers"))).isEqualTo(0)
    }

    @Test
    fun `the launcher builds the server command from the files of the build`() {
        val build = File(checkout, "editor-server/build/editor").apply { mkdirs() }
        File(build, "java.txt").writeText("/jdk21/bin/java\nmore\n")
        File(build, "classpath.txt").writeText("-Xmx3g\n-cp\na.jar\n")
        assertThat(ProcessServerLauncher().command(checkout)).containsExactly(
            "/jdk21/bin/java", "@editor-server/build/editor/classpath.txt", "-Deditor.serve=true", "-Deditor.checkout=${checkout.path}",
            "org.junit.runner.JUnitCore", "app.aaps.facetool.editor.server.EditorServerMain"
        ).inOrder()
        assertThat(files.serverJson.path).matches(".*/cache/[0-9a-f]{16}/server\\.json")
        assertThat(files.log.name).isEqualTo("server.log")
    }

    @Test
    fun `save goes to the server only when it runs`() {
        val dir = openProject()
        cwd = dir
        assertThat(cli("save")).isEqualTo(0)
        assertThat(out.toString()).contains("the engine check was skipped")
        assertThat(launches).isEqualTo(0)
        startServer()
        answers = { """{"written": true, "engineCheck": "done", "text": "Saved"}""" }
        assertThat(cli("save")).isEqualTo(0)
        assertThat(out.toString()).isEqualTo("Saved\n")
        assertThat((requests.single()["command"] as JsonPrimitive).content).isEqualTo("save")
    }

    @Test
    fun `status, gui and stop`() {
        assertThat(cli("status")).isEqualTo(0)
        assertThat(out.toString()).contains("The server does not run")
        assertThat(cli("stop")).isEqualTo(0)
        assertThat(out.toString()).contains("The server does not run")
        assertThat(launches).isEqualTo(0)

        assertThat(cli("gui", "--print")).isEqualTo(0)
        assertThat(launches).isEqualTo(1)
        val url = "http://127.0.0.1:${server!!.address.port}/?token=$token"
        assertThat(out.toString()).isEqualTo("$url\n")
        assertThat(opened).isNull()
        assertThat(cli("gui")).isEqualTo(0)
        assertThat(opened).isEqualTo(url)
        assertThat(out.toString()).isEqualTo("Opened $url\n")
        openWorks = false
        assertThat(cli("gui")).isEqualTo(0)
        assertThat(out.toString()).isEqualTo("$url\n")

        answers = { """{"port": 1, "aapsCommit": "380ea15181", "pinned": true, "text": "running, AAPS 380ea15181 (pinned)"}""" }
        assertThat(cli("status", "--json")).isEqualTo(0)
        val status = AndroidJson.parseObject(out.toString(), "out")
        assertThat(status["running"]).isEqualTo(JsonPrimitive(true))
        assertThat(status["aapsCommit"]).isEqualTo(JsonPrimitive("380ea15181"))

        answers = { """{"text": "stopping"}""" }
        assertThat(cli("stop")).isEqualTo(0)
        assertThat(out.toString()).isEqualTo("The server stopped.\n")
        assertThat(cli("status")).isEqualTo(0)
        assertThat(out.toString()).contains("The server does not run")
        assertThat(launches).isEqualTo(1)
    }

    @Test
    fun `wake with gif writes the frames of the server as a GIF`() {
        cwd = openProject()
        val frames = listOf(png(File(root, "f0.png"), 0xFFFF0000.toInt()), png(File(root, "f1.png"), 0xFF00FF00.toInt()), png(File(root, "f2.png"), 0x00000000))
        answers = { """{"frames": [${frames.joinToString(", ") { "\"${it.path}\"" }}], "frameMs": 125, "text": "3 frames"}""" }
        val gif = File(root, "sub/wake.gif")
        assertThat(cli("wake", "--fps", "8", "--gif", gif.path)).isEqualTo(0)
        assertThat(out.toString()).isEqualTo("3 frames\nGIF: ${gif.path} (3 frames)\n")
        val reader = ImageIO.getImageReadersBySuffix("gif").next()
        ImageIO.createImageInputStream(gif).use { input ->
            reader.input = input
            assertThat(reader.getNumImages(true)).isEqualTo(3)
            assertThat(reader.read(0).getRGB(2, 2)).isEqualTo(0xFFFF0000.toInt())
            assertThat(reader.read(1).getRGB(2, 2)).isEqualTo(0xFF00FF00.toInt())
            assertThat(reader.read(2).getRGB(2, 2)).isEqualTo(0xFF000000.toInt())
            val delay = (reader.getImageMetadata(1).getAsTree("javax_imageio_gif_image_1.0") as Node).childNodes.let { nodes ->
                (0 until nodes.length).map { nodes.item(it) }.first { it.nodeName == "GraphicControlExtension" }.attributes.getNamedItem("delayTime").nodeValue
            }
            assertThat(delay).isEqualTo("12")
        }
        assertThat((requests.single()["args"] as JsonObject)["gif"]).isEqualTo(JsonPrimitive(gif.path))
    }

    @Test
    fun `every example of the table parses in the CLI, and a server example reaches the server`() {
        val dir = openProject()
        cwd = dir
        startServer()
        val table = CommandTable.standard()
        for (spec in table.commands.filter { it.server && !it.cli }) {
            for (example in spec.examples) {
                requests.clear()
                val code = cli(*CommandLines.split(example).drop(1).filter { !it.endsWith(".gif") && it != "--gif" }.toTypedArray())
                assertWithMessage(example).that(code).isEqualTo(0)
                assertWithMessage(example).that((requests.single()["command"] as JsonPrimitive).content).isEqualTo(spec.name)
            }
        }
        // The help of every command prints
        for (spec in table.commands) {
            assertWithMessage(spec.name).that(cli("help", *spec.name.split(" ").toTypedArray())).isEqualTo(0)
            assertWithMessage(spec.name).that(out.toString()).startsWith("facetool ${spec.name}")
        }
        assertThat(cli("help", "--json")).isEqualTo(0)
        assertThat(JsonObject(AndroidJson.parseObject(out.toString(), "out") - "text")).isEqualTo(table.toJson())
        assertThat(cli("help", "--markdown")).isEqualTo(0)
        assertThat(out.toString()).startsWith("## Command reference")
    }
}
