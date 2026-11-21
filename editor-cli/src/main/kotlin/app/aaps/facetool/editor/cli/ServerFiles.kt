package app.aaps.facetool.editor.cli

import app.aaps.facetool.editor.model.AndroidJson
import app.aaps.facetool.editor.model.Hashes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** What the server wrote into `server.json` when it started. */
class ServerInfo(val port: Int, val pid: Long, val token: String, val startedAt: String, val checkout: String)

/**
 * The files of the server of one checkout (design section 5.4), in
 * `<cache root>/<first 16 hex of SHA-256 of the checkout path>/`: `server.json`, written by the
 * server, and `server.log`, the output of a server that the CLI started in the background.
 */
class ServerFiles(cacheRoot: File, val checkout: File) {

    val dir: File = File(cacheRoot, Hashes.sha256(checkout.path.toByteArray()).take(16))
    val serverJson: File get() = File(dir, "server.json")
    val log: File get() = File(dir, "server.log")

    /** The facts of `server.json`, or null when the file is missing or does not read. */
    fun read(): ServerInfo? {
        if (!serverJson.isFile) return null
        return runCatching {
            val json = AndroidJson.parseObject(serverJson.readText(), serverJson.path)
            fun text(key: String): String = (json.getValue(key) as JsonPrimitive).content
            ServerInfo(text("port").toInt(), text("pid").toLong(), text("token"), text("startedAt"), text("checkout"))
        }.getOrNull()
    }

    /** `server.json` as the server writes it. The tests use this to play the server. */
    fun write(info: ServerInfo) {
        dir.mkdirs()
        serverJson.writeText(AndroidJson.write(JsonObject(mapOf(
            "port" to JsonPrimitive(info.port),
            "pid" to JsonPrimitive(info.pid),
            "token" to JsonPrimitive(info.token),
            "startedAt" to JsonPrimitive(info.startedAt),
            "checkout" to JsonPrimitive(info.checkout)
        ))))
    }
}
