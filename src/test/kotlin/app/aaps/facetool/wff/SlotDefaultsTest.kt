package app.aaps.facetool.wff

import android.app.Application
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.facetool.snapshot.SnapshotEngine
import app.aaps.wear.phonepreview.PreviewSlotSource
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A slot of the wearer starts with the same source on the code-based face (`customSlotDefault` of
 * AAPS, read through the engine) and on a WFF face ([WffFaceWriter.USER_SLOT_PREVIEW]). So a picture
 * of the engine with no slot chosen is also the picture of the WFF face.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class SlotDefaultsTest {

    @Test
    fun theCodeBasedFaceAndTheWffFaceStartTheirSlotsTheSame() {
        val engine = SnapshotEngine(RuntimeEnvironment.getApplication(), mock<AAPSLogger>())
        val wff = (1..5).map { WffFaceWriter.USER_SLOT_PREVIEW.getValue(it) }
        assertThat(wff).containsExactly(
            PreviewSlotSource.HEART_RATE, PreviewSlotSource.STEPS, PreviewSlotSource.COB, PreviewSlotSource.IOB, PreviewSlotSource.NONE
        ).inOrder()
        assertThat(engine.slotDefaults).isEqualTo(wff)
    }
}
