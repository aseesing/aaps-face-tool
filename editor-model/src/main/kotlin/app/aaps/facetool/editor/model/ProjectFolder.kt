package app.aaps.facetool.editor.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.LocalDateTime

/** A design outside a folder: its JSON and its resource files (file name to bytes). */
class DesignSnapshot(val json: JsonObject, val resources: Map<String, ByteArray>)

/**
 * A project folder (design section 6.1): the files of one design zip, and the hidden folder
 * `.facetool` of the editor. This class keeps no copy of the folder: every call reads the files
 * fresh, so an edit from outside is seen at once.
 */
class ProjectFolder(val dir: File) {

    /** The hidden folder of the editor. */
    val toolDir: File get() = File(dir, TOOL_DIR)

    /** The design JSON file. */
    val designFile: File get() = File(dir, ResourceNames.DESIGN_JSON)

    private val metaFile: File get() = File(toolDir, "project.json")

    /** True when the folder is a project: it has `.facetool/project.json`. */
    fun exists(): Boolean = metaFile.isFile

    /** The design JSON text, as it is on disk. */
    fun designText(): String {
        if (!designFile.isFile) unreadable("${designFile.path} is missing")
        return designFile.readText()
    }

    /** The design JSON. Ends with exit code 3 when the file does not read. */
    fun design(): JsonObject = AndroidJson.parseObject(designText(), designFile.name)

    /** Writes the design JSON in the form of section 6.2. */
    fun writeDesign(design: JsonObject) {
        designFile.writeText(AndroidJson.write(design))
    }

    /**
     * The resource files: file name to bytes. Every file of the folder but the design JSON and
     * the `.facetool` folder, also files the editor does not know (a licence). A file in a
     * sub folder has a name with `/`.
     */
    fun resources(): Map<String, ByteArray> {
        val found = sortedMapOf<String, ByteArray>()
        fun walk(folder: File, prefix: String) {
            for (file in folder.listFiles().orEmpty()) {
                val name = prefix + file.name
                when {
                    file.name == TOOL_DIR || file.name == ".DS_Store" || file.name == ".git" -> Unit
                    file.isDirectory                                                         -> walk(file, "$name/")
                    name != ResourceNames.DESIGN_JSON                                        -> found[name] = file.readBytes()
                }
            }
        }
        walk(dir, "")
        return found
    }

    /** The file name of the resource with [key]: a file name, or a name without its extension. Null when there is none. */
    fun resourceFile(key: String): String? {
        val names = resources().keys
        return names.firstOrNull { it == key } ?: names.firstOrNull { ResourceNames.keyOf(it) == key && !it.contains('/') }
    }

    /** The design as a snapshot. */
    fun snapshot(): DesignSnapshot = DesignSnapshot(design(), resources())

    /** The project facts of `.facetool/project.json`. */
    fun meta(): JsonObject = if (exists()) AndroidJson.parseObject(metaFile.readText(), metaFile.path) else JsonObject(emptyMap())

    fun writeMeta(meta: JsonObject) {
        toolDir.mkdirs()
        metaFile.writeText(AndroidJson.write(meta))
    }

    /** Changes keys of the project facts. */
    fun updateMeta(changes: Map<String, JsonElement>) = writeMeta(JsonObject(LinkedHashMap(meta()).apply { putAll(changes) }))

    /** The file names in the order the zip gets them. Files that are not in this list follow, sorted by name. */
    fun order(): List<String> = (meta()["order"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }

    fun writeOrder(order: List<String>) = updateMeta(mapOf("order" to JsonArray(order.map { JsonPrimitive(it) })))

    /** The current scene of the project, with the defaults of design section 7 filled in. */
    fun scene(): JsonObject = Scenes.merge(meta()["scene"] as? JsonObject, null, emptyList())

    /** The folder for the output of [command]: `.facetool/out/<command>/`. It is made when missing. */
    fun outDir(command: String): File = File(toolDir, "out/${command.replace(' ', '-')}").apply { mkdirs() }

    /** A hash of the design JSON text and every resource. It changes when any of them changes. */
    fun hash(): String = Hashes.sha256(listOf(ResourceNames.DESIGN_JSON to designText().toByteArray()) + resources().map { it.key to it.value })

    /**
     * The zip that `save` writes, in memory. The JSON is written in the form the source zip had
     * (4 spaces, or on one line), the files come in the order of the source zip, new files after them.
     */
    fun zipBytes(defaultTime: LocalDateTime = LocalDateTime.of(2026, 1, 1, 0, 0)): ByteArray {
        val meta = meta()
        val compact = (meta["jsonForm"] as? JsonPrimitive)?.content == JSON_FORM_COMPACT
        val json = design().let { if (compact) AndroidJson.compact(it) else AndroidJson.write(it) }
        val files = LinkedHashMap<String, ByteArray>()
        files[ResourceNames.DESIGN_JSON] = json.toByteArray()
        files.putAll(resources())
        val times = meta["entryTimes"] as? JsonObject
        val order = order().filter { it in files }
        val names = order + (files.keys - order.toSet()).let { rest -> if (ResourceNames.DESIGN_JSON in rest) listOf(ResourceNames.DESIGN_JSON) + (rest - ResourceNames.DESIGN_JSON) else rest.toList() }
        return DesignZip.write(names.map { name ->
            val time = (times?.get(name) as? JsonPrimitive)?.content?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
            ZipFileEntry(name, files.getValue(name), time)
        }, defaultTime)
    }

    companion object {

        const val TOOL_DIR = ".facetool"
        const val JSON_FORM_COMPACT = "compact"
        const val JSON_FORM_INDENT = "indent4"

        /** The project folder that holds [start]: the first folder at or above it with `.facetool/project.json`. */
        fun find(start: File): ProjectFolder? {
            var at: File? = start.absoluteFile
            while (at != null) {
                val project = ProjectFolder(at)
                if (project.exists()) return project
                at = at.parentFile
            }
            return null
        }
    }
}
