package app.aaps.facetool.editor.model

/** The help texts of `facetool`, all made from the command table (design section 5.2). */
class Help(private val table: CommandTable) {

    /** `facetool help`: every command with its help line, by group. */
    fun overview(): String {
        val out = StringBuilder()
        out.appendLine("facetool: make and change AAPS watch face designs.")
        out.appendLine("Use: facetool <command> [arguments]. A project is the folder with .facetool at or above the working folder, or --project <dir>.")
        val width = table.commands.maxOf { it.name.length }
        for ((group, commands) in table.commands.groupBy { it.group }) {
            out.appendLine()
            out.appendLine("$group:")
            for (command in commands) out.appendLine("  ${command.name.padEnd(width)}  ${firstSentence(command.help)}${if (command.server) " (server)" else ""}")
        }
        out.appendLine()
        out.appendLine("Every command takes:")
        for (arg in table.globals) out.appendLine("  ${arg.usage().removeSurrounding("[", "]").padEnd(width)}  ${arg.help}")
        out.appendLine()
        out.appendLine("Exit codes: 0 done; 1 done, but a check has errors; 2 wrong use; 3 the design does not read; 4 the server failed or did not start.")
        out.append("More: facetool help <command>, facetool help --json (the whole command table).")
        return out.toString()
    }

    /** `facetool help <command>`: the use, the arguments, where it runs, the answer and examples. */
    fun command(spec: CommandSpec): String {
        val out = StringBuilder()
        out.appendLine(spec.usage())
        out.appendLine("  ${spec.help}")
        if (spec.args.isNotEmpty()) {
            out.appendLine()
            out.appendLine("Arguments:")
            val shown = spec.args.sortedBy { !it.positional }.map { it.usage().removeSurrounding("[", "]") to it }
            val width = shown.maxOf { it.first.length }
            for ((usage, arg) in shown) out.appendLine("  ${usage.padEnd(width)}  ${arg.help}${arg.default?.let { " Default: $it." } ?: ""}${if (!arg.required && arg.positional) " (optional)" else ""}")
        }
        out.appendLine()
        out.appendLine("Runs: ${runs(spec)}")
        out.appendLine("Answer with --json: ${spec.answer}")
        out.appendLine()
        out.appendLine("Examples:")
        out.append(spec.examples.joinToString("\n") { "  $it" })
        return out.toString()
    }

    /** `facetool help --markdown`: the reference of all commands, for `docs/face-editor.md`. */
    fun markdown(): String {
        val out = StringBuilder()
        out.appendLine("## Command reference")
        out.appendLine()
        out.appendLine("Made by `facetool help --markdown` from the command table. Do not edit by hand.")
        out.appendLine()
        out.appendLine("Every command takes:")
        out.appendLine()
        for (arg in table.globals) out.appendLine("- `${arg.usage().removeSurrounding("[", "]")}`: ${arg.help}")
        for ((group, commands) in table.commands.groupBy { it.group }) {
            out.appendLine()
            out.appendLine("### $group")
            for (spec in commands) {
                out.appendLine()
                out.appendLine("#### `${spec.name}`")
                out.appendLine()
                out.appendLine("`${spec.usage()}`")
                out.appendLine()
                out.appendLine(spec.help)
                out.appendLine()
                out.appendLine("Runs: ${runs(spec)}")
                if (spec.args.isNotEmpty()) {
                    out.appendLine()
                    out.appendLine("| Argument | Type | Meaning |")
                    out.appendLine("|---|---|---|")
                    for (arg in spec.args.sortedBy { !it.positional })
                        out.appendLine("| `${arg.usage().removeSurrounding("[", "]").replace("|", "\\|")}` | ${arg.type.name.lowercase()} | ${arg.help.replace("|", "\\|")}${arg.default?.let { " Default: $it." } ?: ""} |")
                }
                out.appendLine()
                out.appendLine("Answer with `--json`: `${spec.answer}`")
                out.appendLine()
                out.appendLine("```")
                spec.examples.forEach { out.appendLine(it) }
                out.appendLine("```")
            }
        }
        return out.toString().trimEnd()
    }

    private fun runs(spec: CommandSpec): String = when {
        spec.cli                           -> "in the CLI (it is about the server itself)"
        spec.server                        -> "in the server (started when needed)"
        spec.serverWhenRunning             -> "local; in the server when one runs"
        spec.args.any { it.server }        -> "local; in the server with ${spec.args.filter { it.server }.joinToString(", ") { "--${it.name}" }}"
        else                               -> "local, without the server"
    }

    private fun firstSentence(text: String): String = Regex("^(.*?[.:])(\\s|$)").find(text)?.groupValues?.get(1)?.trimEnd(':')?.let { if (it.endsWith(".")) it else "$it." } ?: text
}
