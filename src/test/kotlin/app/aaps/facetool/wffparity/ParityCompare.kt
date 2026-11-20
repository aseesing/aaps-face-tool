package app.aaps.facetool.wffparity

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Compares the face as our engine draws it (the reference) with the WFF face on the emulator, both
 * as large as the emulator's screen. Only the round screen counts, without the system's charging
 * mark at the bottom.
 *
 * A pixel is changed when one of its colours differs by more than [threshold] (of 255). The result
 * is the share of changed pixels in each [Region]; a test sets how much each region may change.
 */
object ParityCompare {

    /** A part of the face to compare on its own, in design pixels (400 x 400). Null [box]: the whole face. */
    data class Region(val name: String, val maxChangedPercent: Double, val box: IntArray? = null)

    data class Result(val region: Region, val changedPercent: Double, val maxDiff: Int) {

        val passed: Boolean get() = changedPercent <= region.maxChangedPercent

        override fun toString() = String.format("%s: %.2f %% changed (at most %.2f %%), largest difference %d", region.name, changedPercent, region.maxChangedPercent, maxDiff)
    }

    /** The default threshold: colour noise of WebP, of scaling and of the screen's own rendering stays under it. */
    const val THRESHOLD = 40

    fun compare(reference: File, emulator: File, regions: List<Region>, diff: File, threshold: Int = THRESHOLD): List<Result> {
        val a = scaled(ImageIO.read(reference), SIZE)
        val b = scaled(ImageIO.read(emulator), SIZE)
        val marks = BufferedImage(SIZE * 3, SIZE, BufferedImage.TYPE_INT_RGB)
        marks.graphics.apply {
            drawImage(a, 0, 0, null)
            drawImage(b, SIZE, 0, null)
        }
        val changed = BooleanArray(SIZE * SIZE)
        val diffOf = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            if (!counts(x, y)) continue
            val pa = a.getRGB(x, y)
            val pb = b.getRGB(x, y)
            val d = maxOf(abs((pa shr 16 and 0xff) - (pb shr 16 and 0xff)), abs((pa shr 8 and 0xff) - (pb shr 8 and 0xff)), abs((pa and 0xff) - (pb and 0xff)))
            diffOf[y * SIZE + x] = d
            changed[y * SIZE + x] = d > threshold
            marks.setRGB(SIZE * 2 + x, y, if (d > threshold) Color(255, minOf(255, d), 0).rgb else Color(0, 0, d * 3).rgb)
        }
        diff.parentFile?.mkdirs()
        ImageIO.write(marks, "png", diff)
        return regions.map { region ->
            val k = SIZE / 400.0
            val box = region.box?.let { intArrayOf((it[0] * k).toInt(), (it[1] * k).toInt(), (it[2] * k).toInt(), (it[3] * k).toInt()) } ?: intArrayOf(0, 0, SIZE, SIZE)
            var total = 0
            var bad = 0
            var largest = 0
            for (y in box[1] until minOf(SIZE, box[1] + box[3])) for (x in box[0] until minOf(SIZE, box[0] + box[2])) {
                if (!counts(x, y)) continue
                total++
                if (changed[y * SIZE + x]) bad++
                largest = maxOf(largest, diffOf[y * SIZE + x])
            }
            Result(region, if (total == 0) 0.0 else 100.0 * bad / total, largest)
        }
    }

    /** Inside the round screen, a little in from its edge, and not on the charging mark of the system. */
    private fun counts(x: Int, y: Int): Boolean {
        val c = SIZE / 2.0
        if (hypot(x - c + 0.5, y - c + 0.5) > c - EDGE) return false
        return !(y > SIZE - CHARGING_MARK && abs(x - c) < CHARGING_MARK / 2)
    }

    private fun scaled(image: BufferedImage, size: Int): BufferedImage {
        if (image.width == size && image.height == size) return image
        val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(image, 0, 0, size, size, null)
            dispose()
        }
        return out
    }

    /** The emulator's screen. */
    const val SIZE = 454

    /** Pixels at the edge of the round screen do not count: anti aliasing differs there. */
    private const val EDGE = 6

    /** The size of the charging mark the system draws at the bottom of the emulator's screen. */
    private const val CHARGING_MARK = 56
}
