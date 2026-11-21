package app.aaps.facetool.editor.cli

import app.aaps.facetool.editor.model.AndroidJson
import app.aaps.facetool.editor.model.CommandAnswer
import app.aaps.facetool.editor.model.ExitCodes
import app.aaps.facetool.editor.model.FacetoolException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Talks to the render server over HTTP (design section 9). Only `127.0.0.1`. Every request
 * carries the token of `server.json` in the header `X-Facetool-Token`.
 */
class ServerClient(private val info: ServerInfo, private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {

    val baseUrl: String get() = "http://127.0.0.1:${info.port}"

    /** The address of the editor page, with the token. */
    val guiUrl: String get() = "$baseUrl/?token=${info.token}"

    /** True when the server answers `GET /api/ping` with `{"ok": true}`. */
    fun ping(): Boolean = try {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/ping")).header(TOKEN_HEADER, info.token).timeout(Duration.ofSeconds(2)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() == 200 && (AndroidJson.parseObject(response.body(), "ping")["ok"] as? JsonPrimitive)?.content == "true"
    } catch (_: IOException) {
        false
    } catch (_: FacetoolException) {
        false
    }

    /**
     * Runs one command of the table in the server: `POST /api/run` with
     * `{"command": ..., "args": {...}, "project": "/abs/path"}`. The answer body is the JSON answer
     * of the command, with the key `exitCode` when it is not 0.
     */
    fun run(command: String, args: JsonObject, project: File?): CommandAnswer {
        val body = buildJsonObject {
            put("command", command)
            put("args", args)
            project?.let { put("project", it.absolutePath) }
        }
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/run"))
            .header(TOKEN_HEADER, info.token)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofMinutes(30))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw FacetoolException("The server did not answer '$command': ${e.message ?: e.javaClass.simpleName}", ExitCodes.SERVER_FAILED)
        }
        val json = try {
            AndroidJson.parseObject(response.body(), "The answer of the server")
        } catch (_: FacetoolException) {
            throw FacetoolException("The server gave no JSON for '$command' (HTTP ${response.statusCode()}): ${response.body().take(300)}", ExitCodes.SERVER_FAILED)
        }
        val exitCode = (json["exitCode"] as? JsonPrimitive)?.content?.toIntOrNull() ?: if (response.statusCode() == 200) ExitCodes.OK else ExitCodes.SERVER_FAILED
        return CommandAnswer(if ("text" in json) json else JsonObject(json + ("text" to JsonPrimitive(json.toString()))), exitCode)
    }

    companion object {

        const val TOKEN_HEADER = "X-Facetool-Token"
    }
}
