package app.aaps.facetool.editor.server

import android.app.Application
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.facetool.editor.model.CommandLines
import app.aaps.facetool.editor.model.CommandTable
import app.aaps.facetool.editor.model.EditorConfig
import app.aaps.facetool.editor.model.LocalCommands
import app.aaps.facetool.editor.model.ProcessGitReader
import app.aaps.facetool.snapshot.SnapshotEngine
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.SecureRandom
import java.time.Clock

/**
 * The parts of the render server, put together (design section 5). `EditorServerMain` makes one inside
 * a Robolectric test that does not end; the tests make one without HTTP and call [commands].
 *
 * @param clock the frozen clock of the engine
 * @param checkout the `aaps-face-tool` checkout, as the CLI names it, or null in a test
 * @param editingRoot where `open` and `new` make project folders when the page gives no folder
 * @param designsDir the folder with the design zips the page offers to open, or null
 * @param guiDir a folder with the files of the page, to try a change of the page without a new build
 * @param renderThread in a test a direct one: a command draws on the calling thread, there is no render loop
 */
class EditorServer(
    app: Application, logger: AAPSLogger, clock: EngineClock, private val checkout: String?, aapsDir: File?, editingRoot: File, cacheRoot: File, designsDir: File?, private val guiDir: File? = null,
    private val renderThread: RenderThread = RenderThread()
) {

    private val table = CommandTable.standard()
    private val wallClock = Clock.systemUTC()
    private val facts = ServerFacts.of(checkout?.let { File(it) }, aapsDir, System.currentTimeMillis())
    private val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val life = ServerLife(cacheRoot, checkout ?: "test", token, facts, { System.currentTimeMillis() })
    private val events = Events()
    private val places = ProjectPlaces(editingRoot, designsDir, RecentProjects(File(cacheRoot, "recent.json")))
    private val watcher = FolderWatcher { dir -> events.send("changed", buildJsonObject { put("project", dir.path) }, dir) }
    private val pictures = Pictures()
    private val loader = DesignLoader()
    private val engine = SnapshotEngine(app, logger)
    private val preview = EnginePreview(app, engine, clock)
    private val wff = WffCommands(app, engine, clock, loader, SceneReader(), facts, checkout?.let { File(it) })
    private val pictureCommands = PictureCommands(preview, loader, SceneReader(), pictures, facts)

    private val player = PlayerCommands(app, wff, preview, loader, SceneReader(), pictures, pictureCommands)
    private val config = EditorConfig(editingRoot)
    private val lint = LintCommands(preview, loader, SceneReader(), pictureCommands, EngineManual(aapsDir?.let { File(it, "docs/watchface-animation.html") }), config, wallClock, wffLint = { project, cwf -> wff.lint(project, cwf) + player.lint(project, cwf) })
    private val local = LocalCommands(config, wallClock, table, engineCheck = lint::engineCheck)
    private val sheets = SheetCommands(preview, loader, SceneReader(), pictures, pictureCommands, local, wallClock, ProcessGitReader())

    val commands = ServerCommands(
        table, local, renderThread,
        engine = mapOf(
            "render" to { project, args -> pictureCommands.render(project!!, args) },
            "scene" to { project, args -> pictureCommands.scene(project, args) },
            "wake" to { project, args -> sheets.wake(project!!, args) },
            "options" to { project, args -> sheets.options(project!!, args) },
            "looks" to { project, args -> sheets.looks(project!!, args) },
            "pick" to { project, args -> sheets.pick(project!!, args) },
            "snapshot" to { project, args -> sheets.snapshot(project!!, args) },
            "diff" to { project, args -> sheets.diff(project, args) },
            "lint" to { project, args -> lint.lint(project!!, args) },
            "expand" to { project, args -> lint.expand(project!!, args) },
            "keys" to { _, args -> lint.keys(args) },
            "manual" to { _, args -> lint.manual(args) },
            "wff build" to { project, args -> wff.buildCommand(project!!, args) },
            "wff plan" to { project, args -> wff.plan(project!!, args) },
            "wff xml" to { project, args -> wff.xml(project!!, args) },
            "wff emulator" to { project, args -> wff.emulator(project!!, args) },
            "wff apk" to { project, args -> wff.apk(project!!, args) },
            "wff render" to { project, args -> player.render(project!!, args) },
            "wff wake" to { project, args -> player.wake(project!!, args) },
            "wff parts" to { project, args -> player.parts(project!!, args) },
            "wff compare" to { project, args -> player.compare(project!!, args) }
        ),
        life = mapOf(
            "status" to { _, _ -> life.status() },
            "stop" to { _, _ -> life.stop() }
        )
    ).also { commands ->
        commands.onCommand = { command, args, project, answer ->
            val line = runCatching { CommandLines.format(table.find(command)!!, args) }.getOrDefault("facetool $command")
            life.commandSeen(line)
            events.send("log", buildJsonObject {
                put("line", line)
                put("exitCode", answer.exitCode)
                put("text", (answer.json["text"] as? JsonPrimitive)?.content ?: "")
            }, project)
        }
    }

    init {
        // The page of a project listens: watch its folder from then on, and keep it as a recent project
        events.onListen = { dir ->
            watcher.watch(dir)
            places.opened(dir)
        }
    }

    private val api = HttpApi(commands, table, token, facts, events, places, ::guiFile, life::requestSeen)

    /** A file of the page: from [guiDir] when it is given and has the file, else from the build. */
    private fun guiFile(path: String): ByteArray? =
        guiDir?.let { File(it, path) }?.takeIf { it.isFile }?.readBytes() ?: javaClass.classLoader?.getResourceAsStream("gui/$path")?.use { it.readBytes() }

    /** The token every API request must carry. */
    val apiToken: String get() = token

    /** Starts HTTP and writes `server.json`. Gives the port. */
    fun start(): Int {
        api.start()
        life.started(api.port)
        return api.port
    }

    /** Stops HTTP and removes `server.json`. */
    fun stop() {
        watcher.stop()
        life.ended()
        api.stop()
    }

    /** Starts HTTP, writes `server.json`, and runs render jobs on the calling thread until the server stops. */
    fun serve(startedAtMs: Long) {
        start()
        println("facetool server ready on ${HttpApi.HOST}:${api.port} after ${System.currentTimeMillis() - startedAtMs} ms, AAPS ${facts.aapsCommit ?: "unknown"}")
        try {
            renderThread.loop { life.isRunning }
        } finally {
            stop()
            println("facetool server stopped")
        }
    }
}
