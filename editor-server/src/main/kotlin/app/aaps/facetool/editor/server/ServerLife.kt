package app.aaps.facetool.editor.server

import app.aaps.facetool.editor.model.AndroidJson
import app.aaps.facetool.editor.model.Hashes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.util.ArrayDeque

/**
 * The life of the server (design 5.4): `server.json` in the cache folder of the checkout, the stop
 * after [idleLimitMs] without a request, and the commands `status` and `stop`.
 *
 * @param cacheRoot `~/.cache/aaps-face-editor`
 * @param checkout the path of the `aaps-face-tool` checkout, exactly as the CLI gives it
 *   (`-Deditor.checkout`): the CLI finds the server by a hash of this text
 */
class ServerLife(
    private val cacheRoot: File, private val checkout: String, private val token: String, private val facts: ServerFacts,
    private val now: () -> Long, private val idleLimitMs: Long = 2 * 60 * 60 * 1000L
) {

    private val dir = File(cacheRoot, Hashes.sha256(checkout.toByteArray()).take(16))
    val serverJson = File(dir, "server.json")

    @Volatile
    private var lastRequestMs = now()

    @Volatile
    private var stopped = false

    @Volatile
    private var port = 0

    private val lastCommands = ArrayDeque<String>()

    /** True until `stop` was asked for or the server was idle for too long. */
    val isRunning: Boolean get() = !stopped && now() - lastRequestMs < idleLimitMs

    fun requestSeen() {
        lastRequestMs = now()
    }

    /** Keeps the last commands for `status`. */
    fun commandSeen(line: String) = synchronized(lastCommands) {
        lastCommands.addLast("${Instant.ofEpochMilli(now())} $line")
        while (lastCommands.size > 10) lastCommands.removeFirst()
    }

    /** Writes `server.json`: the CLI and the GUI find the server with it. */
    fun started(port: Int) {
        this.port = port
        dir.mkdirs()
        serverJson.writeText(AndroidJson.write(JsonObject(mapOf(
            "port" to JsonPrimitive(port),
            "pid" to JsonPrimitive(ProcessHandle.current().pid()),
            "token" to JsonPrimitive(token),
            "startedAt" to JsonPrimitive(Instant.ofEpochMilli(facts.startedAtMs).toString()),
            "checkout" to JsonPrimitive(checkout)
        ))))
    }

    /** Removes `server.json` when it is still the file of this server. */
    fun ended() {
        if (serverJson.isFile && serverJson.readText().contains("\"$token\"")) serverJson.delete()
    }

    fun status(): JsonObject {
        val upSec = (now() - facts.startedAtMs) / 1000
        val commands = synchronized(lastCommands) { lastCommands.toList() }
        return buildJsonObject {
            put("running", true)
            put("port", port)
            put("pid", ProcessHandle.current().pid())
            put("aapsCommit", facts.aapsCommit ?: "unknown")
            put("pinnedCommit", facts.pinnedCommit ?: "unknown")
            put("pinned", facts.isPinned)
            put("restartNeeded", facts.restartNeeded)
            put("uptimeSec", upSec)
            put("lastRequests", JsonArray(commands.map { JsonPrimitive(it) }))
            put("warnings", JsonArray(facts.warnings().map { JsonPrimitive(it) }))
            put("text", (listOf(
                "The server runs on 127.0.0.1:$port (process ${ProcessHandle.current().pid()}), up for $upSec s.",
                "AAPS commit: ${facts.aapsCommit ?: "unknown"} (${if (facts.isPinned) "the tested one" else "NOT the tested ${facts.pinnedCommit}"})"
            ) + facts.warnings().map { "warning: $it" } + listOf("Last requests:") + commands.map { "  $it" }).joinToString("\n"))
        }
    }

    /** Answers first; the server loop ends after it. */
    fun stop(): JsonObject {
        stopped = true
        return buildJsonObject {
            put("stopped", true)
            put("text", "The server stops.")
        }
    }
}
