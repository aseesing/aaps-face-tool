package app.aaps.watchfaces.wffparity

import org.junit.Test

/*
 * Shapes, colours and texts of the layer engine (chapters 2 and 3 of the manual): each test is a
 * still layer (`"rest": 0`) on the plain background, so it shows while no wake plays.
 */
/** Shapes, colours and gradients. */
class ParityShapesTextShapesTest : WffParityBase() {

    @Test
    fun rect() = check(
        layers(
            """
            {"shape": "rect", "color": "#4CC9F0", "rest": 0, "x": 80, "y": 120, "width": 120, "height": 80},
            {"shape": "rect", "color": "#F72585", "rest": 0, "x": 230, "y": 150, "width": 60, "height": 140}
            """
        )
    )

    @Test
    fun circle() = check(
        layers(
            """
            {"shape": "circle", "color": "#F72585", "rest": 0, "x": 140, "y": 180, "radius": 50},
            {"shape": "circle", "color": "#4CC9F0", "rest": 0, "x": 270, "y": 220, "radius": 20}
            """
        )
    )

    @Test
    fun lineThicknessAngle() = check(
        layers(
            """
            {"shape": "line", "color": "#FFD166", "rest": 0, "x": 120, "y": 110, "length": 120},
            {"shape": "line", "color": "#4CC9F0", "rest": 0, "x": 200, "y": 110, "length": 120, "thickness": 10, "angle": 30},
            {"shape": "line", "color": "#F72585", "rest": 0, "x": 260, "y": 150, "length": 100, "thickness": 4, "angle": -120}
            """
        )
    )

    @Test
    fun shapesAlpha() = check(
        layers(
            """
            {"shape": "rect", "color": "#FFFFFF", "rest": 0, "x": 70, "y": 140, "width": 260, "height": 40},
            {"shape": "rect", "color": "#4CC9F0", "rest": 0, "alpha": 0.5, "x": 90, "y": 120, "width": 70, "height": 120},
            {"shape": "circle", "color": "#F72585", "rest": 0, "alpha": 0.3, "x": 220, "y": 180, "radius": 45},
            {"shape": "line", "color": "#FFD166", "rest": 0, "alpha": 0.7, "x": 300, "y": 110, "length": 140, "thickness": 12}
            """
        )
    )

    @Test
    fun colorKeys() = check(
        layers(
            """
            {"shape": "rect", "color": "#4CC9F0", "rest": 0, "x": 70, "y": 110, "width": 60, "height": 60},
            {"shape": "rect", "color": "#80FFFFFF", "rest": 0, "x": 150, "y": 110, "width": 60, "height": 60},
            {"shape": "rect", "color": "red", "rest": 0, "x": 230, "y": 110, "width": 60, "height": 60},
            {"shape": "circle", "colors": ["#E63946", "#FFD166", "#2A9D8F"], "count": 3, "rest": 0,
             "x": "120 + 80 * i", "y": 230, "radius": 25, "colorIndex": "i"},
            {"text": "'Colour'", "fontSize": 36, "rest": 0, "colors": ["#FFFFFF", "#FFD166"], "colorIndex": 1,
             "align": "center", "x": 200, "y": 320}
            """
        )
    )

    @Test
    fun colorBgColor() = check(
        layers(
            """
            {"shape": "circle", "color": "bgColor", "rest": 0, "x": 140, "y": 200, "radius": 40},
            {"text": "'BG'", "fontSize": 48, "color": "bgColor", "rest": 0, "align": "center", "x": 270, "y": 218}
            """
        )
    )

    @Test
    fun gradientLinear() = check(
        layers(
            """
            {"shape": "rect", "color": "#4CC9F0", "rest": 0, "x": 70, "y": 100, "width": 260, "height": 60,
             "gradient": {"type": "linear", "stops": [[0, 1], [1, 0]]}},
            {"shape": "circle", "color": "#FFD166", "rest": 0, "x": 130, "y": 230, "radius": 50,
             "gradient": {"type": "linear", "stops": [[0, 0], [0.5, 1], [1, 0]]}},
            {"text": "'fades'", "fontSize": 48, "color": "#F72585", "rest": 0, "align": "center", "x": 270, "y": 250,
             "gradient": {"type": "linear", "stops": [[0, 1], [0.6, 1], [1, 0]]}}
            """
        )
    )

    @Test
    fun gradientRadial() = check(
        layers(
            """
            {"shape": "circle", "color": "#FFE2A6", "rest": 0, "x": 120, "y": 200, "radius": 60,
             "gradient": {"type": "radial", "stops": [[0, 1], [0.25, 1], [1, 0]]}},
            {"shape": "rect", "color": "#F72585", "rest": 0, "x": 210, "y": 140, "width": 140, "height": 100,
             "gradient": {"type": "radial", "stops": [[0, 1], [1, 0]]}},
            {"text": "'round'", "fontSize": 40, "color": "#4CC9F0", "rest": 0, "align": "center", "x": 200, "y": 310,
             "gradient": {"type": "radial", "stops": [[0, 1], [1, 0.2]]}}
            """
        )
    )

    private fun layers(layers: String) = ParityDesign(testName.methodName).animation(layers, "\"durationMs\": 4000")
}

/** Text layers, glows and pictures that stay sharp when big. */
class ParityShapesTextTextsTest : WffParityBase() {

    @Test
    fun textPlain() = check(
        layers(
            """
            {"text": "'Hello'", "rest": 0, "x": 120, "y": 180},
            {"text": 42, "rest": 0, "color": "#FFD166", "x": 220, "y": 240}
            """
        )
    )

    @Test
    fun textFontSize() = check(
        layers(
            """
            {"text": "'Small'", "fontSize": 18, "rest": 0, "x": 90, "y": 130},
            {"text": "'Middle'", "fontSize": 40, "rest": 0, "x": 90, "y": 200},
            {"text": "'Big'", "fontSize": 90, "rest": 0, "x": 90, "y": 300}
            """
        )
    )

    @Test
    fun textZipFont() = check(
        layers(
            """
            {"text": "'10:08'", "font": "AuroraCut", "fontSize": 80, "rest": 0, "align": "center", "x": 200, "y": 190},
            {"text": "'10:08'", "font": "AuroraSnow", "fontSize": 80, "rest": 0, "align": "center", "x": 200, "y": 290, "color": "#4CC9F0"}
            """
        )
    )

    @Test
    fun textAppFont() = check(
        layers(
            """
            {"text": "'Bold 123'", "font": "default_bold", "fontSize": 40, "rest": 0, "align": "center", "x": 200, "y": 140},
            {"text": "'Serif 123'", "font": "serif", "fontSize": 40, "rest": 0, "align": "center", "x": 200, "y": 210},
            {"text": "'Mono 123'", "font": "monospace", "fontSize": 40, "rest": 0, "align": "center", "x": 200, "y": 280}
            """
        )
    )

    @Test
    fun textAlign() = check(
        layers(
            """
            {"shape": "rect", "color": "#E63946", "rest": 0, "x": 199, "y": 90, "width": 2, "height": 220},
            {"text": "'left'", "fontSize": 40, "rest": 0, "align": "left", "x": 200, "y": 150},
            {"text": "'center'", "fontSize": 40, "rest": 0, "align": "center", "x": 200, "y": 220},
            {"text": "'right'", "fontSize": 40, "rest": 0, "align": "right", "x": 200, "y": 290}
            """
        )
    )

    @Test
    fun textValign() = check(
        layers(
            """
            {"shape": "rect", "color": "#E63946", "rest": 0, "x": 40, "y": 199, "width": 320, "height": 2},
            {"text": "'Base'", "fontSize": 32, "rest": 0, "valign": "baseline", "x": 50, "y": 200},
            {"text": "'Top'", "fontSize": 32, "rest": 0, "valign": "top", "x": 135, "y": 200},
            {"text": "'Mid'", "fontSize": 32, "rest": 0, "valign": "middle", "x": 205, "y": 200},
            {"text": "'Bottom'", "fontSize": 32, "rest": 0, "valign": "bottom", "x": 270, "y": 200}
            """
        )
    )

    @Test
    fun textRotation() = check(
        layers(
            """
            {"text": "'turned'", "fontSize": 44, "rest": 0, "align": "center", "valign": "middle", "x": 200, "y": 150, "rotation": 30},
            {"text": "'pivot'", "fontSize": 44, "rest": 0, "color": "#FFD166", "x": 120, "y": 260, "rotation": -20, "pivotX": 120, "pivotY": 260}
            """
        )
    )

    @Test
    fun glowText() = check(
        layers(
            """
            {"text": "'GLOW'", "fontSize": 64, "rest": 0, "letterSpacing": 0.15, "align": "center", "x": 200, "y": 220,
             "color": "#FFFFFF", "glow": {"color": "#4CC9F0", "radius": 14}, "glowAlpha": 1}
            """
        )
    )

    @Test
    fun glowPicture() = check(
        layers(
            """
            {"image": "Star", "rest": 0, "rect": {"left": 90, "top": 150, "width": 100, "height": 100},
             "glow": {"color": "#FF3050", "radius": 14}, "glowAlpha": 1},
            {"image": "Star", "rest": 0, "rect": {"left": 220, "top": 150, "width": 100, "height": 100},
             "glow": {"radius": 30}, "glowAlpha": 0.5}
            """
        )
    )

    @Test
    fun sharpWhenBig() = check(
        layers(
            """
            {"image": "Star", "rest": 0, "rect": {"left": 95, "top": 175, "width": 50, "height": 50}, "scale": 3},
            {"image": "Star", "rest": 0, "rect": {"left": 255, "top": 175, "width": 50, "height": 50}, "scale": 3, "maxScale": 3}
            """
        )
    )

    /** A design of only these layers (json without the brackets). */

    private fun layers(layers: String) = ParityDesign(testName.methodName).animation(layers, "\"durationMs\": 4000")
}
