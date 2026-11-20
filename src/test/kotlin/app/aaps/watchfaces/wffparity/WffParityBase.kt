package app.aaps.watchfaces.wffparity

import android.app.Application
import app.aaps.watchfaces.snapshot.SnapshotClock
import app.aaps.watchfaces.snapshot.SnapshotEngine
import app.aaps.watchfaces.wff.WffApkBuilder
import app.aaps.watchfaces.wff.WffBaker
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
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * The base of the parity tests: each test is a small design with one feature of the layer engine or
 * of the classic face, made into a Watch Face Format face, and compared with the face our own engine
 * draws. See `docs/wff-parity.md`.
 *
 * Two parts, as the test is run:
 * - always: the design is converted, the face must pass the Watch Face Push validator, and its lint
 *   must say what the test expects (a part that WFF cannot show must be said to be left out);
 * - with `-PwffParityEmulator` (an emulator of Wear OS 6 must run): the face is shown on the emulator
 *   with the same values at the same moment, and the pictures are compared per region.
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
     * Converts [design] and checks it.
     *
     * @param lint texts the lint must have (for example "omit" next to a part WFF cannot show)
     * @param regions how much each part of the face may differ from our engine on the emulator
     * @param wake a test of the wake-up animation: also takes pictures during a wake on the emulator
     */
    protected fun check(design: ParityDesign, lint: List<String> = emptyList(), regions: List<ParityCompare.Region> = listOf(WHOLE), wake: Boolean = false) {
        val dir = File("build/wff-parity/${javaClass.simpleName}/${testName.methodName}").apply { deleteRecursively(); mkdirs() }
        val app = RuntimeEnvironment.getApplication()
        clock.setTo(SnapshotClock.epochMs(MOMENT))
        val cache = File(System.getProperty("user.home"), ".cache/aaps-wff")
        val pack = File(cache, "pack/target/release/pack-cli").takeIf { it.isFile } ?: File(cache, "pack/target/release/pack-cli.exe")
        check(pack.isFile) { "Pack is not built: run tools/build-pack.sh (docs/wff-apk.md)" }
        val baker = WffBaker(SnapshotEngine(app), clock, ParityCompare.SIZE)
        val cwf = design.cwf()
        // The values and our picture first, at the fixed moment; baking then moves the clock on
        val scene = File(app.cacheDir, "scene").apply { deleteRecursively() }
        baker.scene(cwf, scene, app.filesDir) { name -> app.getSharedPreferences(name, 0).all }
        val sceneAt = SnapshotClock.epochMs(MOMENT) + WffBaker.REST_LEAD_MS
        val result = WffApkBuilder(app, baker, CLIENT, pack, File(cache, "dev-key.pem")).build(cwf, dir)
        scene.copyRecursively(File(dir, "scene"), overwrite = true)
        File(dir, "scene/reference.png").copyTo(File(dir, "reference.png"), overwrite = true)
        val report = StringBuilder(result.lint)
        try {
            assertWithMessage("no valid face, see %s", File(dir, "lint.txt")).that(result.token).isNotNull()
            for (text in lint) assertWithMessage("the lint should say %s:\n%s", text, result.lint).that(result.lint).contains(text)
            if (System.getProperty("wff.parity.emulator") != "true") {
                report.append("emulator: not run\n")
                return
            }
            emulator(dir, sceneAt, wake)
            val results = ParityCompare.compare(File(dir, "reference.png"), File(dir, "emulator.png"), regions, File(dir, "diff.png"))
            results.forEach { report.append("compare ").append(it).append('\n') }
            if (wake) wakeCheck(dir, report)
            val failed = results.filter { !it.passed }
            assertWithMessage("the WFF face differs from ours, see %s:\n%s", File(dir, "diff.png"), failed.joinToString("\n")).that(failed).isEmpty()
        } catch (e: AssertionError) {
            report.append("FAILED: ").append(e.message).append('\n')
            throw e
        } finally {
            File(dir, "result.txt").writeText(report.toString())
        }
    }

    /** Shows the face of [dir] on the emulator with the values of its scene, at [atMs], and takes its picture. */
    private fun emulator(dir: File, atMs: Long, wake: Boolean) {
        val env = mutableMapOf("WFF_TIME" to atMs.toString(), "WFF_TZ" to SnapshotClock.ZONE, "WFF_WAIT" to (System.getProperty("wff.parity.wait") ?: "20"))
        if (wake) env["WFF_WAKE"] = WAKE_PICTURES.toString()
        val output = run(listOf("sh", "tools/wff-emulator.sh", dir.path), env)
        check(File(dir, "emulator.png").isFile) { "No picture of the emulator:\n$output" }
    }

    /** Pictures during a wake: the wake must show something that the face at rest does not. */
    private fun wakeCheck(dir: File, report: StringBuilder) {
        val rest = File(dir, "emulator.png")
        val frames = (0 until WAKE_PICTURES).map { File(dir, "wake_$it.png") }.filter { it.isFile }
        val moved = frames.map { ParityCompare.compare(rest, it, listOf(WHOLE), File(dir, "wake_diff_${frames.indexOf(it)}.png")).first().changedPercent }
        report.append("wake: ").append(moved.joinToString { String.format("%.2f %%", it) }).append(" changed against the face at rest\n")
        assertWithMessage("the wake shows nothing on the emulator, see %s", dir).that(moved.any { it > WAKE_SEEN_PERCENT }).isTrue()
    }

    private fun run(command: List<String>, env: Map<String, String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).apply { environment().putAll(env) }.start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(10, TimeUnit.MINUTES)
        return output
    }

    companion object {

        /** The moment of every test: a weekday morning, the hands apart, the moon past first quarter. */
        val MOMENT: LocalDateTime = LocalDateTime.of(2026, 11, 17, 10, 8)

        const val CLIENT = "info.nightscout.androidaps"

        /** The whole face, for a design whose parts are all baked or drawn the same way. */
        val WHOLE = ParityCompare.Region("whole face", 1.0)

        /** A wake must change at least this share of the face to count as seen. */
        const val WAKE_SEEN_PERCENT = 0.3

        /** Pictures taken during a wake on the emulator. */
        const val WAKE_PICTURES = 4
    }
}
