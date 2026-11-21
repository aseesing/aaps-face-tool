package app.aaps.facetool.editor.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * A command line after parsing: the command, its arguments as one object (argument name without
 * the dashes to value, see [ArgType]) and the global arguments.
 */
class ParsedCommand(val spec: CommandSpec, val args: JsonObject, val json: Boolean, val project: File?, val noStart: Boolean)

/**
 * Turns the words of a command line into a [ParsedCommand], with the command table.
 * Paths of files are made absolute against [cwd], so the server reads the same file as the CLI.
 */
class ArgParser(private val table: CommandTable, private val cwd: File) {

    /** Parses the words after `facetool`. No words at all is `help`. Wrong use ends with exit code 2. */
    fun parse(argv: List<String>): ParsedCommand {
        // Global arguments may stand anywhere
        val globals = LinkedHashMap<String, String>()
        val words = mutableListOf<String>()
        var i = 0
        var onlyPositional = false
        while (i < argv.size) {
            val word = argv[i]
            val global = if (onlyPositional) null else table.globals.firstOrNull { word == "--${it.name}" || word.startsWith("--${it.name}=") }
            when {
                word == "--" && !onlyPositional -> {
                    onlyPositional = true
                    words += word
                }

                global == null                  -> words += word
                global.type == ArgType.FLAG     -> globals[global.name] = "true"
                word.contains('=')              -> globals[global.name] = word.substringAfter('=')

                else                            -> {
                    globals[global.name] = argv.getOrNull(i + 1) ?: wrongUse("--${global.name} needs a value")
                    i++
                }
            }
            i++
        }

        if (words.isEmpty()) words += "help"
        val two = words.take(2).joinToString(" ")
        val spec = table.find(two)?.takeIf { words.size >= 2 } ?: table.find(words[0]) ?: wrongUse(unknownCommand(words))
        val rest = words.drop(spec.name.count { it == ' ' } + 1)

        val raw = LinkedHashMap<String, MutableList<String>>()
        val positional = spec.args.filter { it.positional }
        var place = 0
        onlyPositional = false
        i = 0
        while (i < rest.size) {
            val word = rest[i]
            when {
                word == "--" && !onlyPositional                                                        -> onlyPositional = true

                !onlyPositional && word.startsWith("--") && word.length > 2                            -> {
                    val name = word.substring(2).substringBefore('=')
                    val arg = spec.args.firstOrNull { !it.positional && it.name == name }
                        ?: wrongUse("${spec.name}: unknown argument --$name. ${nearest(name, spec.args.filter { !it.positional }.map { it.name })?.let { "Did you mean --$it? " } ?: ""}See: facetool help ${spec.name}")
                    val value = when {
                        arg.type == ArgType.FLAG -> if (word.contains('=')) wrongUse("--$name takes no value") else "true"
                        word.contains('=')       -> word.substringAfter('=')

                        else                     -> {
                            i++
                            rest.getOrNull(i) ?: wrongUse("--$name needs a value")
                        }
                    }
                    if (!arg.repeat && name in raw) wrongUse("--$name is given twice")
                    raw.getOrPut(name) { mutableListOf() } += value
                }

                !onlyPositional && word.startsWith("-") && word.length > 1 && !word[1].isDigit() && word[1] != '.' -> wrongUse("${spec.name}: unknown argument $word. Arguments start with two dashes.")

                else                                                                                   -> {
                    val arg = positional.getOrNull(place) ?: wrongUse("${spec.name}: too many arguments ('$word'). Use: ${spec.usage()}")
                    raw.getOrPut(arg.name) { mutableListOf() } += word
                    if (!arg.repeat) place++
                }
            }
            i++
        }

        val asText = raw["text"]?.firstOrNull() == "true" && spec.args.any { it.name == "text" && it.type == ArgType.FLAG }
        val args = LinkedHashMap<String, JsonElement>()
        for (arg in spec.args) {
            val values = raw[arg.name] ?: arg.default?.let { listOf(it) }
            if (values == null) {
                if (arg.required) wrongUse("${spec.name}: ${if (arg.positional) "<${arg.name}>" else "--${arg.name}"} is missing. Use: ${spec.usage()}")
                continue
            }
            val converted = values.map { convert(spec, arg, it, asText) }
            args[arg.name] = if (arg.repeat) JsonArray(converted) else converted.single()
        }
        if (globals["force"] == "true") args["force"] = JsonPrimitive(true)
        return ParsedCommand(spec, JsonObject(args), globals["json"] == "true", globals["project"]?.let { absolute(it) }, globals["no-start"] == "true")
    }

    private fun convert(spec: CommandSpec, arg: ArgSpec, value: String, asText: Boolean): JsonElement {
        val shown = if (arg.positional) "<${arg.name}>" else "--${arg.name}"
        if (arg.choices.isNotEmpty() && value !in arg.choices) wrongUse("${spec.name}: $shown is one of ${arg.choices.joinToString(", ")}, not '$value'")
        return when (arg.type) {
            ArgType.STRING -> JsonPrimitive(value)
            ArgType.FLAG   -> JsonPrimitive(true)
            ArgType.INT    -> JsonPrimitive(value.toIntOrNull() ?: wrongUse("${spec.name}: $shown needs a whole number, not '$value'"))
            ArgType.NUMBER -> value.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: wrongUse("${spec.name}: $shown needs a number, not '$value'"))
            ArgType.FILE   -> JsonPrimitive(if (value == "-") value else absolute(value).path)
            ArgType.SOURCE -> JsonPrimitive(if (value.startsWith("history:") || value.startsWith("git:")) value else absolute(value).path)

            ArgType.JSON   -> if (asText) JsonPrimitive(value) else try {
                AndroidJson.parse(value)
            } catch (e: IllegalArgumentException) {
                wrongUse("${spec.name}: $shown is not JSON: ${e.message?.lineSequence()?.firstOrNull()}. A text needs quotes inside the shell quotes ('\"text\"'), or give --text.")
            }
        }
    }

    private fun absolute(path: String): File {
        val home = System.getProperty("user.home")
        val expanded = if (path == "~") home else if (path.startsWith("~/")) home + path.substring(1) else path
        val file = File(expanded)
        return (if (file.isAbsolute) file else File(cwd, expanded)).toPath().normalize().toFile()
    }

    private fun unknownCommand(words: List<String>): String {
        val names = table.commands.map { it.name }
        val subs = names.filter { it.startsWith(words[0] + " ") }
        if (subs.isNotEmpty()) return "'${words[0]}' needs one of: ${subs.joinToString(", ") { it.substringAfter(' ') }}"
        return "Unknown command '${words[0]}'. ${nearest(words[0], names)?.let { "Did you mean '$it'? " } ?: ""}See: facetool help"
    }

    /** The name of [names] nearest to [word], when it is near enough to be a typing error. */
    private fun nearest(word: String, names: List<String>): String? =
        names.map { it to distance(word, it) }.filter { it.second <= maxOf(1, word.length / 3) }.minByOrNull { it.second }?.first

    private fun distance(a: String, b: String): Int {
        var row = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val next = IntArray(b.length + 1)
            next[0] = i
            for (j in 1..b.length) next[j] = minOf(row[j] + 1, next[j - 1] + 1, row[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            row = next
        }
        return row[b.length]
    }
}

/** Command lines as text: for the history, the GUI's command log and the examples of the help. */
object CommandLines {

    /** The command line that gives [args] for [spec]: `facetool set sgv.fontColor '"#FF0000"'`. */
    fun format(spec: CommandSpec, args: JsonObject): String {
        val words = mutableListOf("facetool")
        words += spec.name.split(' ')
        fun text(arg: ArgSpec, value: JsonElement): String = if (arg.type == ArgType.JSON && !(args["text"] == JsonPrimitive(true) && value is JsonPrimitive && value.isString)) AndroidJson.compact(value).replace("\\/", "/") else (value as JsonPrimitive).content
        for (arg in spec.args.filter { it.positional }) {
            val value = args[arg.name] ?: continue
            if (value is JsonArray && arg.repeat) value.forEach { words += quote(text(arg, it)) } else words += quote(text(arg, value))
        }
        for (arg in spec.args.filter { !it.positional }) {
            val value = args[arg.name] ?: continue
            when {
                arg.type == ArgType.FLAG                                     -> words += "--${arg.name}"
                value is JsonPrimitive && value.content == arg.default       -> Unit
                value is JsonArray && arg.repeat                             -> value.forEach { words += "--${arg.name}"; words += quote(text(arg, it)) }
                else                                                         -> { words += "--${arg.name}"; words += quote(text(arg, value)) }
            }
        }
        if (args["force"] == JsonPrimitive(true)) words += "--force"
        return words.joinToString(" ")
    }

    /** [word] with shell quotes when it needs them. Brackets need them: zsh reads `layers[3]` as a file pattern. */
    fun quote(word: String): String =
        if (word.isNotEmpty() && word.all { it.isLetterOrDigit() || it in "_-./:=,+@%" }) word else "'" + word.replace("'", "'\\''") + "'"

    /** Splits a command line into words as a shell does: single quotes, double quotes and the backslash. */
    fun split(line: String): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var inWord = false
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quote == '\''                     -> if (c == '\'') quote = null else word.append(c)

                quote == '"'                      -> when {
                    c == '"'                                              -> quote = null
                    c == '\\' && i + 1 < line.length && line[i + 1] in "\"\\$`" -> word.append(line[++i])
                    else                                                  -> word.append(c)
                }

                c == '\'' || c == '"'             -> {
                    quote = c
                    inWord = true
                }

                c == '\\' && i + 1 < line.length -> {
                    word.append(line[++i])
                    inWord = true
                }

                c.isWhitespace()                  -> if (inWord) {
                    words += word.toString()
                    word.clear()
                    inWord = false
                }

                else                              -> {
                    word.append(c)
                    inWord = true
                }
            }
            i++
        }
        if (quote != null) wrongUse("A quote is not closed in: $line")
        if (inWord) words += word.toString()
        return words
    }
}
