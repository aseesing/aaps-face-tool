package app.aaps.facetool.editor.model

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The settings of the editor.
 * [editingRoot] is where `open` and `new` make project folders when no `--dir` is given.
 * [zipLimitBytes] is the largest zip that reaches the watch.
 */
class EditorConfig(
    val editingRoot: File,
    val generated: GeneratedDesigns = GeneratedDesigns.standard(),
    val zipLimitBytes: Int = 320 * 1024,
    val lockLimitMs: Long = 30_000
)

/** One line of a check: [severity] is `error`, `warning` or `info`. */
class Finding(val severity: String, val text: String, val path: String? = null)

/** What `open` did. */
class OpenResult(val project: ProjectFolder, val alreadyOpen: Boolean, val generated: Boolean, val files: Int)

/** What `save` did. [written] is false when an error stopped it. */
class SaveResult(val zip: File, val written: Boolean, val size: Int, val findings: List<Finding>, val engineChecked: Boolean, val generated: Boolean, val keptOld: File?)

/** Makes, opens and saves project folders (design sections 6.1, 6.5 and 6.6). */
class Projects(private val config: EditorConfig, private val clock: Clock) {

    private companion object {

        /** The preview picture of a new design. */
        const val PREVIEW_FILE = "CustomWatchface.png"
        const val PREVIEW_SIZE = 200
    }

    /** The lock of a project folder. */
    fun lock(project: ProjectFolder): FolderLock = FolderLock(File(project.toolDir, "lock"), clock, config.lockLimitMs)

    /**
     * Unpacks [zip] into a project folder: exactly the files of the zip, and `.facetool`.
     * [dir] null takes `<editing root>/<zip name>/`. A folder that holds something else is refused.
     */
    fun open(zip: File, dir: File?): OpenResult {
        if (!zip.isFile) wrongUse("The zip ${zip.path} is missing")
        val zipBytes = zip.readBytes()
        val entries = DesignZip.read(zipBytes)
        val jsonEntry = entries.firstOrNull { it.name == ResourceNames.DESIGN_JSON } ?: unreadable("${zip.name} has no ${ResourceNames.DESIGN_JSON}: it is not a design zip")
        val sourceText = jsonEntry.bytes.decodeToString()
        val design = AndroidJson.parseObject(sourceText, "${ResourceNames.DESIGN_JSON} of ${zip.name}")
        val generated = config.generated.isGenerated(design)
        val sourceHash = Hashes.sha256(zipBytes)

        val target = (dir ?: File(config.editingRoot, zip.name.removeSuffix(".zip"))).absoluteFile
        val project = ProjectFolder(target)
        if (project.exists()) {
            val meta = project.meta()
            val same = text(meta, "sourceZip") == zip.canonicalPath && text(meta, "sourceHash") == sourceHash
            if (same) return OpenResult(project, true, generated, entries.size)
            wrongUse(
                "${target.path} holds another design (from ${text(meta, "sourceZip") ?: "a new design"}, or an older ${zip.name}). " +
                    "The editor never mixes two zips: remove the folder, or give another one with --dir."
            )
        }
        if (target.exists() && !target.listFiles().isNullOrEmpty()) wrongUse("${target.path} is not empty and is not a project folder. Give another folder with --dir.")

        target.mkdirs()
        for (entry in entries) {
            val file = File(target, entry.name)
            file.parentFile.mkdirs()
            if (entry === jsonEntry) file.writeText(AndroidJson.write(design)) else file.writeBytes(entry.bytes)
        }
        project.writeMeta(buildJsonObject {
            put("sourceZip", zip.canonicalPath)
            put("sourceHash", sourceHash)
            put("openedAt", clock.instant().toString())
            put("openHash", project.hash())
            put("generated", generated)
            put("jsonForm", if (AndroidJson.compact(design) == sourceText) ProjectFolder.JSON_FORM_COMPACT else ProjectFolder.JSON_FORM_INDENT)
            put("order", buildJsonArray { entries.forEach { add(JsonPrimitive(it.name)) } })
            putJsonObject("entryTimes") { entries.forEach { entry -> entry.time?.let { put(entry.name, it.toString()) } } }
            putJsonObject("scene") {}
        })
        History(project, clock).start()
        return OpenResult(project, false, generated, entries.size)
    }

    /**
     * A new empty design in a new project folder: the metadata, a background colour, no layers.
     * [size] is the size of the design grid (the engine works with 400).
     */
    fun new(name: String, size: Int, dir: File?): ProjectFolder {
        val fileName = name.trim().replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_')
        if (fileName.isEmpty()) wrongUse("The name needs a letter or a digit")
        if (size <= 0) wrongUse("The size must be over 0")
        val target = (dir ?: File(config.editingRoot, fileName)).absoluteFile
        if (target.exists() && !target.listFiles().isNullOrEmpty()) wrongUse("${target.path} is not empty. Give another folder with --dir.")
        target.mkdirs()
        val project = ProjectFolder(target)
        project.writeDesign(buildJsonObject {
            putJsonObject("metadata") {
                put("name", name.trim())
                put("filename", fileName)
                put("author", "")
                put("created_at", DateTimeFormatter.ofPattern("dd/MM/yy").format(now()))
                put("author_version", "1.0")
                put("cwf_version", "3.0")
                put("comment", "")
            }
            putJsonObject("background") {
                put("width", size)
                put("height", size)
                put("topmargin", 0)
                put("leftmargin", 0)
                put("visibility", "visible")
                put("color", "#000000")
            }
            putJsonObject("animation") {
                put("durationMs", 6000)
                put("layers", buildJsonArray { })
            }
        })
        // The engine reads a zip as a design only when it has the preview picture `CustomWatchface`: a
        // plain black one to start with. Put a render of the design in its place before the zip is shared.
        ImageIO.write(BufferedImage(PREVIEW_SIZE, PREVIEW_SIZE, BufferedImage.TYPE_INT_RGB), "png", File(target, PREVIEW_FILE))
        project.writeMeta(buildJsonObject {
            put("openedAt", clock.instant().toString())
            put("openHash", project.hash())
            put("generated", false)
            put("jsonForm", ProjectFolder.JSON_FORM_INDENT)
            put("order", buildJsonArray { add(JsonPrimitive(ResourceNames.DESIGN_JSON)); add(JsonPrimitive(PREVIEW_FILE)) })
            putJsonObject("entryTimes") {}
            putJsonObject("scene") {}
        })
        History(project, clock).start()
        return project
    }

    /** The checks of `save` that need no engine: the zip size and the resources the JSON names. */
    fun check(project: ProjectFolder, design: JsonObject, zipSize: Int): List<Finding> {
        val findings = mutableListOf<Finding>()
        val percent = zipSize * 100L / config.zipLimitBytes
        if (zipSize > config.zipLimitBytes)
            findings += Finding("error", "The zip is $zipSize bytes, over the limit of ${config.zipLimitBytes} bytes: it never reaches the watch, and gives no error there.")
        else if (percent >= 95)
            findings += Finding("warning", "The zip is $zipSize bytes, $percent % of the limit of ${config.zipLimitBytes} bytes.")
        val keys = project.resources().keys.map { ResourceNames.keyOf(it) }.toSet()
        for (use in ResourceNames.uses(design)) if (use.key !in keys)
            findings += Finding("error", "The ${if (use.font) "font" else "picture"} '${use.key}' is named but there is no such file.", use.path)
        return findings
    }

    /**
     * Packs the folder into a zip (design section 6.5). [out] null writes back to the zip the
     * project came from, after it keeps the old one as `.facetool/history/<n>.zip`.
     * [engineCheck] gives the errors of the engine for the design, or is null when no engine runs.
     * An error stops the save, but not with [force].
     */
    fun save(project: ProjectFolder, out: File?, force: Boolean, command: String, engineCheck: ((ProjectFolder) -> List<String>)?): SaveResult {
        val meta = project.meta()
        val source = text(meta, "sourceZip")?.let { File(it) }
        val target = (out ?: source ?: wrongUse("This project came from no zip: give the zip to write with --out")).absoluteFile
        if (target.isDirectory) wrongUse("${target.path} is a folder: --out names the zip file")
        val design = project.design()
        val zip = project.zipBytes(now())
        val findings = check(project, design, zip.size).toMutableList()
        engineCheck?.invoke(project)?.forEach { findings += Finding("error", it) }
        val generated = config.generated.isGenerated(design)
        val errors = findings.any { it.severity == "error" }
        if (errors && !force) return SaveResult(target, false, zip.size, findings, engineCheck != null, generated, null)

        val history = History(project, clock)
        history.noticeOutsideChange()
        val entry = history.recordSave(command)
        val kept = if (target.isFile) history.zipFile(entry.n).also { target.copyTo(it, overwrite = true) } else null
        target.parentFile?.mkdirs()
        target.writeBytes(zip)
        if (source == null || target == source.absoluteFile) project.updateMeta(mapOf("sourceZip" to JsonPrimitive(target.canonicalPath), "sourceHash" to JsonPrimitive(Hashes.sha256(zip))))
        return SaveResult(target, true, zip.size, findings, engineCheck != null, generated, kept)
    }

    private fun now(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), clock.zone).withNano(0)

    private fun text(meta: JsonObject, key: String): String? = (meta[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
