package app.aaps.facetool.editor.server

import app.aaps.facetool.editor.model.AndroidJson
import app.aaps.facetool.editor.model.DesignZip
import app.aaps.facetool.editor.model.ProjectFolder
import app.aaps.facetool.editor.model.ResourceNames
import app.aaps.facetool.editor.model.unreadable
import app.aaps.facetool.editor.model.wrongUse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * The project folders the page opened before, newest first, in one small file of the cache folder of
 * the editor. A folder that is no project any more is left out of the list.
 */
class RecentProjects(private val file: File, private val limit: Int = 12) {

    @Synchronized
    fun add(dir: File) {
        val path = dir.canonicalPath
        if (!ProjectFolder(File(path)).exists()) return
        val paths = (listOf(path) + read().filter { it != path }).take(limit)
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(AndroidJson.write(JsonArray(paths.map { JsonPrimitive(it) })))
    }

    @Synchronized
    fun list(): List<File> = read().map { File(it) }.filter { ProjectFolder(it).exists() }

    private fun read(): List<String> = runCatching {
        (AndroidJson.parse(file.readText()) as JsonArray).map { (it as JsonPrimitive).content }
    }.getOrDefault(emptyList())
}

/**
 * What the Open and New buttons of the page need (design section 10): the design zips that can be
 * opened, the project folders opened before, and a place for a zip that was dropped on the page.
 * The page then opens a zip with the command `open`, and makes a design with `new`, as the CLI does.
 *
 * @param root where the page makes project folders: `<root>/<name>/`
 * @param designsDir the folder with the design zips, or null when `local.properties` names none
 */
class ProjectPlaces(private val root: File, private val designsDir: File?, private val recent: RecentProjects) {

    /** The page of [dir] is open: keep it in the list of recent projects. */
    fun opened(dir: File) = recent.add(dir)

    /** `GET /api/projects`. */
    fun list(): JsonObject = buildJsonObject {
        put("root", root.path)
        put("designs", buildJsonArray {
            for (zip in designsDir?.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".zip") }.sortedBy { it.name }) add(place(zip))
        })
        put("recent", buildJsonArray {
            for (dir in recent.list()) add(buildJsonObject {
                put("name", dir.name)
                put("dir", dir.path)
            })
        })
    }

    /**
     * Where the zip [zip] opens: `dir` is its project folder. `project` is true when that folder is a
     * project already; opening the zip again must not overwrite it, so `freeDir` is a folder with a
     * new name for a second copy.
     */
    private fun place(zip: File): JsonObject {
        val name = zip.name.removeSuffix(".zip")
        return buildJsonObject {
            put("name", name)
            put("zip", zip.path)
            put("dir", File(root, name).path)
            put("project", ProjectFolder(File(root, name)).exists())
            put("freeDir", File(root, freeName(name)).path)
        }
    }

    /** The first of `name_2`, `name_3`, ... that is no folder and no zip in [root] yet. */
    private fun freeName(name: String): String = generateSequence(2) { it + 1 }.map { "${name}_$it" }.first { !File(root, it).exists() && !File(root, "$it.zip").exists() }

    /**
     * `POST /api/upload`: keeps the bytes of a zip that was dropped on the page as `<root>/<name>.zip`,
     * because a browser gives the page no path. The same zip again is kept once. Another zip with the
     * same name gets a new name: a zip is never written over.
     */
    fun storeUpload(fileName: String, bytes: ByteArray): JsonObject {
        val name = File(fileName).name.removeSuffix(".zip").replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_')
        if (name.isEmpty()) wrongUse("The name of the zip needs a letter or a digit")
        if (DesignZip.read(bytes).none { it.name == ResourceNames.DESIGN_JSON }) unreadable("$fileName has no ${ResourceNames.DESIGN_JSON}: it is not a design zip")
        root.mkdirs()
        val same = File(root, "$name.zip")
        val zip = when {
            !same.exists() && !File(root, name).exists() -> same
            same.isFile && same.readBytes().contentEquals(bytes) -> same
            else                                         -> File(root, "${freeName(name)}.zip")
        }
        if (!zip.exists()) zip.writeBytes(bytes)
        return place(zip)
    }
}
