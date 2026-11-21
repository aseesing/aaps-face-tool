package app.aaps.facetool.editor.server

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.File

/**
 * The scene key `slots`: what each complication slot shows in the engine preview. A slot that is left
 * out shows what the watch shows before the wearer picks a source (`customSlotDefaultSource` of AAPS).
 */
class SlotSourcesTest : ServerTestBase() {

    private fun sets(vararg pairs: String): Map<String, JsonElement> = mapOf("set" to JsonArray(pairs.map { JsonPrimitive(it) }))

    private fun picture(project: File, vararg pairs: String): String = sha256(File(text(run("render", sets(*pairs), project), "file")).readBytes())

    @Test
    fun aSlotShowsItsSourceOfTheScene() {
        // D2 shows the slots 1 and 2, as rows of text
        val project = open("D2")
        val defaults = picture(project)

        // The defaults of the watch, as the engine names them: the page reads them from here
        val named = run("scene", emptyMap(), project).getValue("slotDefaults") as JsonObject
        assertThat(named.keys).containsExactly("1", "2", "3", "4", "5").inOrder()
        assertThat(named.values.map { (it as JsonPrimitive).content }).containsExactly("cob", "iob", "none", "none", "none").inOrder()

        // Choosing the default by name, or null, is the same picture as no choice
        assertThat(picture(project, "slots.1=cob", "slots.2=iob")).isEqualTo(defaults)
        assertThat(picture(project, "slots.1=null")).isEqualTo(defaults)

        // Another source is another picture, and each source its own
        val pictures = listOf("none", "iob", "bg", "basal", "phoneBattery", "heartRate", "steps").associateWith { picture(project, "slots.1=$it") }
        assertThat(pictures.values.toSet()).hasSize(pictures.size)
        assertThat(pictures.values).doesNotContain(defaults)

        // A slot shows the values of the scene
        assertThat(picture(project, "slots.1=steps", "steps=12")).isNotEqualTo(pictures["steps"])
        assertThat(picture(project, "slots.1=heartRate", "heartRate=150")).isNotEqualTo(pictures["heartRate"])
        assertThat(picture(project, "slots.1=phoneBattery", "phoneBattery=20")).isNotEqualTo(pictures["phoneBattery"])
        assertThat(picture(project, "slots.1=bg", "sgv=250")).isNotEqualTo(pictures["bg"])

        // A slot the design does not show draws nothing, whatever its source
        assertThat(picture(project, "slots.5=bg")).isEqualTo(defaults)

        // After all these the face without a choice is the same picture again
        assertThat(picture(project)).isEqualTo(defaults)
    }

    @Test
    fun theProjectKeepsTheChoiceAndBadChoicesAreRefused() {
        val project = open("D2")
        val defaults = picture(project)
        val heart = picture(project, "slots.2=heartRate")

        // `scene --set` keeps it in the project, as the drop-down of the page does
        val kept = run("scene", sets("slots.2=heartRate"), project)
        assertThat(((kept.getValue("scene") as JsonObject).getValue("slots") as JsonObject)["2"]).isEqualTo(JsonPrimitive("heartRate"))
        assertThat(picture(project)).isEqualTo(heart)
        // A second slot goes next to the first, it does not replace it
        run("scene", sets("slots.1=none"), project)
        assertThat((run("scene", emptyMap(), project).getValue("scene") as JsonObject).getValue("slots").toString()).isEqualTo("""{"2":"heartRate","1":"none"}""")
        // null takes a choice back
        run("scene", sets("slots.1=null", "slots.2=null"), project)
        assertThat(picture(project)).isEqualTo(defaults)

        // A source that does not exist and a slot that does not exist are wrong use, and the scene stays
        val badSource = answer("scene", sets("slots.1=weather"), project)
        assertThat(badSource.exitCode).isEqualTo(2)
        assertThat(text(badSource.json, "text")).contains("cannot show 'weather'")
        val badSlot = answer("render", sets("slots.6=bg"), project)
        assertThat(badSlot.exitCode).isEqualTo(2)
        assertThat(text(badSlot.json, "text")).contains("slots 1 to 5")
        assertThat(picture(project)).isEqualTo(defaults)
    }
}
