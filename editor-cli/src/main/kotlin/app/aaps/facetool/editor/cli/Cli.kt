package app.aaps.facetool.editor.cli

import app.aaps.facetool.editor.model.ArgParser
import app.aaps.facetool.editor.model.CommandAnswer
import app.aaps.facetool.editor.model.CommandTable
import app.aaps.facetool.editor.model.EditorConfig
import app.aaps.facetool.editor.model.ExitCodes
import app.aaps.facetool.editor.model.FacetoolException
import app.aaps.facetool.editor.model.LocalCommands
import app.aaps.facetool.editor.model.ParsedCommand
import app.aaps.facetool.editor.model.ProjectFolder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.PrintStream
import java.time.Clock

/**
 * Where the CLI runs. [cwd] is the working folder. [checkout] is the `aaps-face-tool` folder (the
 * environment variable `FACETOOL_HOME`), or null when it is not known: then only the local
 * commands work. [cacheRoot] holds the `server.json` of each checkout. [editingRoot] is where
 * `open` makes project folders.
 */
class CliEnvironment(val cwd: File, val checkout: File?, val cacheRoot: File, val editingRoot: File)

/**
 * The command line `facetool` (design section 8): parses with the command table, runs the local
 * commands with [LocalCommands] and sends the others to the render server.
 * Everything from outside comes in through the constructor, so the tests run it without a real server.
 */
class Cli(
    private val environment: CliEnvironment,
    private val out: PrintStream,
    private val err: PrintStream,
    private val clock: Clock,
    private val stdin: () -> String,
    private val launcher: ServerLauncher = ProcessServerLauncher(),
    private val openBrowser: (String) -> Boolean = { false },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) }
) {

    private val table = CommandTable.standard()

    /** Runs one command line (the words after `facetool`) and gives the exit code. */
    fun run(argv: List<String>): Int {
        val json = "--json" in argv
        return try {
            val parsed = ArgParser(table, environment.cwd).parse(argv)
            val answer = answer(parsed)
            print(answer, parsed.json)
            answer.exitCode
        } catch (e: FacetoolException) {
            print(CommandAnswer(buildJsonObject {
                put("error", e.message)
                put("exitCode", e.exitCode)
                put("text", e.message)
            }, e.exitCode), json)
            e.exitCode
        }
    }

    private fun answer(parsed: ParsedCommand): CommandAnswer {
        val spec = parsed.spec
        val project = parsed.project ?: ProjectFolder.find(environment.cwd)?.dir
        return when {
            spec.cli                                    -> serverCommand(parsed)

            spec.needsServer(parsed.args)               -> {
                if (spec.project && project == null) throw FacetoolException("'${spec.name}' needs a project: run it in a project folder, or give --project <dir>. Make one with: facetool open <zip>", ExitCodes.WRONG_USE)
                withGif(parsed, manager().ensure(parsed.noStart).run(spec.name, parsed.args, project))
            }

            spec.serverWhenRunning && project != null -> (environment.checkout?.let { manager().running() })?.run(spec.name, parsed.args, project) ?: local(parsed, project)
            else                                        -> local(parsed, project)
        }
    }

    private fun local(parsed: ParsedCommand, project: File?): CommandAnswer =
        LocalCommands(EditorConfig(environment.editingRoot), clock, table, stdin = stdin).run(parsed.spec.name, parsed.args, project)

    private fun manager(): ServerManager {
        val checkout = environment.checkout
            ?: throw FacetoolException("The folder of aaps-face-tool is not known: set FACETOOL_HOME, or run tools/facetool of that folder.", ExitCodes.SERVER_FAILED)
        return ServerManager(ServerFiles(environment.cacheRoot, checkout), launcher, clock, { err.println(it) }, sleep = sleep)
    }

    /** The commands about the server itself: `serve`, `stop`, `status`, `gui`. */
    private fun serverCommand(parsed: ParsedCommand): CommandAnswer {
        val manager = manager()
        return when (parsed.spec.name) {
            "serve"  -> manager.running()?.let { text(ExitCodes.OK, "The server runs already: ${it.baseUrl}") } ?: manager.serve().let { code ->
                text(if (code == 0) ExitCodes.OK else ExitCodes.SERVER_FAILED, "The server ended with code $code.")
            }

            "stop"   -> {
                val client = manager.running() ?: return CommandAnswer(buildJsonObject { put("stopped", false); put("text", "The server does not run.") }, ExitCodes.OK)
                val answer = client.run("stop", parsed.args, null)
                val stopped = manager.waitUntilStopped(client)
                CommandAnswer(JsonObject(answer.json + mapOf("stopped" to JsonPrimitive(stopped), "text" to JsonPrimitive(if (stopped) "The server stopped." else "The server still answers."))), if (stopped) ExitCodes.OK else ExitCodes.SERVER_FAILED)
            }

            "status" -> {
                val client = manager.running() ?: return CommandAnswer(buildJsonObject {
                    put("running", false)
                    put("checkout", manager.checkout.path)
                    put("text", "The server does not run (checkout ${manager.checkout.path}).")
                }, ExitCodes.OK)
                val answer = client.run("status", parsed.args, null)
                CommandAnswer(JsonObject(mapOf("running" to JsonPrimitive(true)) + answer.json), answer.exitCode)
            }

            "gui"    -> {
                val url = manager.ensure(parsed.noStart).guiUrl
                val opened = parsed.args["print"] == null && openBrowser(url)
                CommandAnswer(buildJsonObject {
                    put("url", url)
                    put("opened", opened)
                    put("text", if (opened) "Opened $url" else url)
                }, ExitCodes.OK)
            }

            else     -> throw FacetoolException("'${parsed.spec.name}' has no code in the CLI", ExitCodes.WRONG_USE)
        }
    }

    /** `--gif` of `wake` and `wff wake`: the server gives the frames, the CLI writes the GIF. */
    private fun withGif(parsed: ParsedCommand, answer: CommandAnswer): CommandAnswer {
        val gif = (parsed.args["gif"] as? JsonPrimitive)?.content ?: return answer
        if (answer.exitCode != ExitCodes.OK) return answer
        val frames = (answer.json["frames"] as? JsonArray)?.map { File((it as JsonPrimitive).content) }
            ?: throw FacetoolException("The server gave no frames for the GIF", ExitCodes.SERVER_FAILED)
        val frameMs = (answer.json["frameMs"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 100
        GifWriter.write(frames, frameMs, File(gif))
        val text = (answer.json["text"] as? JsonPrimitive)?.content.orEmpty()
        return CommandAnswer(JsonObject(answer.json + mapOf("gif" to JsonPrimitive(gif), "text" to JsonPrimitive(listOf(text, "GIF: $gif (${frames.size} frames)").filter { it.isNotEmpty() }.joinToString("\n")))), answer.exitCode)
    }

    private fun text(exitCode: Int, text: String): CommandAnswer = CommandAnswer(buildJsonObject {
        put("text", text)
        if (exitCode != ExitCodes.OK) put("exitCode", exitCode)
    }, exitCode)

    /** Prints the answer: the text, or the JSON object with `--json`. An error goes to stderr. */
    private fun print(answer: CommandAnswer, json: Boolean) {
        val stream = if ("error" in answer.json) err else out
        stream.println(if (json) answer.json.toString() else (answer.json["text"] as? JsonPrimitive)?.content.orEmpty())
    }
}
