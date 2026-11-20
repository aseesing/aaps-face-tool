package app.aaps.facetool.wffparity

import java.io.File
import java.io.FileOutputStream
import java.util.Properties

/**
 * What the emulator step does with the face of one test, written by the test (the JVM step) into
 * `parity.job` of its folder. The test folder is also put in the queue, which the one emulator driver
 * (`tools/wff-parity-emulator.sh`) works through while the other tests still run. The compare step
 * ([ParityEmulatorCheck]) reads the job again when the driver has its pictures.
 *
 * @param atMs the clock of the emulator for the picture, in ms since 1970
 * @param regions how much each part of the face may differ from our engine
 * @param wake also take pictures during a wake, which must show something
 * @param stillInWake parts that must look the same in every wake picture as at rest
 * @param restAfterSeconds the face is at rest only this long after it is shown: a face with frames in
 *   the wake plays them when it is shown, and may look settled while it still plays
 */
class ParityJob(
    val dir: File, val atMs: Long, val regions: List<ParityCompare.Region>, val wake: Boolean, val stillInWake: List<ParityCompare.Region>, val restAfterSeconds: Int = 0
) {

    fun write() {
        val p = Properties()
        p["atMs"] = atMs.toString()
        p["wake"] = wake.toString()
        p["restAfterSeconds"] = restAfterSeconds.toString()
        regions.forEachIndexed { i, r -> p["region.$i"] = encode(r) }
        stillInWake.forEachIndexed { i, r -> p["still.$i"] = encode(r) }
        File(dir, FILE).outputStream().use { p.store(it, "parity job, see ParityJob") }
    }

    /** Puts this job in [queue], one folder per line. Each test JVM appends its own lines. */
    fun enqueue(queue: File) {
        queue.parentFile?.mkdirs()
        FileOutputStream(queue, true).use { out -> out.channel.lock().use { out.write((dir.absolutePath + "\n").toByteArray()) } }
    }

    companion object {

        const val FILE = "parity.job"

        fun read(dir: File): ParityJob {
            val p = Properties().apply { File(dir, FILE).inputStream().use { load(it) } }
            fun list(prefix: String) = p.stringPropertyNames().filter { it.startsWith(prefix) }.sortedBy { it.substringAfter(prefix).toInt() }.map { decode(p.getProperty(it)) }
            return ParityJob(dir, p.getProperty("atMs").toLong(), list("region."), p.getProperty("wake").toBoolean(), list("still."), p.getProperty("restAfterSeconds", "0").toInt())
        }

        private fun encode(r: ParityCompare.Region) = listOf(r.name, r.maxChangedPercent.toString(), r.box?.joinToString(",") ?: "").joinToString("|")

        private fun decode(text: String): ParityCompare.Region {
            val (name, max, box) = text.split("|")
            return ParityCompare.Region(name, max.toDouble(), box.takeIf { it.isNotEmpty() }?.split(",")?.map { it.toInt() }?.toIntArray())
        }
    }
}
