package app.aaps.facetool.editor.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Lets the page follow an AI agent (or any other client) that works on the same project: when such a
 * client makes a picture, the page shows the same picture, with the command line that made it.
 *
 * The picture may lie anywhere (`--out`, `--gif`, `--frames`), and the page may only read files under
 * `.facetool/out/` of a project. So the picture is copied to `.facetool/out/follow/` first. Only the
 * newest picture is kept there.
 *
 * @param send sends an event to the pages of a project: its name, its data, the project folder
 */
class FollowPictures(private val send: (String, JsonObject, File) -> Unit) {

    private var count = 0

    /**
     * Called after a command of a client that is not the page. [line] is its command line. Sends a
     * `picture` event when [command] made a picture: a GIF, a PNG, or the frames of a film.
     */
    fun seen(command: String, line: String, project: File?, answer: JsonObject) {
        if (project == null || command !in PICTURE_COMMANDS || answer.containsKey("error")) return
        val gif = text(answer, "gif")
        val file = text(answer, "file")
        val frames = (answer["frames"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val sources = when {
            gif != null      -> listOf(gif)
            file != null     -> listOf(file)
            frames.isNotEmpty() -> frames
            else             -> return
        }.map { File(it) }.filter { it.isFile }
        if (sources.isEmpty()) return
        val folder = File(project, ".facetool/out/follow")
        synchronized(this) {
            folder.deleteRecursively()
            folder.mkdirs()
            count++
            val copies = sources.mapIndexed { i, source -> File(folder, "$count-${i.toString().padStart(4, '0')}-${source.name}").also { source.copyTo(it) } }
            send("picture", buildJsonObject {
                put("line", line)
                put("files", buildJsonArray { copies.forEach { add(JsonPrimitive(it.absolutePath)) } })
                (answer["frameMs"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { put("frameMs", it) }
                put("animated", gif != null)
            }, project)
        }
    }

    private fun text(json: JsonObject, key: String): String? = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {

        /** The commands that answer with a picture of the face. */
        val PICTURE_COMMANDS = setOf("render", "wake", "snapshot", "options", "looks", "wff render", "wff wake", "wff compare", "wff parts")
    }
}
