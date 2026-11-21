package app.aaps.facetool.editor.server

import app.aaps.facetool.editor.model.AndroidJson
import app.aaps.facetool.editor.model.CommandTable
import app.aaps.facetool.editor.model.FacetoolException
import app.aaps.facetool.editor.model.wrongUse
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * The HTTP API of the server (design section 9), on 127.0.0.1 only. Every request under `/api/` must
 * carry the token: in the header `X-Facetool-Token`, or as `token=` in the query for requests a
 * browser makes by itself (a picture, the events).
 *
 * @param places the zips and project folders for the Open button of the page
 * @param frameStops takes the wish of the page to stop a frames call
 * @param gui gives a file of the GUI page by its path (`index.html`), or null
 * @param onRequest called at the start of each API request, to count the idle time
 */
class HttpApi(
    private val commands: ServerCommands, private val table: CommandTable, private val token: String, private val facts: ServerFacts,
    private val events: Events, private val places: ProjectPlaces, private val frameStops: FrameStops, private val gui: (String) -> ByteArray?, private val onRequest: () -> Unit
) {

    private var server: HttpServer? = null

    val port: Int get() = server?.address?.port ?: error("the server is not started")

    fun start() {
        val http = HttpServer.create(InetSocketAddress(InetAddress.getByName(HOST), 0), 0)
        http.executor = Executors.newCachedThreadPool { work -> Thread(work, "facetool-http").apply { isDaemon = true } }
        http.createContext("/api/run") { exchange -> api(exchange) { run(exchange) } }
        http.createContext("/api/commands") { exchange -> api(exchange) { json(exchange, 200, table.toJson()) } }
        http.createContext("/api/ping") { exchange ->
            api(exchange) {
                json(exchange, 200, buildJsonObject {
                    put("ok", true)
                    put("aapsCommit", facts.aapsCommit ?: "unknown")
                    put("pinned", facts.isPinned)
                    put("restartNeeded", facts.restartNeeded)
                })
            }
        }
        http.createContext("/api/file") { exchange -> api(exchange) { file(exchange) } }
        http.createContext("/api/events") { exchange -> api(exchange) { events.serve(exchange, query(exchange)["project"]) } }
        http.createContext("/api/projects") { exchange -> api(exchange) { json(exchange, 200, places.list()) } }
        http.createContext("/api/cancel") { exchange ->
            api(exchange) {
                query(exchange)["dir"]?.let { frameStops.ask(File(it)) }
                json(exchange, 200, buildJsonObject { put("ok", true) })
            }
        }
        http.createContext("/api/upload") { exchange -> api(exchange) { upload(exchange) } }
        http.createContext("/") { exchange -> page(exchange) }
        http.start()
        server = http
    }

    fun stop() {
        events.close()
        server?.stop(0)
        server = null
    }

    /** Checks the token, then runs [work]. Any error becomes an answer, so a request never hangs. */
    private fun api(exchange: HttpExchange, work: () -> Unit) {
        try {
            val given = exchange.requestHeaders.getFirst(TOKEN_HEADER) ?: query(exchange)["token"]
            if (given != token) return json(exchange, 403, buildJsonObject { put("error", "wrong or missing token"); put("text", "wrong or missing token") })
            onRequest()
            work()
        } catch (e: Throwable) {
            runCatching { json(exchange, 500, buildJsonObject { put("error", e.toString()); put("exitCode", 4); put("text", "The server failed: $e") }) }
        } finally {
            exchange.close()
        }
    }

    private fun run(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") return json(exchange, 405, buildJsonObject { put("error", "POST only"); put("text", "POST only") })
        val body = AndroidJson.parseObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8), "the request")
        val command = (body["command"] as? JsonPrimitive)?.content ?: return json(exchange, 400, buildJsonObject { put("error", "no command"); put("exitCode", 2); put("text", "The request has no command") })
        val args = body["args"] as? JsonObject ?: JsonObject(emptyMap())
        val project = (body["project"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { File(it) }
        val answer = commands.run(command, args, project)
        val result = if (answer.exitCode != 0 && "exitCode" !in answer.json) JsonObject(answer.json + ("exitCode" to JsonPrimitive(answer.exitCode))) else answer.json
        json(exchange, 200, result)
    }

    /** A zip that was dropped on the page: the body is its bytes, `name=` in the query its file name. */
    private fun upload(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") return json(exchange, 405, buildJsonObject { put("error", "POST only"); put("text", "POST only") })
        val bytes = exchange.requestBody.readNBytes(MAX_UPLOAD + 1)
        val answer = try {
            if (bytes.size > MAX_UPLOAD) wrongUse("The zip is over ${MAX_UPLOAD / 1024 / 1024} MB: that is no design zip")
            places.storeUpload(query(exchange)["name"] ?: "design.zip", bytes)
        } catch (e: FacetoolException) {
            buildJsonObject { put("error", e.message); put("exitCode", e.exitCode); put("text", e.message) }
        }
        json(exchange, 200, answer)
    }

    /** A file under the `.facetool/out/` folder of a project, and nothing else. */
    private fun file(exchange: HttpExchange) {
        val file = query(exchange)["path"]?.let { File(it).canonicalFile }
        if (file == null || !file.isFile || !file.path.replace(File.separatorChar, '/').contains("/.facetool/out/")) {
            return json(exchange, 404, buildJsonObject { put("error", "no such file under .facetool/out"); put("text", "no such file under .facetool/out") })
        }
        send(exchange, 200, contentType(file.name), file.readBytes())
    }

    /** The GUI page and its files. They hold no data of a project, so they need no token. */
    private fun page(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path.trimStart('/').ifEmpty { "index.html" }
            val bytes = if (path.contains("..")) null else gui(path)
            if (bytes == null) send(exchange, 404, "text/plain; charset=utf-8", "not found".toByteArray()) else send(exchange, 200, contentType(path), bytes)
        } finally {
            exchange.close()
        }
    }

    private fun json(exchange: HttpExchange, code: Int, body: JsonObject) = send(exchange, code, "application/json; charset=utf-8", body.toString().toByteArray())

    private fun send(exchange: HttpExchange, code: Int, type: String, bytes: ByteArray) {
        exchange.responseHeaders.add("Content-Type", type)
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun contentType(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js"   -> "text/javascript; charset=utf-8"
        "css"  -> "text/css; charset=utf-8"
        "png"  -> "image/png"
        "gif"  -> "image/gif"
        "json" -> "application/json; charset=utf-8"
        "xml"  -> "text/xml; charset=utf-8"
        "zip"  -> "application/zip"
        "apk"  -> "application/vnd.android.package-archive"
        else   -> "text/plain; charset=utf-8"
    }

    private fun query(exchange: HttpExchange): Map<String, String> =
        exchange.requestURI.rawQuery.orEmpty().split("&").filter { "=" in it }.associate { URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }

    companion object {

        /** The server listens only here, never on another address. */
        const val HOST = "127.0.0.1"
        const val TOKEN_HEADER = "X-Facetool-Token"

        /** A design zip must be under 320 KB for the watch; this only stops a wrong file. */
        const val MAX_UPLOAD = 20 * 1024 * 1024
    }
}
