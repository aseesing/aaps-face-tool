package app.aaps.facetool.editor.server

import app.aaps.core.interfaces.rx.weardata.CwfData
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * A scene key `watch.X` does nothing when the design sets the same switch in its metadata: the design's
 * value wins, as on the watch. The picture commands say so.
 */
class DesignWinsTest {

    private fun design(metadata: String) = CwfData("""{"metadata": $metadata}""", mutableMapOf(), mutableMapOf())

    private fun scene(vararg watch: Pair<String, String>): JsonObject =
        buildJsonObject { put("watch", JsonObject(watch.associate { it.first to JsonPrimitive(it.second) })) }

    @Test
    fun aWatchSettingTheDesignSetsGetsAWarning() {
        val warnings = PictureCommands.designWins(
            design("""{"key_show_complication1": "true", "key_show_date": true}"""),
            scene("show_complication_1" to "false", "show_date" to "false", "show_seconds" to "false")
        )
        assertThat(warnings).hasSize(2)
        assertThat(warnings[0]).contains("watch.show_complication_1 does nothing here: the design sets metadata.key_show_complication1")
        assertThat(warnings[1]).contains("metadata.key_show_date")
    }

    @Test
    fun noWarningWithoutTheSwitchOrWithoutWatchKeys() {
        assertThat(PictureCommands.designWins(design("""{"name": "x"}"""), scene("show_complication_1" to "false"))).isEmpty()
        assertThat(PictureCommands.designWins(design("""{"key_show_date": "true"}"""), JsonObject(emptyMap()))).isEmpty()
    }
}
