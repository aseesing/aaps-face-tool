package app.aaps.facetool.wff

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The memory budget of a face: loops are thinned so the face stays under the limit. */
class WffMemoryTest {

    private val mb = 1024L * 1024L

    @Test
    fun aLoopThatFitsKeepsAllItsFrames() {
        val memory = WffMemory(limitBytes = 10 * mb)
        // 5 frames of 500 x 500 are 5 MB
        assertThat(memory.fit(frames = 5, width = 500, height = 500, fps = 15)).isEqualTo(1)
        assertThat(memory.usedBytes).isEqualTo(5_000_000L)
    }

    @Test
    fun aLoopThatDoesNotFitIsThinnedByAStepThatDividesItsRate() {
        val memory = WffMemory(limitBytes = 10 * mb)
        memory.add(1000, 1000) // 4 MB
        // 30 frames of 250 x 250 are 7.5 MB: too much. Step 2 does not divide 15; step 3 leaves 10 frames, 2.5 MB
        assertThat(memory.fit(frames = 30, width = 250, height = 250, fps = 15)).isEqualTo(3)
        assertThat(memory.usedBytes).isEqualTo(4_000_000L + 2_500_000L)
        // At 8 frames a second the first step is 2
        assertThat(WffMemory(limitBytes = 5 * mb).fit(frames = 30, width = 250, height = 250, fps = 8)).isEqualTo(2)
    }

    @Test
    fun aLoopThatFitsAtNoStepIsLeftOutAndNotCounted() {
        val memory = WffMemory(limitBytes = 1 * mb)
        assertThat(memory.fit(frames = 60, width = 450, height = 450, fps = 4)).isNull()
        assertThat(memory.usedBytes).isEqualTo(0L)
    }

    @Test
    fun aPictureInMotionGivesWayToTheLoops() {
        // As Aurora Town: a sky of 60 large frames at 4 a second, and snow loops of 76 full frames at 15
        val memory = WffMemory(limitBytes = 90 * mb)
        memory.add(450, 450)
        memory.reserveRun(frames = 60, width = 450, height = 405, fps = 4)
        // The loops keep all their frames
        assertThat(memory.fit(frames = 23, width = 450, height = 450, fps = 15)).isEqualTo(1)
        assertThat(memory.fit(frames = 30, width = 450, height = 450, fps = 15)).isEqualTo(1)
        assertThat(memory.fit(frames = 23, width = 450, height = 450, fps = 15)).isEqualTo(1)
        // The sky plays at half its rate: all 60 frames would make 106 MB
        assertThat(memory.settleRuns()).containsExactly(2)
        assertThat(memory.usedBytes).isAtMost(90 * mb)
    }

    @Test
    fun aPictureInMotionKeepsItsRateWhenThereIsRoom() {
        val memory = WffMemory(limitBytes = 90 * mb)
        memory.reserveRun(frames = 60, width = 450, height = 405, fps = 4)
        assertThat(memory.fit(frames = 30, width = 450, height = 450, fps = 15)).isEqualTo(1)
        assertThat(memory.settleRuns()).containsExactly(1)
    }

    @Test
    fun theConverterKeepsTenPercentUnderTheValidator() {
        assertThat(WffMemory.LIMIT_MB).isEqualTo(90)
        assertThat(WffMemory.VALIDATOR_MB).isEqualTo(100)
    }
}
