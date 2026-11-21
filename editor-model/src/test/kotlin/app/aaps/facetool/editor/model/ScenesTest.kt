package app.aaps.facetool.editor.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.File

class ScenesTest {

    private fun obj(text: String): JsonObject = AndroidJson.parseObject(text, "test")

    @Test
    fun `the defaults are the table of the design`() {
        val scene = Scenes.defaults()
        assertThat(scene["time"]).isEqualTo(JsonPrimitive("2026-11-17T10:08:00"))
        assertThat(scene["size"]).isEqualTo(JsonPrimitive(450))
        assertThat(scene["sgv"]).isEqualTo(JsonPrimitive(121))
        assertThat(scene["wake"]).isEqualTo(JsonNull)
        assertThat(scene["tilt"]!!.compact()).isEqualTo("[0,0]")
        assertThat(scene.keys).containsAtLeast("zone", "delta", "avgDelta", "direction", "units", "readingAgeMin", "iob", "cob", "phoneBattery", "heartRate", "weather", "latitude", "decimalSign", "graph", "options", "colours", "watch", "ambient", "visibleSec", "tapSec", "sgvBefore", "transition")
    }

    @Test
    fun `layers go over each other, defaults then project then file then set`() {
        val scene = Scenes.merge(
            obj("""{"sgv": 100, "weather": "rain", "options": {"moon": 1, "santa": 2}}"""),
            obj("""{"sgv": 200, "options": {"moon": 0}}"""),
            listOf("sgv=250", "options.aurora=2", "weather=cloudy", "wake=2.5", "ambient=true", "tilt=[10, 0]", "direction=↑")
        )
        assertThat(scene["sgv"]).isEqualTo(JsonPrimitive(250))
        assertThat(scene["weather"]).isEqualTo(JsonPrimitive("cloudy"))
        assertThat(scene["options"]!!.compact()).isEqualTo("""{"moon":0,"santa":2,"aurora":2}""")
        assertThat(scene["wake"]).isEqualTo(JsonPrimitive(2.5))
        assertThat(scene["ambient"]).isEqualTo(JsonPrimitive(true))
        assertThat(scene["tilt"]!!.compact()).isEqualTo("[10,0]")
        assertThat(scene["direction"]).isEqualTo(JsonPrimitive("↑"))
        assertThat(scene["size"]).isEqualTo(JsonPrimitive(450))
    }

    @Test
    fun `the presets are the list of the design`() {
        assertThat(Scenes.PRESETS.keys).containsExactly("default", "high", "low", "no-data", "old-reading", "night", "christmas", "low-battery").inOrder()
        assertThat(Scenes.over(Scenes.defaults(), Scenes.PRESETS.getValue("default"))).isEqualTo(Scenes.defaults())
    }

    @Test
    fun `named scenes are kept in the project and the scene of a command reads all layers`() {
        val test = TestProjects()
        val project = ProjectFolder(test.open())
        val store = SceneStore(project)
        store.save("high-now", Scenes.merge(null, null, listOf("sgv=250")))
        assertThat(File(project.dir, ".facetool/scenes/high-now.json").readText()).isEqualTo("{\n    \"sgv\": 250\n}")
        assertThat(store.names()).containsExactly("high-now")
        assertThat(store.load("high-now").compact()).isEqualTo("""{"sgv":250}""")
        assertThat(store.load("night")).isEqualTo(Scenes.PRESETS["night"])

        store.use(Scenes.over(Scenes.defaults(), store.load("high-now")))
        assertThat(project.scene()["sgv"]).isEqualTo(JsonPrimitive(250))
        val file = File(test.root, "scene.json").apply { writeText("""{"sgv": 60, "cob": 0}""") }
        val scene = Scenes.forCommand(project, obj("""{"scene": "${file.path}", "set": ["cob=30"]}"""))
        assertThat(scene["sgv"]).isEqualTo(JsonPrimitive(60))
        assertThat(scene["cob"]).isEqualTo(JsonPrimitive(30))
    }
}
