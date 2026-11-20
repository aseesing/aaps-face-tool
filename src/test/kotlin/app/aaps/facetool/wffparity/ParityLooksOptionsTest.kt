package app.aaps.facetool.wffparity

import org.junit.Test

/**
 * Parity of templates, view layers, looks and the keys outside the animation (chapters 7 to 10 of
 * the manual of the layer engine).
 */
class ParityLooksOptionsTest : WffParityBase() {

    /** Chapter 7: a template of the design with parameters, used twice. */
    @Test
    fun template() = check(
        ParityDesign("Template").animation(
            """
            {"use": "ring"},
            {"use": "ring", "count": 5, "color": "#F72585", "radius": 60, "size": 12}
            """,
            """
            "durationMs": 4000,
            "templates": {
              "ring": {
                "params": {
                  "count": {"default": 8, "min": 0, "max": 24, "int": true},
                  "color": "#4CC9F0",
                  "radius": {"default": 110, "min": 10, "max": 150},
                  "size": {"default": 8, "min": 1, "max": 30}
                },
                "layers": [
                  {"shape": "circle", "colors": ["${'$'}color"], "rest": 0, "count": "${'$'}count", "if": "${'$'}count > 0",
                   "x": "200 + ${'$'}radius * cos(i / ${'$'}count * 2 * pi)", "y": "200 + ${'$'}radius * sin(i / ${'$'}count * 2 * pi)",
                   "radius": "${'$'}size"}
                ]
              }
            }
            """
        )
    )

    /** Chapter 8: a classic view drawn as a layer, with a shape in the slot views over it. */
    @Test
    fun viewAsLayer() = check(
        ParityDesign("ViewAsLayer")
            .view("freetext1", 168, 120, 64, 64, "\"image\": \"Star\"")
            .animation(
                """
                {"view": "freetext1", "rest": 0},
                {"shape": "rect", "color": "#4CC9F0", "slot": "views", "rest": 0, "x": 180, "y": 160, "width": 80, "height": 80}
                """,
                "\"durationMs\": 4000"
            )
    )

    /** Chapter 8: the own place of a view (cx0, cy0): the middle of the star goes to 300, 120. */
    @Test
    fun viewOwnPlace() = check(
        ParityDesign("ViewOwnPlace")
            .view("freetext1", 60, 168, 64, 64, "\"image\": \"Star\"")
            .animation("""{"view": "freetext1", "rest": 0, "x": "300 - cx0", "y": "120 - cy0"}""", "\"durationMs\": 4000")
    )

    /** Chapter 8: a view that moves only in the wake (a hop). At rest it is at its design place. */
    @Test
    fun viewMoved() = check(
        ParityDesign("ViewMoved")
            .view("freetext1", 168, 168, 64, 64, "\"image\": \"Star\"")
            .animation("""{"view": "freetext1", "x": "20 * env * sin(t * 5)", "y": "-10 * env * abs(sin(t * 5))"}""", "\"durationMs\": 4000"),
        lint = listOf("its wake motion is left out")
    )

    /** Chapter 8: a view layer with rest: a new place and size in every frame, and a hop in the wake. */
    @Test
    fun viewLayerRest() = check(
        ParityDesign("ViewLayerRest")
            .view("freetext1", 100, 100, 64, 64, "\"image\": \"Star\"")
            .animation("""{"view": "freetext1", "rest": 0, "x": 120, "y": "40 - 30 * env * abs(sin(t * 4))", "scale": 2}""", "\"durationMs\": 4000"),
        lint = listOf("its wake motion is left out")
    )

    /** Chapter 9: a look that is on at the moment of the test (day): it shows a hidden view and swaps a picture. */
    @Test
    fun look() = check(
        ParityDesign("Look")
            .view("freetext1", 100, 168, 64, 64, "\"image\": \"Star\"")
            .view("freetext2", 236, 168, 64, 64, "\"image\": \"Star\", \"visibility\": \"gone\"")
            .keys(
                """
                "looks": [
                  {"when": "v_is_day == 1", "patch": {"freetext2": {"visibility": "visible"}}, "images": {"Star": "Disc"}}
                ]
                """
            ),
        // Both views change with the look, so the watch draws them: a baked picture would keep the look of the build
        lint = listOf("freetext1              complication data          changed by a look of the design", "freetext2              complication data          changed by a look of the design")
    )

    /** Chapter 10: a picture on a view made by a template of the design. */
    @Test
    fun viewImageTemplate() = check(
        ParityDesign("ViewImageTemplate")
            .view("freetext1", 140, 120, 120, 120, "\"image\": {\"use\": \"badge\"}")
            .animation(
                "",
                """
                "templates": {
                  "badge": {
                    "params": {"color": "#FFD166"},
                    "layers": [
                      {"shape": "circle", "color": "${'$'}color", "rest": 0, "x": 200, "y": 200, "radius": 180},
                      {"shape": "rect", "color": "#10233F", "rest": 0, "x": 80, "y": 170, "width": 240, "height": 60}
                    ]
                  }
                }
                """
            )
    )

    /** Chapter 10: a moment as a text: the next sunrise or sunset with @time. */
    @Test
    fun momentText() = check(
        ParityDesign("MomentText")
            .view(
                "freetext1", 120, 170, 160, 50,
                "\"textsize\": 32, \"gravity\": \"center\", \"font\": \"default\", \"fontColor\": \"#F3E9B5\", \"textvalue\": \"@time\", \"dynValue\": false, \"dynData\": \"sunTime\""
            )
            .keys(""""dynData": {"sunTime": {"valueKey": "sun_next_time", "minData": 0, "maxData": 1, "textvalue1": "@time", "invalidTextvalue": "--:--"}}""")
    )

    /** Chapter 10: the glucose as a line of one colour in the graph. */
    @Test
    fun graphLine() = check(
        ParityDesign("GraphLine")
            .view("chart", 50, 140, 300, 120)
            .keys("\"graphLineWidth\": 3, \"graphLineColor\": \"#4CC9F0\"")
    )

    /** Chapter 10: a colour the wearer can choose, left at "as designed": the design's own colour shows. */
    @Test
    fun colourLooks() = check(
        ParityDesign("ColourLooks")
            .animation("""{"shape": "circle", "color": "#F72585", "rest": 0, "x": 200, "y": 200, "radius": 70}""", "\"durationMs\": 4000")
            .keys(
                """
                "colourLooks": {
                  "colour1": {"animation": {"durationMs": 4000, "layers": [{"shape": "circle", "color": "@colour", "rest": 0, "x": 200, "y": 200, "radius": 70}]}}
                }
                """
            ),
        // The wearer picks the colour on the watch, so the watch draws the circle
        lint = listOf("changed by a look or a colour of the wearer: in the data picture")
    )

    /** Chapter 10: an option of the wearer at its default ("Few": 30 of the 60 stars show). */
    @Test
    fun optionDefault() = check(
        ParityDesign("OptionDefault")
            .keys(""""options": [{"name": "stars", "title": "Stars", "choices": ["None", "Few", "Many"], "default": 1}]""")
            .animation(
                """
                {"shape": "circle", "color": "#FFFFFF", "rest": 0, "count": 60, "seed": 7,
                 "x": "70 + r1 * 260", "y": "70 + r2 * 240", "radius": "3 + r3 * 3",
                 "visible": "i < v_opt_stars * 30"}
                """,
                "\"durationMs\": 3000"
            )
    )
}
