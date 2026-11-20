package app.aaps.facetool.wffparity

import org.junit.Test

/*
 * Composition: pictures, place, turn and scale, show and hide, blends, groups and clips (chapters 2
 * and 3 of the manual of the layer engine). Every layer has `"rest": 0`, so it shows at rest.
 */
/** Pictures, place, turn, blends and groups. */
class ParityCompositionLayersTest : WffParityBase() {

    /** A picture of the zip, stretched into its rect. */
    @Test
    fun imageLayer() = check(
        layers(
            "Image",
            """
            {"image": "Star", "rest": 0, "rect": {"left": 70, "top": 140, "width": 120, "height": 120}},
            {"image": "Disc", "rest": 0, "rect": {"left": 230, "top": 160, "width": 100, "height": 80}}
            """
        )
    )

    /** A picture of the app: the moon photo, drawn in the middle of its rect as a square of the shorter side. */
    @Test
    fun appPicture() = check(
        layers(
            "AppPicture",
            """
            {"image": "@moon", "rest": 0, "rect": {"left": 120, "top": 140, "width": 160, "height": 120}}
            """
        ),
        // The watch draws the moon photo in the data picture
        lint = listOf("in the data picture")
    )

    /** rect is the home of a picture, x and y move it; for a shape x and y are its place. */
    @Test
    fun place() = check(
        layers(
            "Place",
            """
            {"shape": "rect", "color": "#33415C", "rest": 0, "x": 80, "y": 170, "width": 100, "height": 100},
            {"image": "Star", "rest": 0, "rect": {"left": 80, "top": 170, "width": 100, "height": 100}, "x": 120, "y": -60},
            {"shape": "circle", "color": "#F72585", "rest": 0, "x": 140, "y": 300, "radius": 30}
            """
        )
    )

    /** rotation round the default pivot and round a set pivot, scale, scaleX and scaleY with pivotY. */
    @Test
    fun turnAndScale() = check(
        layers(
            "TurnScale",
            """
            {"shape": "rect", "color": "#4CC9F0", "rest": 0, "x": 60, "y": 110, "width": 100, "height": 60, "rotation": 30},
            {"shape": "rect", "color": "#FFD166", "rest": 0, "x": 270, "y": 80, "width": 20, "height": 140, "pivotX": 280, "pivotY": 80, "rotation": 35},
            {"image": "Star", "rest": 0, "rect": {"left": 60, "top": 210, "width": 80, "height": 80}, "scale": 1.4, "rotation": 20},
            {"shape": "rect", "color": "#F72585", "rest": 0, "x": 190, "y": 240, "width": 60, "height": 40, "scaleX": 1.5},
            {"shape": "circle", "color": "#90E0EF", "rest": 0, "x": 300, "y": 270, "radius": 30, "scaleY": 0.4, "pivotY": 300}
            """
        )
    )

    /** visible 0 hides a layer, visible 1 shows it, alpha makes it half clear. */
    @Test
    fun showAndHide() = check(
        layers(
            "ShowHide",
            """
            {"shape": "rect", "color": "#35507A", "rest": 0, "x": 40, "y": 170, "width": 320, "height": 60},
            {"shape": "circle", "color": "#FFD166", "rest": 0, "x": 110, "y": 200, "radius": 50, "alpha": 0.5},
            {"shape": "circle", "color": "#4CC9F0", "rest": 0, "x": 200, "y": 200, "radius": 40, "visible": 0},
            {"shape": "circle", "color": "#F72585", "rest": 0, "x": 290, "y": 200, "radius": 50, "visible": 1, "alpha": 0.8}
            """
        )
    )

    /** normal, add and screen over a band, as the manual shows them. */
    @Test
    fun blendAddScreen() = check(
        layers(
            "Blend",
            """
            {"shape": "rect", "color": "#35507A", "rest": 0, "x": 0, "y": 120, "width": 400, "height": 160},
            {"shape": "circle", "color": "#C97B2A", "rest": 0, "radius": 50, "x": 90, "y": 200},
            {"shape": "circle", "color": "#C97B2A", "rest": 0, "radius": 50, "blend": "add", "x": 200, "y": 200},
            {"shape": "circle", "color": "#C97B2A", "rest": 0, "radius": 50, "blend": "screen", "x": 310, "y": 200}
            """
        )
    )

    /** The same blends by their WFF names, in capital letters, with a picture that adds light. */
    @Test
    fun blendWffNames() = check(
        layers(
            "BlendNames",
            """
            {"shape": "rect", "color": "#35507A", "rest": 0, "x": 0, "y": 120, "width": 400, "height": 160},
            {"image": "Disc", "rest": 0, "rect": {"left": 40, "top": 150, "width": 100, "height": 100}, "blend": "SRC_OVER"},
            {"image": "Star", "rest": 0, "rect": {"left": 150, "top": 150, "width": 100, "height": 100}, "blend": "PLUS"},
            {"image": "Star", "rest": 0, "rect": {"left": 260, "top": 150, "width": 100, "height": 100}, "blend": "SCREEN"}
            """
        )
    )

    /** A group with erase: a hole in the group, the band below is not cut. */
    @Test
    fun groupErase() = check(
        layers(
            "GroupErase",
            """
            {"shape": "rect", "color": "#35507A", "rest": 0, "x": 0, "y": 120, "width": 400, "height": 160},
            {"shape": "circle", "color": "#C97B2A", "rest": 0, "radius": 60, "x": 130, "y": 200, "group": "ring"},
            {"shape": "circle", "rest": 0, "radius": 40, "x": 150, "y": 200, "group": "ring", "blend": "erase"},
            {"shape": "rect", "color": "#4CC9F0", "rest": 0, "x": 230, "y": 150, "width": 110, "height": 100, "group": "bar"},
            {"shape": "rect", "color": "#80FFFFFF", "rest": 0, "x": 230, "y": 190, "width": 110, "height": 20, "group": "bar", "blend": "DST_OUT"}
            """
        )
    )

    /** A group with inside: the picture shows only inside the circle; also by its WFF name SRC_IN. */
    @Test
    fun groupInside() = check(
        layers(
            "GroupInside",
            """
            {"shape": "rect", "color": "#35507A", "rest": 0, "x": 0, "y": 120, "width": 400, "height": 160},
            {"shape": "circle", "rest": 0, "radius": 60, "x": 120, "y": 200, "group": "window"},
            {"image": "Star", "rest": 0, "rect": {"left": 40, "top": 120, "width": 160, "height": 160}, "group": "window", "blend": "inside"},
            {"shape": "rect", "rest": 0, "x": 230, "y": 150, "width": 110, "height": 100, "group": "window2"},
            {"image": "Disc", "rest": 0, "rect": {"left": 250, "top": 130, "width": 140, "height": 140}, "group": "window2", "blend": "SRC_IN"}
            """
        )
    )

    /** The sheet of a group is as big as the clip.rect of its first layer: nothing of the group shows outside it. */
    @Test
    fun groupSheet() = check(
        layers(
            "GroupSheet",
            """
            {"shape": "circle", "color": "#FFD166", "rest": 0, "radius": 70, "x": 200, "y": 200, "group": "g",
             "clip": {"rect": {"left": 130, "top": 130, "width": 100, "height": 140}}},
            {"shape": "rect", "color": "#4CC9F0", "rest": 0, "x": 120, "y": 180, "width": 160, "height": 40, "group": "g"}
            """
        )
    )

    private fun layers(name: String, layers: String) = ParityDesign(name).animation(layers, "\"durationMs\": 4000")
}

/** Erase without a group, and the clips. */
class ParityCompositionClipTest : WffParityBase() {

    /** erase without a group is left out by the engine. */
    @Test
    fun eraseWithoutGroup() = check(
        layers(
            "EraseNoGroup",
            """
            {"shape": "rect", "color": "#35507A", "rest": 0, "x": 80, "y": 140, "width": 240, "height": 120},
            {"shape": "circle", "rest": 0, "radius": 40, "x": 200, "y": 200, "blend": "erase"}
            """
        )
    )

    /** clip.rect: the circle shows only inside the dark box. */
    @Test
    fun clipRect() = check(
        layers(
            "ClipRect",
            """
            {"shape": "rect", "color": "#22304A", "rest": 0, "x": 100, "y": 120, "width": 200, "height": 160},
            {"shape": "circle", "color": "#FFD166", "rest": 0, "x": 110, "y": 200, "radius": 60,
             "clip": {"rect": {"left": 100, "top": 120, "width": 200, "height": 160}}}
            """
        )
    )

    /** clip.rings and clip.boxes with round corners. */
    @Test
    fun clipRingsBoxes() = check(
        layers(
            "ClipRings",
            """
            {"shape": "rect", "color": "#FFFFFF", "rest": 0, "x": 140, "y": -100, "width": 120, "height": 600,
             "pivotX": 200, "pivotY": 200, "rotation": 25,
             "clip": {"rings": [{"cx": 200, "cy": 200, "inner": 150, "outer": 180}],
                      "boxes": [{"left": 110, "top": 150, "width": 180, "height": 100}], "cornerRadius": 24}}
            """
        )
    )

    /** clip.mask: the Mask picture (upper half solid), whole face and in a maskRect. */
    @Test
    fun clipMask() = check(
        layers(
            "ClipMask",
            """
            {"shape": "rect", "color": "#35507A", "rest": 0, "x": 60, "y": 60, "width": 120, "height": 280},
            {"image": "Star", "rest": 0, "rect": {"left": 60, "top": 140, "width": 120, "height": 120}, "blend": "screen",
             "clip": {"mask": "Mask"}},
            {"shape": "circle", "color": "#F72585", "rest": 0, "x": 290, "y": 200, "radius": 70,
             "clip": {"mask": "Mask", "maskRect": {"left": 220, "top": 150, "width": 140, "height": 140}}}
            """
        )
    )

    /** The clip props clipLeft, clipTop, clipRight and clipBottom. */
    @Test
    fun clipProps() = check(
        layers(
            "ClipProps",
            """
            {"image": "Star", "rest": 0, "rect": {"left": 60, "top": 120, "width": 140, "height": 140}, "clipRight": 150},
            {"shape": "rect", "color": "#4CC9F0", "rest": 0, "x": 220, "y": 120, "width": 120, "height": 140, "clipLeft": 250, "clipTop": 150, "clipBottom": 230},
            {"shape": "rect", "color": "#FFD166", "rest": 0, "x": 80, "y": 290, "width": 240, "height": 20, "clipLeft": 140}
            """
        )
    )

    private fun layers(name: String, layers: String) = ParityDesign(name).animation(layers, "\"durationMs\": 4000")
}
