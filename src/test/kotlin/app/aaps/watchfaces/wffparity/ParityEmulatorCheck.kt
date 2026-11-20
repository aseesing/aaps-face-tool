package app.aaps.watchfaces.wffparity

import com.google.common.truth.Truth.assertWithMessage
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.Locale

/**
 * The compare step of the parity suite (task `wffParityCompare`, run after `wffParityTest` with
 * `-PwffParityEmulator`): one test per queued face. It compares the picture that the emulator driver
 * took with the picture of our engine, region by region, checks the wake pictures, adds the result to
 * `result.txt` of the test, and writes `build/wff-parity/summary.html` with every result.
 *
 * Plain JUnit, no Robolectric: it only reads pictures, so all faces are compared in one JVM.
 */
@RunWith(Parameterized::class)
class ParityEmulatorCheck(private val name: String, private val dir: File) {

    @Test
    fun matchesOurEngine() {
        val job = ParityJob.read(dir)
        val report = StringBuilder()
        var verdict = "differs"
        try {
            val picture = File(dir, "emulator.png")
            assertWithMessage("no picture from the emulator, see build/wff-parity/emulator-*.log").that(picture.isFile).isTrue()
            val results = ParityCompare.compare(File(dir, "reference.png"), picture, job.regions, File(dir, "diff.png"))
            results.forEach { report.append("compare ").append(it).append('\n') }
            val failed = results.filter { !it.passed }.map { it.toString() }.toMutableList()
            if (job.wake) failed += wake(job, picture, report)
            assertWithMessage("the WFF face differs from ours, see %s:\n%s", File(dir, "diff.png"), failed.joinToString("\n")).that(failed).isEmpty()
            verdict = "same"
        } catch (e: AssertionError) {
            report.append("FAILED: ").append(e.message).append('\n')
            throw e
        } finally {
            File(dir, "result.txt").appendText(report.toString())
            synchronized(rows) { rows += Row(name, dir, verdict, report.toString()) }
        }
    }

    /** The wake must show something, and the parts of [ParityJob.stillInWake] must not change. Gives the failures. */
    private fun wake(job: ParityJob, rest: File, report: StringBuilder): List<String> {
        val frames = (0 until WffParityBase.WAKE_PICTURES).map { File(dir, "wake_$it.png") }.filter { it.isFile }
        if (frames.isEmpty()) return listOf("no wake pictures from the emulator")
        val failed = ArrayList<String>()
        val moved = frames.mapIndexed { i, frame -> ParityCompare.compare(rest, frame, listOf(WffParityBase.WHOLE), File(dir, "wake_diff_$i.png")).first().changedPercent }
        report.append("wake: ").append(moved.joinToString { String.format(Locale.ROOT, "%.2f %%", it) }).append(" changed against the face at rest\n")
        if (moved.none { it > WffParityBase.WAKE_SEEN_PERCENT }) failed += "the wake shows nothing on the emulator"
        for (region in job.stillInWake) {
            val changed = frames.mapIndexed { i, frame -> ParityCompare.compare(rest, frame, listOf(region), File(dir, "wake_still_$i.png")).first() }
            report.append("still in the wake: ").append(changed.joinToString { String.format(Locale.ROOT, "%.2f %%", it.changedPercent) }).append(" changed in ").append(region.name).append('\n')
            changed.filter { !it.passed }.forEach { failed += "${region.name} moves in the wake: $it" }
        }
        return failed
    }

    private class Row(val name: String, val dir: File, val verdict: String, val report: String)

    companion object {

        private val rows = ArrayList<Row>()

        /** The queued faces of this run, from the queue that the tests wrote. */
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun faces(): List<Array<Any>> {
            val queue = System.getProperty("wff.parity.queue")?.let { File(it) }
            assumeTrue("Runs only in the task wffParityCompare", queue != null)
            return queue!!.takeIf { it.isFile }?.readLines().orEmpty().filter { it.isNotBlank() }.distinct().map { line ->
                val dir = File(line)
                arrayOf<Any>("${dir.parentFile.name}.${dir.name}", dir)
            }
        }

        /** One page with every compared face of this run: the verdict, the numbers and the diff picture. */
        @AfterClass
        @JvmStatic
        fun summary() {
            val root = System.getProperty("wff.parity.queue")?.let { File(it).parentFile } ?: return
            val sorted = rows.sortedWith(compareBy({ it.verdict != "differs" }, { it.name }))
            val html = buildString {
                append("<!doctype html><meta charset=\"utf-8\"><title>WFF parity</title><style>body{font:14px sans-serif;background:#111;color:#ddd}")
                append("td{vertical-align:top;padding:6px;border-bottom:1px solid #333}.differs{color:#f66}.same{color:#6c6}img{width:600px}pre{white-space:pre-wrap;margin:0}</style>")
                append("<h1>WFF parity: ${rows.count { it.verdict == "same" }} same, ${rows.count { it.verdict == "differs" }} differ</h1><table>")
                for (row in sorted) {
                    val diff = File(row.dir, "diff.png").relativeTo(root).path
                    append("<tr><td><b>${row.name}</b><br><span class=\"${row.verdict}\">${row.verdict}</span><pre>${row.report.replace("<", "&lt;")}</pre></td>")
                    append("<td><img src=\"$diff\"></td></tr>")
                }
                append("</table>")
            }
            File(root, "summary.html").writeText(html)
        }
    }
}
