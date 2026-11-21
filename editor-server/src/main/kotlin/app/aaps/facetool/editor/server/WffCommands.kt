package app.aaps.facetool.editor.server

import android.app.Application
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.facetool.editor.model.ExitCodes
import app.aaps.facetool.editor.model.FacetoolException
import app.aaps.facetool.editor.model.ProjectFolder
import app.aaps.facetool.editor.model.Scenes
import app.aaps.facetool.editor.model.wrongUse
import app.aaps.facetool.snapshot.SnapshotClock
import app.aaps.facetool.snapshot.SnapshotEngine
import app.aaps.facetool.wff.WffApkBuilder
import app.aaps.facetool.wff.WffBaker
import app.aaps.facetool.wff.WffFaceWriter
import app.aaps.facetool.wff.WffFaceZip
import app.aaps.facetool.wff.WffResources
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * What `wff build` made for one design and scene (design 11.1): the face folders of WFF 4 and 2, the
 * lint of each, and `data.zip`, in the folder `.facetool/out/wff/build` of the project.
 *
 * @param built false when the design cannot be a face; the lint says why
 */
class WffBuild(val dir: File, val built: Boolean, val fromCache: Boolean, val seconds: Double) {

    fun face(version: Int): File = File(dir, if (version == WffFaceWriter.VERSION_4) "face" else "face-wff$version")

    fun xml(version: Int): File = File(face(version), WffResources.WATCH_FACE_XML)

    fun lint(version: Int): String = File(dir, if (version == WffFaceWriter.VERSION_4) "lint.txt" else "lint-wff$version.txt").takeIf { it.isFile }?.readText().orEmpty()

    val dataZip: File get() = File(dir, "data.zip")
}

/**
 * The WFF commands that need no player (design 8.2, step 9): `wff build`, `wff plan`, `wff xml`, and
 * the WFF part of the lint. The face is made by the harness of this repository as it is
 * (`WffApkBuilder.convert`: plan, bake with the engine, write), without Pack.
 */
class WffCommands(
    private val app: Application, private val engine: SnapshotEngine, private val clock: EngineClock, private val loader: DesignLoader,
    private val scenes: SceneReader, private val facts: ServerFacts, private val checkout: File?
) {

    private var builds = 0

    /** The face folders of [project] for its scene: made new when the design, the scene or the code changed. */
    fun build(project: ProjectFolder, cwf: CwfData, scene: JsonObject): WffBuild {
        val values = scenes.read(scene)
        val dir = File(project.outDir("wff"), "build")
        val key = "${project.hash()}|${values.feedKey}|${values.size}|${facts.aapsCommit}|${facts.buildStamp}"
        val keyFile = File(dir, "key.txt")
        if (keyFile.isFile && keyFile.readText() == key) return WffBuild(dir, File(dir, "face/${WffResources.WATCH_FACE_XML}").isFile, fromCache = true, seconds = 0.0)
        val started = System.nanoTime()
        clock.moveTo(values.timeMs)
        // Each build gets face names of its own: the engine allows one face per name in a process
        val baker = WffBaker(engine, clock.snapshotClock, values.size, values.data, facePrefix = "wffbuild${builds++}_")
        val cache = File(System.getProperty("user.home"), ".cache/aaps-wff")
        val builder = WffApkBuilder(app, baker, CLIENT, File(cache, "pack/target/release/pack-cli"), File(cache, "dev-key.pem"))
        val converted = builder.convert(cwf, dir)
        if (converted.face != null) {
            val second = builder.faceFolder(converted, File(dir, "face-wff2"), CLIENT, WffFaceWriter.VERSION_2)
            // As the face zip says it: what WFF 2 leaves out beyond WFF 4
            val first = File(dir, "lint.txt").readText()
            val extra = second.notWritten.map { " ! WFF 2 (Wear OS 5), not written: $it\n" }.filter { it.removePrefix(" ! WFF 2 (Wear OS 5), not written: ").trim() !in first }
            File(dir, "lint-wff2.txt").writeText(first + extra.joinToString(""))
            File(dir, "data.zip").writeBytes(builder.dataZip(converted))
        }
        keyFile.writeText(key)
        return WffBuild(dir, converted.face != null, fromCache = false, seconds = (System.nanoTime() - started) / 1e9)
    }

    private fun build(project: ProjectFolder, args: Args): WffBuild = build(project, loader.load(project), Scenes.forCommand(project, args.json))

    private fun version(args: Args): Int = when (args.text("version") ?: "4") {
        "4"  -> WffFaceWriter.VERSION_4
        "2"  -> WffFaceWriter.VERSION_2
        else -> wrongUse("--version is 4 or 2")
    }

    fun buildCommand(project: ProjectFolder, args: Args): JsonObject {
        val version = version(args)
        val build = build(project, args)
        return buildJsonObject {
            put("built", build.built)
            put("dir", build.dir.absolutePath)
            if (build.built) {
                put("face", build.face(version).absolutePath)
                put("xml", build.xml(version).absolutePath)
                put("dataZip", build.dataZip.absolutePath)
            }
            put("fromCache", build.fromCache)
            put("seconds", build.seconds)
            put("aapsCommit", facts.aapsCommit ?: "unknown")
            put("text", if (!build.built) "The design cannot be a WFF face:\n${build.lint(version)}" else
                "The WFF $version face folder is ${build.face(version).path} (${if (build.fromCache) "from the cache" else "built in %.1f s".format(build.seconds)}). facetool wff plan shows the lint.")
        }
    }

    fun plan(project: ProjectFolder, args: Args): JsonObject {
        val version = version(args)
        val build = build(project, args)
        val lint = build.lint(version)
        return buildJsonObject {
            put("version", version)
            put("built", build.built)
            put("lines", JsonArray(lint.lines().filter { it.isNotBlank() }.map { JsonPrimitive(it) }))
            put("text", lint.trimEnd())
        }
    }

    fun xml(project: ProjectFolder, args: Args): JsonObject {
        val version = version(args)
        val build = build(project, args)
        if (!build.built) wrongUse("The design cannot be a WFF face:\n${build.lint(version)}")
        return buildJsonObject {
            put("version", version)
            put("file", build.xml(version).absolutePath)
            put("text", build.xml(version).readText())
        }
    }

    /**
     * `wff apk` (design section 13): the face as an APK, made by the harness as it is: baked new, packed
     * with Pack and checked by the Watch Face Push validator. Only to check the converter on an emulator.
     */
    fun apk(project: ProjectFolder, args: Args): JsonObject {
        val client = args.text("client") ?: CLIENT
        val cache = File(System.getProperty("user.home"), ".cache/aaps-wff")
        val pack = File(cache, "pack/target/release/pack-cli").takeIf { it.isFile } ?: File(cache, "pack/target/release/pack-cli.exe")
        // facetool never downloads or builds Pack itself
        if (!pack.isFile) wrongUse("Pack is not built: run sh tools/build-pack.sh once (docs/wff-apk.md), then try again")
        val cwf = loader.load(project)
        val values = scenes.read(Scenes.forCommand(project, args.json))
        val dir = File(project.outDir("wff"), "apk")
        // The scene first, as the harness does it: the stores of the wear app with the values, and
        // reference.png, our engine's face at the time of the scene (it is drawn a rest lead after the values are fed)
        clock.moveTo(values.timeMs - WffBaker.REST_LEAD_MS)
        val baker = WffBaker(engine, clock.snapshotClock, values.size, values.data, facePrefix = "wffbuild${builds++}_")
        val sceneDir = File(app.cacheDir, "facetool-scene").apply { deleteRecursively() }
        baker.scene(cwf, sceneDir, app.filesDir) { name -> app.getSharedPreferences(name, 0).all }
        val faceZip = if (args.flag("zip")) WffApkBuilder.FaceZip(WffFaceZip.APP_IDS, File(project.outDir("wff"), WffFaceZip.nameOf("${project.dir.name}.zip"))) else null
        val result = WffApkBuilder(app, baker, client, pack, File(cache, "dev-key.pem")).build(cwf, dir, faceZip)
        sceneDir.copyRecursively(File(dir, "scene"), overwrite = true)
        val apk = result.apk
        return buildJsonObject {
            put("built", apk != null)
            put("valid", result.token != null)
            apk?.let { put("apk", it.absolutePath) }
            result.token?.let { put("token", it) }
            result.faceZip?.let { put("faceZip", it.absolutePath) }
            put("dir", dir.absolutePath)
            put("client", client)
            if (result.token == null) put("exitCode", 1)
            put("text", when {
                apk == null          -> "No face APK:\n${result.lint}"
                result.token == null -> "The face APK ${apk.path} is built, but the validator does not pass it: see ${File(dir, "validator.txt").path}"
                else                 -> "The face APK is ${apk.path} (${apk.length() / 1024} KB), for the wear app $client. The validator passes it; its token is in ${File(dir, "token.txt").path}." +
                    (result.faceZip?.let { "\nThe face zip is ${it.path}." } ?: "") + "\nIt is only for a Wear OS emulator (tools/wff-emulator.sh). Never install it on a real watch, and do not share faces of other people's designs."
            })
        }
    }

    /**
     * `wff emulator` (design section 13): makes the face APK, shows it on a running Wear OS emulator
     * with `tools/wff-emulator.sh` and gives the emulator's picture next to our engine's. It never
     * touches a real watch: the device must say it is an emulator, here and in the script.
     */
    fun emulator(project: ProjectFolder, args: Args): JsonObject {
        val script = checkout?.let { File(it, "tools/wff-emulator.sh") }?.takeIf { it.isFile } ?: wrongUse("tools/wff-emulator.sh was not found: the server does not know its checkout")
        val adb = File(System.getenv("ANDROID_HOME") ?: "${System.getProperty("user.home")}/Library/Android/sdk", "platform-tools/adb").takeIf { it.canExecute() }?.path ?: "adb"
        fun adb(vararg words: String): String = runCatching {
            ProcessBuilder(listOf(adb) + words).redirectErrorStream(true).start().let { process -> process.inputStream.bufferedReader().readText().also { process.waitFor(20, TimeUnit.SECONDS) } }.trim()
        }.getOrDefault("")
        val running = adb("devices").lines().mapNotNull { Regex("^(\\S+)\\s+device$").find(it.trim())?.groupValues?.get(1) }
        val wantedApi = if ((args.text("version") ?: "4") == "2") 34 else 36
        val device = args.text("device") ?: running.firstOrNull { it.startsWith("emulator-") && (adb("-s", it, "shell", "getprop", "ro.build.version.sdk").toIntOrNull() ?: 0) >= wantedApi }
            ?: wrongUse("No Wear OS emulator of API $wantedApi or newer runs. Start one (docs/wff-parity.md); facetool does not start it. Running: ${running.joinToString(", ").ifEmpty { "none" }}")
        if (device !in running) wrongUse("The device $device does not run. Running: ${running.joinToString(", ").ifEmpty { "none" }}")
        // Never a real watch or phone
        if (adb("-s", device, "shell", "getprop", "ro.kernel.qemu") != "1") wrongUse("$device is not an emulator. facetool never installs anything on a real watch or phone")
        if ((args.text("version") ?: "4") != "4") wrongUse("wff emulator shows the WFF 4 face. For WFF 2 make the face zip (wff apk --zip) and use tools/wff-emulator.sh by hand")
        val apk = apk(project, args)
        if ((apk["valid"] as? JsonPrimitive)?.content != "true") return apk
        val dir = File((apk.getValue("dir") as JsonPrimitive).content)
        val values = scenes.read(Scenes.forCommand(project, args.json))
        val command = listOfNotNull("sh", script.path, dir.path, args.text("wear-apk"))
        val process = ProcessBuilder(command).directory(checkout).redirectErrorStream(true).apply {
            environment()["WFF_DEVICE"] = device
            environment()["WFF_CLIENT"] = args.text("client") ?: CLIENT
            environment()["WFF_TIME"] = values.timeMs.toString()
            environment()["WFF_TZ"] = SnapshotClock.ZONE
            if (args.flag("wake")) environment()["WFF_WAKE"] = "5"
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        val ended = process.waitFor(40, TimeUnit.MINUTES)
        File(dir, "emulator.log").writeText(output)
        val picture = File(dir, "emulator.png")
        val compare = File(dir, "compare.png")
        if (!ended || process.exitValue() != 0 || !picture.isFile) throw FacetoolException("The emulator script failed, see ${File(dir, "emulator.log").path}:\n${output.lines().takeLast(8).joinToString("\n")}", ExitCodes.SERVER_FAILED)
        return buildJsonObject {
            put("device", device)
            put("file", compare.absolutePath)
            put("emulator", picture.absolutePath)
            put("reference", File(dir, "scene/reference.png").absolutePath)
            File(dir, "wake.png").takeIf { it.isFile }?.let { put("wake", it.absolutePath) }
            put("dir", dir.absolutePath)
            put("text", "Shown on $device. ${compare.path}: left our engine, right the emulator. The emulator's picture alone: ${picture.path}")
        }
    }

    /** The WFF lint of both versions as lint findings: ` !! ` is an error, ` ! ` a warning, a note is information. */
    fun lint(project: ProjectFolder, cwf: CwfData): List<LintFinding> {
        val build = build(project, cwf, project.scene())
        val result = ArrayList<LintFinding>()
        for (version in listOf(WffFaceWriter.VERSION_4, WffFaceWriter.VERSION_2)) {
            for (line in build.lint(version).lines()) {
                val text = line.trim()
                val severity = when {
                    text.startsWith("!!")    -> "error"
                    text.startsWith("!")     -> "warning"
                    text.startsWith("note:") -> "info"
                    else                     -> continue
                }
                // The planner lists what a part reads in an order that changes from run to run: sort it
                val body = READS.replace(text.removePrefix("!!").removePrefix("!").removePrefix("note:").trim()) { match ->
                    "reads " + match.groupValues[1].split(",").map { it.trim() }.sorted().joinToString(", ") + ":"
                }
                result += LintFinding(severity, "wff$version", Regex("^L(\\d+)").find(body)?.value ?: "", body)
            }
            if (!build.built) break
        }
        return result
    }

    private companion object {

        /** The wear app the preview face is made for. The player does not need it; `wff apk` takes `--client`. */
        const val CLIENT = "info.nightscout.androidaps"
        val READS = Regex("reads ([a-z_, ]+):")
    }
}
