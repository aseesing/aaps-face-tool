package app.aaps.facetool.wffparity

import android.app.Application
import app.aaps.facetool.snapshot.SnapshotClock
import app.aaps.facetool.snapshot.SnapshotData
import app.aaps.facetool.snapshot.SnapshotEngine
import app.aaps.facetool.wff.WffApkBuilder
import app.aaps.facetool.wff.WffBaker
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime

/**
 * The base of the parity tests: each test is a small design with one feature of the layer engine or
 * of the classic face, made into a Watch Face Format face, and compared with the face our own engine
 * draws. See `docs/wff-parity.md`.
 *
 * Two steps:
 * - this test (a JVM, several run side by side): the design is converted, the face must pass the
 *   Watch Face Push validator, and its lint must say what the test expects (a part that WFF cannot
 *   show must be said to be left out);
 * - with `-PwffParityEmulator`: the face is queued ([ParityJob]). One driver shows the queued faces
 *   on the emulator, one after the other, while the tests still run, and [ParityEmulatorCheck]
 *   compares its pictures with ours, region by region.
 *
 * Output per test in `build/wff-parity/<class>/<test>/`: the face folder of the harness, `lint.txt`,
 * `reference.png` (our engine), `emulator.png`, `diff.png` (ours, WFF, the differences) and
 * `result.txt`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// xhdpi: the density of a round watch. The engine is instrumented so that its clock is the frozen one.
@Config(sdk = [35], application = Application::class, instrumentedPackages = ["app.aaps.wear"], qualifiers = "xhdpi")
abstract class WffParityBase {

    @get:Rule
    val testName = TestName()

    private val clock = SnapshotClock()

    @Before
    fun startClock() {
        assumeTrue("Runs only in the task wffParityTest, see docs/wff-parity.md", System.getProperty("wff.parity") == "true")
        clock.start()
    }

    @After
    fun stopClock() = clock.stop()

    /**
     * Converts [design] and checks it: the face must be valid and the lint must say what [lint] says.
     * With the emulator (`-PwffParityEmulator`) the face is then queued for the one emulator driver;
     * the compare step checks [regions], the wake and [stillInWake] when the driver has its pictures.
     *
     * @param lint texts the lint must have (for example "omit" next to a part WFF cannot show)
     * @param regions how much each part of the face may differ from our engine on the emulator
     * @param wake a test of the wake-up animation: also takes pictures during a wake on the emulator
     * @param stillInWake parts that must look the same in every wake picture as at rest (a part that
     *   WFF must not show or move in the wake)
     * @param onEmulator also on the emulator when the face is only one picture of our engine: for the
     *   test that proves such a picture is drawn one to one
     * @param editFace changes the `watchface.xml` the converter wrote before the face is packed: for a
     *   test that needs a part the converter does not write by itself (see [WffApkBuilder])
     */
    protected fun check(
        design: ParityDesign, lint: List<String> = emptyList(), regions: List<ParityCompare.Region> = listOf(WHOLE), wake: Boolean = false,
        stillInWake: List<ParityCompare.Region> = emptyList(), onEmulator: Boolean = false, editFace: (String) -> String = { it }
    ) {
        val dir = File("build/wff-parity/${javaClass.simpleName}/${testName.methodName}")
        // The last pictures of the emulator stay: the driver keeps them when the face did not change
        val kept = dir.listFiles { f -> f.name == "emulator.png" || f.name == "emulator.key" || f.name.matches(Regex("wake_\\d+\\.png")) }.orEmpty().associate { it.name to it.readBytes() }
        dir.deleteRecursively()
        dir.mkdirs()
        val app = RuntimeEnvironment.getApplication()
        clock.setTo(SnapshotClock.epochMs(MOMENT))
        val cache = File(System.getProperty("user.home"), ".cache/aaps-wff")
        val pack = File(cache, "pack/target/release/pack-cli").takeIf { it.isFile } ?: File(cache, "pack/target/release/pack-cli.exe")
        check(pack.isFile) { "Pack is not built: run tools/build-pack.sh (docs/wff-apk.md)" }
        // The phone writes its texts in the language of the watch (US English, see SnapshotClock), as a
        // Wear OS watch takes the language of its phone. A phone in another language would make the
        // glucose text of the slot ("6,7") differ from what glucose() writes on the watch ("6.7").
        val baker = WffBaker(SnapshotEngine(app), clock, ParityCompare.SIZE, SnapshotData(weather = "clear", watchBattery = WATCH_BATTERY, decimalSign = '.'))
        val cwf = design.cwf()
        // The values and our picture first, at the fixed moment; baking then moves the clock on
        val scene = File(app.cacheDir, "scene").apply { deleteRecursively() }
        baker.scene(cwf, scene, app.filesDir) { name -> app.getSharedPreferences(name, 0).all }
        val sceneAt = SnapshotClock.epochMs(MOMENT) + WffBaker.REST_LEAD_MS
        val result = WffApkBuilder(app, baker, CLIENT, pack, File(cache, "dev-key.pem"), editFace).build(cwf, dir)
        // The builder starts with an empty folder too: the kept pictures go back after it
        kept.forEach { (name, bytes) -> File(dir, name).writeBytes(bytes) }
        scene.copyRecursively(File(dir, "scene"), overwrite = true)
        File(dir, "scene/reference.png").copyTo(File(dir, "reference.png"), overwrite = true)
        val report = StringBuilder(result.lint)
        try {
            assertWithMessage("no valid face, see %s", File(dir, "lint.txt")).that(result.token).isNotNull()
            for (text in lint) assertWithMessage("the lint should say %s:\n%s", text, result.lint).that(result.lint).contains(text)
            val queue = System.getProperty("wff.parity.queue")
            when {
                queue == null                                                   -> report.append("emulator: not run\n")
                // The face is one picture that our engine drew: the emulator would only show that picture again
                !wake && !onEmulator && onlyOneBakedPicture(result.lint)        -> report.append("emulator: not needed, the face is one picture drawn by our engine\n")
                !wake && regions.isEmpty()                                      -> report.append("emulator: not needed, nothing to compare\n")
                else                                                            -> {
                    // A face with frames plays its wake when it is shown: its picture at rest comes after the wake
                    val frames = "PartAnimatedImage" in faceXml()
                    val wakeMs = JSONObject(cwf.json).optJSONObject("animation")?.optLong("durationMs", DEFAULT_WAKE_MS) ?: 0L
                    val restAfter = if (frames) (wakeMs / 1000 + 2).toInt() else 0
                    ParityJob(dir.absoluteFile, sceneAt, regions, wake, stillInWake, restAfter).apply { write() }.enqueue(File(queue))
                    report.append("emulator: queued\n")
                }
            }
        } catch (e: AssertionError) {
            report.append("FAILED: ").append(e.message).append('\n')
            throw e
        } finally {
            File(dir, "result.txt").writeText(report.toString())
        }
    }

    /** The folder of the running test. */
    protected fun dir(): File = File("build/wff-parity/${javaClass.simpleName}/${testName.methodName}")

    /** The `watchface.xml` the converter wrote for the running test. */
    protected fun faceXml(): String = File(dir(), "face/res/raw/watchface.xml").readText()

    /** True when every part of the face is baked into static picture 0, and the face has nothing else. */
    private fun onlyOneBakedPicture(lint: String): Boolean {
        val lines = lint.lines()
        val items = lines.drop(1).takeWhile { !it.startsWith("Slots") }
        return items.isNotEmpty() && items.all { " bake " in it && "picture 0" in it } &&
            lines.any { it.startsWith("Slots 0 /") && "static pictures 1" in it } && lines.any { it.startsWith("Pictures: 1 static, 0 loops") } &&
            lines.none { it.startsWith(" ! ") }
    }

    companion object {

        /** The moment of every test: a weekday morning, the hands apart, the moon past first quarter. */
        val MOMENT: LocalDateTime = LocalDateTime.of(2026, 11, 17, 10, 8)

        const val CLIENT = "info.nightscout.androidaps"

        /** The battery of the watch in percent, in our engine and on the emulator (`tools/wff-parity-emulator.sh`). */
        const val WATCH_BATTERY = 64

        /** The whole face, for a design whose parts are all baked or drawn the same way. */
        val WHOLE = ParityCompare.Region("whole face", 1.0)

        /** A wake must change at least this share of the face to count as seen. */
        const val WAKE_SEEN_PERCENT = 0.3

        /** The length of a wake when the design sets none, as the engine (`WakeAnimationSpec`). */
        const val DEFAULT_WAKE_MS = 6000L

        /** Pictures taken during a wake on the emulator. */
        const val WAKE_PICTURES = 4
    }
}
