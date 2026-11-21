package app.aaps.facetool.editor.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Clock

/** What a project folder holds at one moment: the hash of the JSON text, of each resource, and the zip order. */
data class FolderState(val json: String, val resources: Map<String, String>, val order: List<String>)

/** One entry of the history. [kind] is `edit`, `outside` (a change from outside the editor) or `save`. */
class HistoryEntry(val n: Int, val time: String, val command: String, val kind: String, val before: FolderState, val after: FolderState) {

    /** The resources that differ between before and after. */
    val changedResources: List<String> get() = (before.resources.keys + after.resources.keys).filter { before.resources[it] != after.resources[it] }.sorted()
}

/**
 * The history of a project (design section 6.4), in `.facetool/history/`.
 *
 * Every state the editor has seen is kept: the JSON text and each resource as a file named by its
 * hash (`blobs/`). An entry `<n>.json` names the state before and after one change. `state.json`
 * holds the place in the history (how many entries are applied) and the state the editor last saw,
 * so a change from outside is noticed at the next command.
 */
class History(private val project: ProjectFolder, private val clock: Clock) {

    private val dir: File get() = File(project.toolDir, "history")
    private val blobs: File get() = File(dir, "blobs")
    private val stateFile: File get() = File(dir, "state.json")

    /** Starts the history of a new project folder: keeps the state of now as entry 0. */
    fun start() {
        writeState(0, 0, keep(current()))
    }

    /** How many entries are applied. `undo` makes it smaller. */
    fun position(): Int = int(readState()["position"])

    /** How many entries there are. */
    fun count(): Int = int(readState()["count"])

    fun entries(): List<HistoryEntry> = (1..count()).map { entry(it) }

    /** The state of the folder now, from its files. */
    fun current(): FolderState =
        FolderState(Hashes.sha256(project.designText().toByteArray()), project.resources().mapValues { Hashes.sha256(it.value) }, project.order())

    /**
     * Adds the entry "changed outside the editor" when the folder is not as the editor last saw it.
     * Every command calls this first, so undo also takes back an outside change.
     */
    fun noticeOutsideChange(): HistoryEntry? {
        if (!stateFile.isFile) {
            start()
            return null
        }
        val seen = state(readState()["state"] as JsonObject)
        val now = current()
        if (now == seen) return null
        return add("changed outside the editor", "outside", seen, keep(now))
    }

    /** Adds an entry for the change a command made since [before]. Gives null when nothing changed. */
    fun record(command: String, before: FolderState): HistoryEntry? {
        val now = current()
        if (now == before) return null
        return add(command, "edit", before, keep(now))
    }

    /** Adds an entry for a save. It changes nothing; [n] of the entry names the kept old zip `<n>.zip`. */
    fun recordSave(command: String): HistoryEntry = current().let { add(command, "save", it, it) }

    /** The file that keeps the old zip of the save entry [n]. */
    fun zipFile(n: Int): File = File(dir, "$n.zip")

    /** Takes the last change back. Gives the entry, or null when there is nothing to undo. */
    fun undo(): HistoryEntry? {
        noticeOutsideChange()
        var position = position()
        while (position > 0) {
            val entry = entry(position)
            position--
            if (entry.kind == "save") continue
            restore(entry.after, entry.before)
            writeState(position, count(), entry.before)
            return entry
        }
        return null
    }

    /** Does the change again that the last undo took back. Gives the entry, or null when there is nothing to redo. */
    fun redo(): HistoryEntry? {
        noticeOutsideChange()
        var position = position()
        val count = count()
        while (position < count) {
            position++
            val entry = entry(position)
            if (entry.kind == "save") continue
            restore(entry.before, entry.after)
            writeState(position, count, entry.after)
            return entry
        }
        return null
    }

    /** The design as it was after entry [n]. 0 is the design at `open`. */
    fun snapshotAt(n: Int): DesignSnapshot {
        val count = count()
        if (n !in 0..count) wrongUse("history:$n: the history has the entries 0 to $count")
        val state = if (n == 0) (if (count == 0) state(readState()["state"] as JsonObject) else entry(1).before) else entry(n).after
        return DesignSnapshot(
            AndroidJson.parseObject(String(blob(state.json)), "The JSON of history:$n"),
            state.resources.mapValues { blob(it.value) }
        )
    }

    private fun add(command: String, kind: String, before: FolderState, after: FolderState): HistoryEntry {
        val position = position()
        // A new change after an undo: the entries that could be done again are gone
        for (n in position + 1..count()) {
            File(dir, "$n.json").delete()
            zipFile(n).delete()
        }
        val entry = HistoryEntry(position + 1, clock.instant().toString(), command, kind, before, after)
        File(dir, "${entry.n}.json").writeText(AndroidJson.write(buildJsonObject {
            put("n", entry.n)
            put("time", entry.time)
            put("command", entry.command)
            put("kind", entry.kind)
            put("before", json(entry.before))
            put("after", json(entry.after))
        }))
        writeState(entry.n, entry.n, after)
        return entry
    }

    /** Makes the folder as [to]. [from] is what it holds now. */
    private fun restore(from: FolderState, to: FolderState) {
        if (from.json != to.json) project.designFile.writeBytes(blob(to.json))
        for (name in from.resources.keys + to.resources.keys) {
            val hash = to.resources[name]
            val file = File(project.dir, name)
            when {
                hash == null                 -> file.delete()
                hash != from.resources[name] -> {
                    file.parentFile.mkdirs()
                    file.writeBytes(blob(hash))
                }
            }
        }
        if (from.order != to.order) project.writeOrder(to.order)
    }

    /** Keeps every file of the folder that the history does not have yet. Gives [state] back. */
    private fun keep(state: FolderState): FolderState {
        blobs.mkdirs()
        if (!File(blobs, state.json).exists()) File(blobs, state.json).writeBytes(project.designText().toByteArray())
        val missing = state.resources.filter { !File(blobs, it.value).exists() }
        if (missing.isNotEmpty()) {
            val bytes = project.resources()
            for ((name, hash) in missing) File(blobs, hash).writeBytes(bytes.getValue(name))
        }
        return state
    }

    private fun blob(hash: String): ByteArray = File(blobs, hash).takeIf { it.isFile }?.readBytes() ?: unreadable("The history misses the file $hash")

    private fun entry(n: Int): HistoryEntry {
        val json = AndroidJson.parseObject(File(dir, "$n.json").readText(), "History entry $n")
        return HistoryEntry(n, text(json["time"]), text(json["command"]), text(json["kind"]), state(json["before"] as JsonObject), state(json["after"] as JsonObject))
    }

    private fun readState(): JsonObject {
        if (!stateFile.isFile) start()
        return AndroidJson.parseObject(stateFile.readText(), stateFile.path)
    }

    private fun writeState(position: Int, count: Int, state: FolderState) {
        dir.mkdirs()
        stateFile.writeText(AndroidJson.write(buildJsonObject {
            put("position", position)
            put("count", count)
            put("state", json(state))
        }))
    }

    private fun json(state: FolderState): JsonObject = buildJsonObject {
        put("json", state.json)
        put("resources", JsonObject(state.resources.mapValues { JsonPrimitive(it.value) }))
        put("order", JsonArray(state.order.map { JsonPrimitive(it) }))
    }

    private fun state(json: JsonObject): FolderState = FolderState(
        text(json["json"]),
        (json["resources"] as JsonObject).mapValues { text(it.value) },
        (json["order"] as JsonArray).map { text(it) }
    )

    private fun text(element: Any?): String = (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: unreadable("The history does not read")

    private fun int(element: Any?): Int = text(element).toInt()
}
