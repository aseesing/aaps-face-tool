package app.aaps.facetool.editor.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidJsonTest {

    @Test
    fun `writes the form of Android toString 4`() {
        val text = """
            {
                "a": "x\/y \"q\" \\ \n\t",
                "empty": {},
                "none": [],
                "list": [
                    1,
                    0.10,
                    -2.5e3,
                    true,
                    null,
                    {
                        "k": "°"
                    }
                ]
            }
        """.trimIndent()
        assertThat(AndroidJson.write(AndroidJson.parse(text))).isEqualTo(text)
    }

    @Test
    fun `writes the compact form of Android toString`() {
        val text = """{"a":"x\/y","n":[1,2.50,{"b":false}],"e":{},"l":[]}"""
        assertThat(AndroidJson.compact(AndroidJson.parse(text))).isEqualTo(text)
    }

    @Test
    fun `control characters get the small hex form of Android`() {
        val element = AndroidJson.parse("\"a\\u0001\\u001f\\b\\f\\u2028\"")
        assertThat(AndroidJson.write(element)).isEqualTo("\"a\\u0001\\u001f\\b\\f \"")
    }

    @Test
    fun `keeps the order of keys`() {
        assertThat(AndroidJson.compact(AndroidJson.parse("""{"z":1,"a":2,"m":3}"""))).isEqualTo("""{"z":1,"a":2,"m":3}""")
    }

    @Test
    fun `a bare word is not JSON`() {
        assertThrows(IllegalArgumentException::class.java) { AndroidJson.parse("""{"a": rain}""") }
        assertThrows(IllegalArgumentException::class.java) { AndroidJson.parse("rain") }
        assertThrows(IllegalArgumentException::class.java) { AndroidJson.parse("""{"a": 1""") }
    }
}
