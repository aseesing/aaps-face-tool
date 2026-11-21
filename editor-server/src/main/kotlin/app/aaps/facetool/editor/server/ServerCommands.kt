package app.aaps.facetool.editor.server

import app.aaps.facetool.editor.model.CommandAnswer
import app.aaps.facetool.editor.model.CommandTable
import app.aaps.facetool.editor.model.ExitCodes
import app.aaps.facetool.editor.model.FacetoolException
import app.aaps.facetool.editor.model.LocalCommands
import app.aaps.facetool.editor.model.ProjectFolder
import app.aaps.facetool.editor.model.wrongUse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** One command of the server: it gets the project (null when the request names none) and the arguments. */
typealias ServerCommand = (ProjectFolder?, Args) -> JsonObject

/**
 * Runs the commands of the command table in the server (design 5.2, 5.3): a command that needs the
 * engine runs on the render thread with its code in [engine]; every other command runs with the same
 * code as in the CLI ([local]).
 *
 * @param engine the commands that need the engine, by their name in the table
 * @param life `status` and `stop`, which are about the server itself
 */
class ServerCommands(
    private val table: CommandTable, private val local: LocalCommands, private val renderThread: RenderThread,
    private val engine: Map<String, ServerCommand>, private val life: Map<String, ServerCommand>
) {

    /** Called after each command with its name, its arguments, its project and its answer: for the events of the GUI. */
    var onCommand: (String, JsonObject, File?, CommandAnswer) -> Unit = { _, _, _, _ -> }

    fun run(command: String, args: JsonObject, projectDir: File?): CommandAnswer {
        val answer = answerOf(command, args, projectDir)
        onCommand(command, args, projectDir, answer)
        return answer
    }

    private fun answerOf(command: String, args: JsonObject, projectDir: File?): CommandAnswer {
        try {
            val spec = table.find(command) ?: wrongUse("Unknown command '$command'. facetool help lists the commands")
            val project = projectDir?.let { ProjectFolder(it) }
            life[command]?.let { return done(it(project, Args(args))) }
            val work = engine[command]
            return when {
                work != null && (spec.server || spec.needsServer(args)) -> {
                    if (spec.project && project == null) wrongUse("No project: run facetool inside a project folder, or give --project <dir>")
                    done(renderThread.run { work(project, Args(args)) })
                }
                !spec.needsServer(args)                                 -> local.run(command, args, projectDir)
                else                                                    -> wrongUse("The command '$command' is not built yet")
            }
        } catch (e: FacetoolException) {
            return failed(e.message ?: "error", e.exitCode)
        } catch (e: Throwable) {
            return failed("The server failed: $e\n" + e.stackTrace.take(12).joinToString("\n") { "  at $it" }, ExitCodes.SERVER_FAILED)
        }
    }

    /** An answer may name its own exit code (the lint: 1 when it has errors). */
    private fun done(json: JsonObject): CommandAnswer = CommandAnswer(json, (json["exitCode"] as? JsonPrimitive)?.content?.toIntOrNull() ?: ExitCodes.OK)

    private fun failed(message: String, exitCode: Int): CommandAnswer = CommandAnswer(buildJsonObject {
        put("error", message)
        put("exitCode", exitCode)
        put("text", message)
    }, exitCode)
}
