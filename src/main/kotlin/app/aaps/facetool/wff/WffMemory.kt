package app.aaps.facetool.wff

/**
 * The memory budget of a face. The validator of Watch Face Push counts every picture of a face at 4
 * bytes a pixel and allows 100 MB while the face is active (converter doc 7.3). A face over that gets
 * no token and cannot be installed. The converter keeps a margin: frames of the wake are large, and
 * how large depends on the moment the face is baked at.
 *
 * When the frames of a loop do not fit, the loop keeps only every 2nd, 3rd, ... frame and plays that
 * much slower, so it lasts as long as before. Only a step that divides the frame rate is used, so the
 * rate stays a whole number.
 *
 * Who gives way first: a static picture in motion (a "run": the whole picture as frames, large and
 * slow, like a sky). Its frames are counted at their lowest rate while the loops are made, so the
 * loops (snow, rain, a sleigh: small or fast things, where a low rate shows at once) keep their full
 * rate when that is possible at all. At the end each run gets the highest rate that still fits
 * ([settleRuns]).
 */
class WffMemory(private val limitBytes: Long = LIMIT_BYTES) {

    /** The bytes of the pictures counted so far. */
    var usedBytes = 0L
        private set

    private class Run(val frames: Int, val width: Int, val height: Int, val fps: Int)

    private val runs = ArrayList<Run>()

    /** The frames a run keeps at [step], and never fewer than two: a loop needs two frames. */
    private fun kept(run: Run, step: Int): Int = ((run.frames + step - 1) / step).coerceAtLeast(2)

    /**
     * Counts a static picture in motion of [frames] frames of [width] x [height] pixels at [fps] frames
     * a second, for now at its lowest rate (one frame a second). [settleRuns] gives its real rate.
     */
    fun reserveRun(frames: Int, width: Int, height: Int, fps: Int) {
        val run = Run(frames, width, height, fps)
        runs += run
        usedBytes += bytes(kept(run, fps), width, height)
    }

    /**
     * The step of each run of [reserveRun], in their order: 1 keeps all its frames. Each run gets the
     * smallest step that fits with everything counted so far; a run that does not fit at any step
     * keeps its lowest rate.
     */
    fun settleRuns(): List<Int> = runs.map { run ->
        val reserved = bytes(kept(run, run.fps), run.width, run.height)
        val step = (1..run.fps).firstOrNull { step -> run.fps % step == 0 && usedBytes - reserved + bytes(kept(run, step), run.width, run.height) <= limitBytes } ?: run.fps
        usedBytes += bytes(kept(run, step), run.width, run.height) - reserved
        step
    }

    /** Counts one picture of [width] x [height] pixels that the face always has (a static picture, the preview). */
    fun add(width: Int, height: Int) {
        usedBytes += bytes(1, width, height)
    }

    /**
     * Counts a loop of [frames] frames of [width] x [height] pixels at [fps] frames a second. Gives
     * the step to keep the frames at: 1 keeps all, 2 every second one at half the rate, and so on;
     * null when the loop does not fit at any step (then nothing is counted).
     */
    fun fit(frames: Int, width: Int, height: Int, fps: Int): Int? {
        for (step in 1..fps) {
            if (fps % step != 0) continue
            val kept = (frames + step - 1) / step
            // A loop needs two frames to be a loop
            if (kept < 2) break
            val needed = bytes(kept, width, height)
            if (usedBytes + needed <= limitBytes) {
                usedBytes += needed
                return step
            }
        }
        return null
    }

    private fun bytes(pictures: Int, width: Int, height: Int): Long = pictures.toLong() * width * height * BYTES_PER_PIXEL

    companion object {

        private const val BYTES_PER_PIXEL = 4

        /** What the validator allows for an active face, in MB. */
        const val VALIDATOR_MB = 100

        /** What the converter fills at most, in MB: 10 % under the validator's limit. */
        const val LIMIT_MB = 90
        const val LIMIT_BYTES = LIMIT_MB * 1024L * 1024L
    }
}
