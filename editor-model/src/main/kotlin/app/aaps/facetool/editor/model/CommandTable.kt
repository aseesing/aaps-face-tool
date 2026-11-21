package app.aaps.facetool.editor.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The type of an argument. In the argument object of a command (argument name without the dashes
 * to value) the types travel as:
 * [STRING] a text; [INT] and [NUMBER] a number; [FLAG] `true`; [JSON] the JSON value itself;
 * [FILE] the absolute path of a file or folder as a text (`-` is standard input);
 * [SOURCE] a text: an absolute path, `history:<n>` or `git:<rev>`.
 */
enum class ArgType { STRING, INT, NUMBER, FLAG, JSON, FILE, SOURCE }

/**
 * One argument of a command. A [positional] argument is given by its place, the others as
 * `--name value` (a [ArgType.FLAG] as `--name`). [repeat] arguments may be given more than once and
 * travel as a list. [default] is the value when the argument is not given, as text; the parser
 * fills it in. [server] says that the command needs the server when this argument is given.
 */
class ArgSpec(
    val name: String,
    val type: ArgType,
    val help: String,
    val positional: Boolean = false,
    val required: Boolean = false,
    val default: String? = null,
    val repeat: Boolean = false,
    val choices: List<String> = emptyList(),
    val server: Boolean = false
) {

    /** The argument as the help shows it: `<path>`, `[--at <n>]`, `[--json]`. */
    fun usage(): String {
        val core = when {
            positional            -> "<$name>" + if (repeat) "..." else ""
            type == ArgType.FLAG  -> "--$name"
            choices.isNotEmpty()  -> "--$name ${choices.joinToString("|")}"
            else                  -> "--$name <${type.name.lowercase()}>"
        }
        return if (required) core else "[$core]"
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("type", type.name.lowercase())
        put("help", help)
        put("positional", positional)
        put("required", required)
        default?.let { put("default", it) }
        if (repeat) put("repeat", true)
        if (choices.isNotEmpty()) put("choices", JsonArray(choices.map { JsonPrimitive(it) }))
        if (server) put("server", true)
    }
}

/**
 * One command (design section 5.2). A command with a sub word has a [name] with a space:
 * `layer add`, `wff plan`.
 *
 * [server] is true when the render server runs the command; the others run with `LocalCommands`.
 * [cli] is true for the commands about the server itself (`serve`, `stop`, `status`, `gui`): the
 * CLI handles them, and never starts a server for them but for `serve` and `gui`.
 * [serverWhenRunning] is true for a local command that the CLI sends to the server when one runs
 * already, because the server can do more (`save` with the engine check).
 * [project] is false for a command that works without a project folder.
 * [answer] is the JSON shape of the answer, as text.
 */
class CommandSpec(
    val name: String,
    val group: String,
    val help: String,
    val args: List<ArgSpec>,
    val server: Boolean,
    val examples: List<String>,
    val answer: String,
    val cli: Boolean = false,
    val serverWhenRunning: Boolean = false,
    val project: Boolean = true
) {

    /** True when the command with these arguments needs the server. */
    fun needsServer(args: JsonObject): Boolean = server || this.args.any { it.server && it.name in args }

    /** The command as the help shows it: `facetool layer add <json> [--at <int>]`. */
    fun usage(): String = (listOf("facetool", name) + args.sortedBy { !it.positional }.map { it.usage() }).joinToString(" ")

    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("group", group)
        put("help", help)
        put("usage", usage())
        put("args", JsonArray(args.map { it.toJson() }))
        put("server", server)
        if (cli) put("cli", true)
        if (serverWhenRunning) put("serverWhenRunning", true)
        put("project", project)
        put("examples", JsonArray(examples.map { JsonPrimitive(it) }))
        put("answer", answer)
    }
}

/**
 * The one table of commands (design section 5.2). The CLI parser, the help, the HTTP API and the
 * GUI all read this table. [globals] are the arguments every command takes.
 */
class CommandTable(val commands: List<CommandSpec>, val globals: List<ArgSpec>) {

    private val byName = commands.associateBy { it.name }

    init {
        require(byName.size == commands.size) { "Two commands have the same name" }
    }

    fun find(name: String): CommandSpec? = byName[name]

    fun toJson(): JsonObject = buildJsonObject {
        put("globals", JsonArray(globals.map { it.toJson() }))
        put("commands", JsonArray(commands.map { it.toJson() }))
    }

    companion object {

        /** The table of `facetool`: every command of design section 8.2. */
        fun standard(): CommandTable = CommandTable(StandardCommands.all(), StandardCommands.globals())
    }
}
