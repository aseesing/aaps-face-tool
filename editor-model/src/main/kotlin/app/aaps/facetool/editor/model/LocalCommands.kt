package app.aaps.facetool.editor.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.Clock

/** The answer of a command: the JSON object (always with the key `text`, the short text for a person) and the exit code. */
class CommandAnswer(val json: JsonObject, val exitCode: Int)

/**
 * Runs every command of the table that needs no server (design section 5.3). The CLI calls it for
 * the local commands; the server calls the same class for the GUI.
 *
 * [engineCheck] gives the engine's errors for a design at `save`; null when no engine runs (the CLI).
 * [stdin] reads standard input, for `apply -`.
 */
class LocalCommands(
    private val config: EditorConfig,
    private val clock: Clock,
    private val table: CommandTable = CommandTable.standard(),
    private val engineCheck: ((ProjectFolder) -> List<String>)? = null,
    private val stdin: () -> String = { "" },
    private val git: GitReader = ProcessGitReader()
) {

    private val projects = Projects(config, clock)

    /**
     * Runs [command] (a name of the table, `layer add` with a space) with [args] (argument name
     * without the dashes to value). [projectDir] is the project folder, or null when there is none.
     * An error gives an answer with the keys `error` and `text` and its exit code; nothing is thrown.
     */
    fun run(command: String, args: JsonObject, projectDir: File?): CommandAnswer = try {
        val spec = table.find(command) ?: wrongUse("Unknown command '$command'. See: facetool help")
        if (spec.needsServer(args)) wrongUse("'$command' needs the server")
        val project = projectDir?.let { ProjectFolder(it) }
        if (project != null && !project.exists()) wrongUse("${projectDir.path} is not a project folder (it has no .facetool). Make one with: facetool open <zip>")
        if (spec.project && project == null) wrongUse("'$command' needs a project: run it in a project folder, or give --project <dir>. Make one with: facetool open <zip>")
        val line = CommandLines.format(spec, args)
        when (command) {
            "open"         -> open(args)
            "new"          -> new(args)
            "save"         -> save(project!!, args, line)
            "info"         -> info(project!!)
            "show"         -> show(project!!, args)
            "list"         -> list(project!!, args)
            "set"          -> editJson(project!!, args, line) { DesignEdits.set(it, text(args, "path"), args.getValue("value")) }
            "unset"        -> editJson(project!!, args, line) { DesignEdits.unset(it, text(args, "path")) }
            "apply"        -> editJson(project!!, args, line) { DesignEdits.apply(it, patch(args)) }
            "layer add"    -> editJson(project!!, args, line) { DesignEdits.layerAdd(it, args.getValue("json"), int(args, "at")) }
            "layer move"   -> editJson(project!!, args, line) { DesignEdits.layerMove(it, int(args, "from")!!, int(args, "to")!!) }
            "layer copy"   -> editJson(project!!, args, line) { DesignEdits.layerCopy(it, int(args, "n")!!, int(args, "at")) }
            "layer remove" -> editJson(project!!, args, line) { DesignEdits.layerRemove(it, int(args, "n")!!) }
            "res add"      -> edit(project!!, args, line) { resAdd(project, args) }
            "res replace"  -> edit(project!!, args, line) { resReplace(project, args) }
            "res remove"   -> edit(project!!, args, line) { resRemove(project, args) }
            "res rename"   -> edit(project!!, args, line) { resRename(project, args) }
            "undo"         -> step(project!!, args, line, "undo")
            "redo"         -> step(project!!, args, line, "redo")
            "history"      -> history(project!!)
            "diff"         -> diff(project, args)
            "help"         -> help(args)
            else           -> wrongUse("'$command' has no local code")
        }
    } catch (e: FacetoolException) {
        CommandAnswer(buildJsonObject {
            put("error", e.message)
            put("exitCode", e.exitCode)
            put("text", e.message)
        }, e.exitCode)
    }

    private fun answer(exitCode: Int = ExitCodes.OK, build: JsonObjectBuilder.() -> Unit): CommandAnswer = CommandAnswer(buildJsonObject {
        build()
        if (exitCode != ExitCodes.OK) put("exitCode", exitCode)
    }, exitCode)

    private fun open(args: JsonObject): CommandAnswer {
        val zip = File(text(args, "zip"))
        val result = projects.open(zip, textOrNull(args, "dir")?.let { File(it) })
        return answer {
            put("dir", result.project.dir.path)
            put("files", result.files)
            put("alreadyOpen", result.alreadyOpen)
            put("generated", result.generated)
            if (result.generated) put("warning", GeneratedDesigns.WARNING)
            put("text", listOfNotNull(
                if (result.alreadyOpen) "${zip.name} is open already in ${result.project.dir.path}" else "Opened ${zip.name} in ${result.project.dir.path} (${result.files} files)",
                GeneratedDesigns.WARNING.takeIf { result.generated }
            ).joinToString("\n"))
        }
    }

    private fun new(args: JsonObject): CommandAnswer {
        val project = projects.new(text(args, "name"), int(args, "size") ?: 400, textOrNull(args, "dir")?.let { File(it) })
        return answer {
            put("dir", project.dir.path)
            put("text", "Made the new design ${text(args, "name")} in ${project.dir.path}")
        }
    }

    private fun save(project: ProjectFolder, args: JsonObject, line: String): CommandAnswer = projects.lock(project).with(line, flag(args, "force")) {
        val saveAs = textOrNull(args, "as")
        if (saveAs != null && textOrNull(args, "out") != null) wrongUse("Give --out or --as, not both")
        val result = projects.save(project, (saveAs ?: textOrNull(args, "out"))?.let { File(it) }, flag(args, "force"), line, engineCheck, asSource = saveAs != null)
        val lines = mutableListOf<String>()
        lines += if (result.written) "Saved ${result.zip.path} (${result.size} bytes, ${result.size * 100L / config.zipLimitBytes} % of the limit of the watch)" else "Not saved: a check has errors. Give --force to write the zip anyway."
        result.findings.forEach { lines += "${it.severity}: ${it.path?.let { path -> "$path: " } ?: ""}${it.text}" }
        if (!result.engineChecked) lines += "info: the engine check was skipped (the server does not run)"
        result.keptOld?.let { lines += "The old zip is kept as ${it.path}" }
        if (saveAs != null && result.written) lines += "From now on Save writes to ${result.zip.path}"
        if (result.generated) lines += GeneratedDesigns.WARNING
        answer(if (result.findings.any { it.severity == "error" }) ExitCodes.CHECK_ERRORS else ExitCodes.OK) {
            put("zip", result.zip.path)
            put("written", result.written)
            put("bytes", result.size)
            put("limit", config.zipLimitBytes)
            put("engineCheck", if (result.engineChecked) "done" else "skipped")
            put("generated", result.generated)
            put("findings", JsonArray(result.findings.map { finding ->
                buildJsonObject {
                    put("severity", finding.severity)
                    put("text", finding.text)
                    finding.path?.let { put("path", it) }
                }
            }))
            put("text", lines.joinToString("\n"))
        }
    }

    private fun info(project: ProjectFolder): CommandAnswer {
        val design = project.design()
        val meta = project.meta()
        val resources = project.resources()
        val metadata = design["metadata"] as? JsonObject
        val zipBytes = project.zipBytes().size
        val generated = config.generated.isGenerated(design)
        val changed = textOrNull(meta, "openHash") != project.hash()
        val counts = linkedMapOf(
            "layers" to DesignEdits.layers(design).size,
            "views" to DesignLists.views(design).size,
            "templates" to DesignLists.templates(design).size,
            "options" to DesignLists.options(design).size,
            "looks" to DesignLists.looks(design).size
        )
        val wakeMs = (JsonPaths.get(design, JsonPaths.parse("animation.durationMs")) as? JsonPrimitive)?.content
        val jsonBytes = project.designText().toByteArray().size
        return answer {
            put("name", textOrNull(metadata, "name") ?: "")
            put("author", textOrNull(metadata, "author") ?: "")
            put("filename", textOrNull(metadata, "filename") ?: "")
            put("dir", project.dir.path)
            textOrNull(meta, "sourceZip")?.let { put("sourceZip", it) }
            put("zipBytes", zipBytes)
            put("zipLimit", config.zipLimitBytes)
            put("jsonBytes", jsonBytes)
            put("resources", JsonArray(resources.map { buildJsonObject { put("name", it.key); put("bytes", it.value.size) } }))
            putJsonObject("counts") { counts.forEach { put(it.key, it.value) } }
            wakeMs?.toLongOrNull()?.let { put("wakeMs", it) }
            // The frames a second of the wake; the engine takes 24 when the design names none
            if (design["animation"] is JsonObject) put("wakeFps", (JsonPaths.get(design, JsonPaths.parse("animation.fps")) as? JsonPrimitive)?.content?.toIntOrNull() ?: DEFAULT_WAKE_FPS)
            put("generated", generated)
            put("changedSinceOpen", changed)
            put("text", buildString {
                appendLine("${textOrNull(metadata, "name")} by ${textOrNull(metadata, "author")} (${textOrNull(metadata, "filename")})")
                appendLine("Folder: ${project.dir.path}")
                appendLine("Zip: ${textOrNull(meta, "sourceZip") ?: "none yet"}")
                appendLine("Sizes: zip $zipBytes bytes (${zipBytes * 100L / config.zipLimitBytes} % of the limit of the watch), JSON $jsonBytes bytes, ${resources.size} resources ${resources.values.sumOf { it.size }} bytes")
                appendLine("Counts: ${counts.entries.joinToString(", ") { "${it.value} ${it.key}" }}")
                appendLine("Wake: ${wakeMs?.let { "$it ms" } ?: "none"}")
                appendLine("Made by a generator: ${if (generated) "yes" else "no"}")
                appendLine("Changed since open: ${if (changed) "yes" else "no"}")
                append("Resources:")
                resources.forEach { append("\n  ${it.key}  ${it.value.size} B") }
            })
        }
    }

    private fun show(project: ProjectFolder, args: JsonObject): CommandAnswer {
        val path = textOrNull(args, "path") ?: ""
        val value = JsonPaths.get(project.design(), JsonPaths.parse(path)) ?: wrongUse("$path: nothing is there")
        return answer {
            put("path", path)
            put("value", value)
            put("text", AndroidJson.write(value))
        }
    }

    private fun list(project: ProjectFolder, args: JsonObject): CommandAnswer {
        val kind = text(args, "kind")
        val items = DesignLists.list(kind, project.design(), project.resources())
        return answer {
            put("kind", kind)
            put("items", JsonArray(items))
            put("text", if (items.isEmpty()) "no $kind" else items.joinToString("\n") { item ->
                val label = when {
                    kind == "layers" -> "L${textOrNull(item, "index")}"
                    "name" in item   -> textOrNull(item, "name")!!
                    else             -> "[${textOrNull(item, "index")}]"
                }
                "${label.padEnd(if (kind == "layers") 4 else 2)}  ${textOrNull(item, "summary")}"
            })
        }
    }

    /** A change of the design JSON: [change] gives the new design from the old one. */
    private fun editJson(project: ProjectFolder, args: JsonObject, line: String, change: (JsonObject) -> JsonObject): CommandAnswer = edit(project, args, line) {
        val old = project.design()
        val new = change(old)
        if (new != old) project.writeDesign(new)
        emptyMap()
    }

    /** A change of the folder under the lock, with its history entry. [work] gives more keys for the answer. */
    private fun edit(project: ProjectFolder, args: JsonObject, line: String, work: () -> Map<String, JsonElement>): CommandAnswer = projects.lock(project).with(line, flag(args, "force")) {
        val history = History(project, clock)
        val outside = history.noticeOutsideChange()
        val before = history.current()
        val more = work()
        val entry = history.record(line, before)
        answer {
            put("changed", entry != null)
            entry?.let { put("history", it.n) }
            more.forEach { put(it.key, it.value) }
            put("text", listOfNotNull(
                outside?.let { "The folder was changed outside the editor: history entry ${it.n}." },
                if (entry != null) "Done: history entry ${entry.n}." else "Nothing changed.",
                (more["note"] as? JsonPrimitive)?.content
            ).joinToString("\n"))
        }
    }

    private fun patch(args: JsonObject): JsonArray {
        val path = text(args, "patch")
        val content = if (path == "-") stdin() else File(path).takeIf { it.isFile }?.readText() ?: wrongUse("The patch file $path is missing")
        val patch = try {
            AndroidJson.parse(content)
        } catch (e: IllegalArgumentException) {
            wrongUse("The patch is not JSON: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        return patch as? JsonArray ?: wrongUse("The patch is a JSON list of edits")
    }

    /** Reads a file to add as a resource. A zip is refused: the editor never takes design parts from a second zip. */
    private fun resourceFile(path: String): Pair<String, ByteArray> {
        val file = File(path)
        if (!file.isFile) wrongUse("The file $path is missing")
        val extension = ResourceNames.extensionOf(file.name)
        val bytes = file.readBytes()
        val isZip = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() && bytes[2] == 3.toByte() && bytes[3] == 4.toByte()
        if (extension == "zip" || isZip) wrongUse("${file.name} is a zip. A resource is one picture or font file; the editor never takes parts from a second zip.")
        if (extension !in ResourceNames.IMAGE_EXTENSIONS && extension !in ResourceNames.FONT_EXTENSIONS)
            wrongUse("${file.name}: a resource is a picture (${ResourceNames.IMAGE_EXTENSIONS.joinToString(", ")}) or a font (${ResourceNames.FONT_EXTENSIONS.joinToString(", ")})")
        return extension to bytes
    }

    private fun existing(project: ProjectFolder, key: String): String = project.resourceFile(key) ?: wrongUse("There is no resource '$key'. See: facetool list resources")

    private fun checkKey(key: String) {
        if (!key.matches(Regex("[A-Za-z0-9_-]+"))) wrongUse("A resource key has only letters, digits, - and _ (got '$key')")
    }

    private fun resAdd(project: ProjectFolder, args: JsonObject): Map<String, JsonElement> {
        val path = text(args, "file")
        val (extension, bytes) = resourceFile(path)
        val key = textOrNull(args, "name") ?: ResourceNames.keyOf(File(path).name)
        checkKey(key)
        project.resourceFile(key)?.let { wrongUse("The resource '$key' is there already ($it). Use: facetool res replace $key <file>") }
        File(project.dir, "$key.$extension").writeBytes(bytes)
        return mapOf("name" to JsonPrimitive("$key.$extension"))
    }

    private fun resReplace(project: ProjectFolder, args: JsonObject): Map<String, JsonElement> {
        val old = existing(project, text(args, "key"))
        val (extension, bytes) = resourceFile(text(args, "file"))
        val new = "${ResourceNames.keyOf(old)}.$extension"
        if (new != old) {
            File(project.dir, old).delete()
            renameInMeta(project, old, new)
        }
        File(project.dir, new).writeBytes(bytes)
        return mapOf("name" to JsonPrimitive(new))
    }

    private fun resRemove(project: ProjectFolder, args: JsonObject): Map<String, JsonElement> {
        val old = existing(project, text(args, "key"))
        val uses = ResourceNames.uses(project.design()).filter { it.key == ResourceNames.keyOf(old) }
        File(project.dir, old).delete()
        return buildMap {
            put("name", JsonPrimitive(old))
            if (uses.isNotEmpty()) put("note", JsonPrimitive("The JSON still names it at: ${uses.joinToString(", ") { it.path }}"))
        }
    }

    private fun resRename(project: ProjectFolder, args: JsonObject): Map<String, JsonElement> {
        val old = existing(project, text(args, "old"))
        val oldKey = ResourceNames.keyOf(old)
        val newKey = text(args, "new")
        checkKey(newKey)
        project.resourceFile(newKey)?.let { wrongUse("The resource '$newKey' is there already ($it)") }
        val new = "$newKey.${ResourceNames.extensionOf(old)}"
        val design = project.design()
        val uses = ResourceNames.uses(design).count { it.key == oldKey }
        val others = ResourceNames.otherTexts(design, oldKey)
        if (!File(project.dir, old).renameTo(File(project.dir, new))) wrongUse("Could not rename $old to $new")
        renameInMeta(project, old, new)
        if (uses > 0) project.writeDesign(ResourceNames.rename(design, oldKey, newKey))
        return buildMap {
            put("name", JsonPrimitive(new))
            put("uses", JsonPrimitive(uses))
            put("otherTexts", JsonArray(others.map { JsonPrimitive(it) }))
            put("note", JsonPrimitive("Renamed $old to $new and changed $uses places in the JSON." + if (others.isEmpty()) "" else " Not changed, the same text at: ${others.joinToString(", ")}"))
        }
    }

    /** Gives the new file name the place and the zip time of the old one. */
    private fun renameInMeta(project: ProjectFolder, old: String, new: String) {
        val meta = project.meta()
        val times = (meta["entryTimes"] as? JsonObject).orEmpty()
        project.updateMeta(buildMap {
            put("order", JsonArray(project.order().map { JsonPrimitive(if (it == old) new else it) }))
            times[old]?.let { put("entryTimes", JsonObject(times + (new to it))) }
        })
    }

    private fun step(project: ProjectFolder, args: JsonObject, line: String, what: String): CommandAnswer = projects.lock(project).with(line, flag(args, "force")) {
        val history = History(project, clock)
        val entry = if (what == "undo") history.undo() else history.redo()
        answer {
            put("done", entry != null)
            entry?.let { put("entry", toJson(it)) }
            put("position", history.position())
            put("text", if (entry == null) "Nothing to $what." else "${if (what == "undo") "Undone" else "Done again"}: entry ${entry.n}, ${entry.command}")
        }
    }

    private fun history(project: ProjectFolder): CommandAnswer {
        val history = History(project, clock)
        val entries = history.entries()
        val position = history.position()
        return answer {
            put("position", position)
            put("entries", JsonArray(entries.map { toJson(it) }))
            put("text", if (entries.isEmpty()) "No changes yet." else entries.joinToString("\n") { "${if (it.n == position) "*" else " "} ${it.n}  ${it.time}  ${it.command}" })
        }
    }

    private fun toJson(entry: HistoryEntry): JsonObject = buildJsonObject {
        put("n", entry.n)
        put("time", entry.time)
        put("command", entry.command)
        put("kind", entry.kind)
        put("resources", JsonArray(entry.changedResources.map { JsonPrimitive(it) }))
    }

    private fun diff(project: ProjectFolder?, args: JsonObject): CommandAnswer {
        val sources = DesignSources(clock, git)
        val a = sources.load(text(args, "a"), project)
        val b = sources.load(text(args, "b"), project)
        val json = DesignDiff.json(a.json, b.json)
        val resources = DesignDiff.resources(a.resources, b.resources)
        return answer {
            put("same", json.isEmpty() && resources.isEmpty())
            put("json", JsonArray(json.map { DesignDiff.toJson(it) }))
            put("resources", JsonArray(resources.map { DesignDiff.toJson(it) }))
            put("text", DesignDiff.text(json, resources))
        }
    }

    private fun help(args: JsonObject): CommandAnswer {
        val help = Help(table)
        val words = (args["command"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }
        val text = when {
            flag(args, "markdown") -> help.markdown()
            words.isEmpty()        -> help.overview()
            else                   -> help.command(table.find(words.joinToString(" ")) ?: wrongUse("Unknown command '${words.joinToString(" ")}'. See: facetool help"))
        }
        return answer {
            table.toJson().forEach { put(it.key, it.value) }
            put("text", text)
        }
    }

    private fun text(args: JsonObject, key: String): String = textOrNull(args, key) ?: wrongUse("The argument '$key' is missing")

    private fun textOrNull(args: JsonObject?, key: String): String? = (args?.get(key) as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

    private fun int(args: JsonObject, key: String): Int? = (args[key] as? JsonPrimitive)?.content?.let { it.toIntOrNull() ?: wrongUse("The argument '$key' needs a whole number") }

    private fun flag(args: JsonObject, key: String): Boolean = (args[key] as? JsonPrimitive)?.content == "true"

    private companion object {

        /** The frames a second of a wake when the design names none (`WakeAnimationSpec` of the engine). */
        const val DEFAULT_WAKE_FPS = 24
    }
}
