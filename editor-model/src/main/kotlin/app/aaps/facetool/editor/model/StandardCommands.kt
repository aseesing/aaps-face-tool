package app.aaps.facetool.editor.model

import app.aaps.facetool.editor.model.ArgType.FILE
import app.aaps.facetool.editor.model.ArgType.FLAG
import app.aaps.facetool.editor.model.ArgType.INT
import app.aaps.facetool.editor.model.ArgType.JSON
import app.aaps.facetool.editor.model.ArgType.NUMBER
import app.aaps.facetool.editor.model.ArgType.SOURCE
import app.aaps.facetool.editor.model.ArgType.STRING

/** The commands of `facetool` as data (design section 8.2). Pure tables: no state. */
object StandardCommands {

    private const val PROJECT = "Project"
    private const val READ = "Read"
    private const val EDIT = "Edit"
    private const val PICTURE = "Picture"
    private const val CHECK = "Check"
    private const val WFF = "WFF"

    /** The arguments every command takes. */
    fun globals(): List<ArgSpec> = listOf(
        ArgSpec("json", FLAG, "Print the answer as one JSON object. Errors go to stderr as JSON too."),
        ArgSpec("project", FILE, "The project folder. Default: the folder with .facetool at or above the working folder."),
        ArgSpec("force", FLAG, "Break a lock older than 30 s; with save: write the zip also when a check has errors."),
        ArgSpec("no-start", FLAG, "Do not start the server when it does not run (exit code 4 then).")
    )

    private fun pos(name: String, type: ArgType, help: String, required: Boolean = true, repeat: Boolean = false, choices: List<String> = emptyList()) =
        ArgSpec(name, type, help, positional = true, required = required, repeat = repeat, choices = choices)

    private fun opt(name: String, type: ArgType, help: String, default: String? = null, choices: List<String> = emptyList(), repeat: Boolean = false, server: Boolean = false) =
        ArgSpec(name, type, help, default = default, choices = choices, repeat = repeat, server = server)

    /** The scene arguments and `--out` of every picture command. */
    private fun picture(): List<ArgSpec> = listOf(
        opt("scene", FILE, "A scene file (JSON): its keys go over the scene of the project."),
        opt("set", STRING, "One scene key as key=value, for example sgv=180 or options.moon=1. May repeat.", repeat = true),
        opt("out", FILE, "Where to write the picture. Default: .facetool/out/<command>/.")
    )

    private fun version(): ArgSpec = opt("version", STRING, "The WFF version.", default = "4", choices = listOf("4", "2"))

    private const val PICTURE_ANSWER = "{\"file\": path of the PNG, \"scene\": {...}, \"aapsCommit\": text, \"warnings\": [text], \"text\": text}"
    private const val EDIT_ANSWER = "{\"changed\": true|false, \"history\": number of the history entry, \"text\": text}"

    fun all(): List<CommandSpec> = project() + read() + edit() + pictures() + check() + wff() + help()

    private fun project(): List<CommandSpec> = listOf(
        CommandSpec(
            "open", PROJECT, "Unpacks a design zip into a project folder: the files of the zip, and .facetool for the editor.",
            listOf(pos("zip", FILE, "The design zip."), opt("dir", FILE, "The project folder to make. Default: <editing folder>/<zip name>/.")),
            server = false, project = false,
            examples = listOf("facetool open ~/Projects/aaps-watchfaces/cwf-designs/Aurora_Town.zip", "facetool open D2.zip --dir /tmp/d2"),
            answer = "{\"dir\": project folder, \"files\": count, \"alreadyOpen\": bool, \"generated\": bool, \"warning\": text?, \"text\": text}"
        ),
        CommandSpec(
            "new", PROJECT, "Makes a new empty design in a new project folder: metadata, a background colour, no layers.",
            listOf(pos("name", STRING, "The name of the design."), opt("size", INT, "The size of the design grid.", default = "400"), opt("dir", FILE, "The project folder to make. Default: <editing folder>/<name>/.")),
            server = false, project = false,
            examples = listOf("facetool new \"My Face\"", "facetool new Test --dir /tmp/test"),
            answer = "{\"dir\": project folder, \"text\": text}"
        ),
        CommandSpec(
            "save", PROJECT,
            "Packs the folder into a zip. Checks first: the JSON reads (in the engine when the server runs), the zip is under the size limit of the watch, every resource the JSON names is there. " +
                "An error stops the save (exit code 1); --force writes anyway. The old zip is kept in .facetool/history/<n>.zip.",
            listOf(
                opt("out", FILE, "The zip to write. Default: the zip the project came from."),
                opt("as", FILE, "Save as: write this zip and make it the zip of the project, so a later save writes there. It does not write over another zip without --force.")
            ),
            server = false, serverWhenRunning = true,
            examples = listOf("facetool save", "facetool save --out /tmp/Aurora_Town_test.zip", "facetool save --as ~/Projects/aaps-face-editing/My_Town.zip"),
            answer = "{\"zip\": path, \"written\": bool, \"bytes\": zip size, \"limit\": bytes, \"engineCheck\": \"done\"|\"skipped\", \"generated\": bool, \"findings\": [{\"severity\", \"text\", \"path\"?}], \"text\": text}"
        ),
        CommandSpec(
            "info", PROJECT, "The facts of the project: name, author, sizes, counts of layers, views, templates, options and looks, the wake length, whether a generator makes it, whether the folder changed since open.",
            emptyList(), server = false,
            examples = listOf("facetool info", "facetool info --json"),
            answer = "{\"name\", \"author\", \"filename\", \"dir\", \"sourceZip\", \"zipBytes\", \"zipLimit\", \"jsonBytes\", \"resources\": [{\"name\", \"bytes\"}], \"counts\": {\"layers\", \"views\", \"templates\", \"options\", \"looks\"}, \"wakeMs\", \"wakeFps\", \"generated\": bool, \"changedSinceOpen\": bool, \"text\": text}"
        ),
        CommandSpec(
            "status", PROJECT, "The server: running or not, port, the AAPS commit it runs and whether that is the pinned one, uptime, the last requests.",
            emptyList(), server = true, cli = true, project = false,
            examples = listOf("facetool status"),
            answer = "{\"running\": bool, \"port\", \"pid\", \"checkout\", \"aapsCommit\", \"pinned\": bool, \"uptimeSec\", \"requests\": [text], \"text\": text}"
        ),
        CommandSpec(
            "serve", PROJECT, "Starts the server in the foreground, to see its log. Ctrl-C stops it.",
            emptyList(), server = true, cli = true, project = false,
            examples = listOf("facetool serve"),
            answer = "(no answer: the log of the server)"
        ),
        CommandSpec(
            "stop", PROJECT, "Stops the server.",
            emptyList(), server = true, cli = true, project = false,
            examples = listOf("facetool stop"),
            answer = "{\"stopped\": bool, \"text\": text}"
        ),
        CommandSpec(
            "gui", PROJECT, "Starts the server when needed and opens the editor page in the browser.",
            listOf(opt("print", FLAG, "Only print the address of the page.")),
            server = true, cli = true, project = false,
            examples = listOf("facetool gui", "facetool gui --print"),
            answer = "{\"url\": address with the token, \"text\": text}"
        )
    )

    private fun read(): List<CommandSpec> = listOf(
        CommandSpec(
            "show", READ, "The JSON at a path, or the whole design. Paths: metadata.name, animation.layers[24].x, looks[1].patch, animation.functions[\"bg()\"]. Put a path with brackets in quotes: the shell reads brackets itself.",
            listOf(pos("path", STRING, "The JSON path. Default: the whole design.", required = false)),
            server = false,
            examples = listOf("facetool show metadata.name", "facetool show 'animation.layers[24]'", "facetool show"),
            answer = "{\"path\": text, \"value\": the JSON value, \"text\": the value in the 4 space form}"
        ),
        CommandSpec(
            "list", READ, "One line per entry, with its index or name and a summary. Layers show their source: image Aurora, shape circle x120, text clock(), use rain, view sgv.",
            listOf(pos("kind", STRING, "What to list.", choices = DesignLists.KINDS)),
            server = false,
            examples = listOf("facetool list layers", "facetool list resources --json"),
            answer = "{\"kind\": text, \"items\": [{\"index\" or \"name\", \"path\"?, \"summary\", ...}], \"text\": text}"
        ),
        CommandSpec(
            "expand", READ, "The layers as the engine reads them after templates, let and own functions are expanded. Shows what a {\"use\": ...} really makes.",
            listOf(pos("path", STRING, "A layer path. Default: all layers.", required = false)),
            server = true,
            examples = listOf("facetool expand", "facetool expand 'animation.layers[0]'"),
            answer = "{\"layers\": [the expanded layers], \"text\": text}"
        ),
        CommandSpec(
            "keys", READ, "The names the engine knows, from its own enums: layer keys, view keys, metadata keys, the value names (v_...), the formula functions.",
            listOf(pos("kind", STRING, "Which names. Default: all.", required = false, choices = listOf("layer", "view", "metadata", "values", "functions"))),
            server = true, project = false,
            examples = listOf("facetool keys layer", "facetool keys values --json"),
            answer = "{\"keys\": {kind: [{\"name\", \"help\"?}]}, \"text\": text}"
        ),
        CommandSpec(
            "manual", READ, "A chapter of the engine manual of the AAPS checkout (docs/watchface-animation.html), as plain text. So an agent reads the manual of the commit that draws.",
            listOf(pos("topic", STRING, "The topic.", required = false), opt("list", FLAG, "Show the topics.")),
            server = true, project = false,
            examples = listOf("facetool manual --list", "facetool manual templates"),
            answer = "{\"topics\": [text]?, \"topic\": text?, \"text\": the chapter}"
        )
    )

    private fun edit(): List<CommandSpec> = listOf(
        CommandSpec(
            "set", EDIT, "Sets a value in the design JSON. The value is JSON (5, \"text\", {...}); --text takes it as a text. A new key goes to the end of its object. In a list, the size of the list as index adds the value.",
            listOf(pos("path", STRING, "The JSON path."), pos("value", JSON, "The value, as JSON."), opt("text", FLAG, "Take the value as a text, not as JSON.")),
            server = false,
            examples = listOf("facetool set animation.durationMs 12000", "facetool set sgv.fontColor --text \"#FF0000\"", "facetool set 'animation.layers[3].rect' '{\"left\": 0, \"top\": 0, \"width\": 400, \"height\": 400}'"),
            answer = EDIT_ANSWER
        ),
        CommandSpec(
            "unset", EDIT, "Removes a key or a list entry.",
            listOf(pos("path", STRING, "The JSON path.")),
            server = false,
            examples = listOf("facetool unset 'animation.layers[3].blend'"),
            answer = EDIT_ANSWER
        ),
        CommandSpec(
            "apply", EDIT,
            "A list of edits in one step, all or none: [{\"op\": \"set\", \"path\": ..., \"value\": ...}, {\"op\": \"unset\", \"path\": ...}, " +
                "{\"op\": \"insert\", \"path\": ..., \"value\": ...}, {\"op\": \"move\", \"from\": ..., \"to\": ...}]. insert puts a value before a list entry, or adds a new key. " +
                "Each edit sees the result of the edits before it.",
            listOf(pos("patch", FILE, "The patch file (JSON). - reads standard input.")),
            server = false,
            examples = listOf("facetool apply patch.json", "facetool apply -"),
            answer = EDIT_ANSWER
        ),
        CommandSpec(
            "layer add", EDIT, "Adds a layer; default at the end (on top).",
            listOf(pos("json", JSON, "The layer, as a JSON object."), opt("at", INT, "The index the new layer gets. Default: the end.")),
            server = false,
            examples = listOf("facetool layer add '{\"shape\": \"circle\", \"x\": 200, \"y\": 200, \"radius\": 20, \"color\": \"#FFFFFF\"}'", "facetool layer add '{\"use\": \"rain\"}' --at 0"),
            answer = EDIT_ANSWER
        ),
        CommandSpec(
            "layer move", EDIT, "Moves a layer in the paint order.",
            listOf(pos("from", INT, "The index of the layer."), pos("to", INT, "The index it gets.")),
            server = false,
            examples = listOf("facetool layer move 24 3"),
            answer = EDIT_ANSWER
        ),
        CommandSpec(
            "layer copy", EDIT, "Copies a layer; default right after the layer.",
            listOf(pos("n", INT, "The index of the layer."), opt("at", INT, "The index the copy gets. Default: n + 1.")),
            server = false,
            examples = listOf("facetool layer copy 24", "facetool layer copy 24 --at 0"),
            answer = EDIT_ANSWER
        ),
        CommandSpec(
            "layer remove", EDIT, "Removes a layer.",
            listOf(pos("n", INT, "The index of the layer.")),
            server = false,
            examples = listOf("facetool layer remove 24"),
            answer = EDIT_ANSWER
        ),
        CommandSpec(
            "res add", EDIT, "Adds a picture (png, jpg, svg) or a font file (ttf, otf). Takes single files only, never a zip.",
            listOf(pos("file", FILE, "The file to add."), opt("name", STRING, "The key of the resource: the file name without extension. Default: the name of the file.")),
            server = false,
            examples = listOf("facetool res add ~/Pictures/Star.png", "facetool res add /tmp/font.ttf --name FaceFont"),
            answer = EDIT_ANSWER
        ),
        CommandSpec(
            "res replace", EDIT, "Replaces the bytes of a resource with another file.",
            listOf(pos("key", STRING, "The key or file name of the resource."), pos("file", FILE, "The new file.")),
            server = false,
            examples = listOf("facetool res replace Background /tmp/NewBackground.jpg"),
            answer = EDIT_ANSWER
        ),
        CommandSpec(
            "res remove", EDIT, "Removes a resource file. The JSON is not changed: save reports a name without a file.",
            listOf(pos("key", STRING, "The key or file name of the resource.")),
            server = false,
            examples = listOf("facetool res remove Santa"),
            answer = EDIT_ANSWER
        ),
        CommandSpec(
            "res rename", EDIT, "Renames a resource and changes the JSON that names it (image, image1..., invalidImage, background, mask, font, fontTitle). Other texts equal to the old name are listed, not changed.",
            listOf(pos("old", STRING, "The key of the resource."), pos("new", STRING, "The new key.")),
            server = false,
            examples = listOf("facetool res rename Santa Sleigh"),
            answer = "{\"changed\": bool, \"history\": number, \"uses\": count of changed JSON places, \"otherTexts\": [paths], \"text\": text}"
        ),
        CommandSpec(
            "undo", EDIT, "Takes the last change back, also a change from outside the editor.",
            emptyList(), server = false,
            examples = listOf("facetool undo"),
            answer = "{\"done\": bool, \"entry\": {\"n\", \"time\", \"command\", \"kind\"}?, \"text\": text}"
        ),
        CommandSpec(
            "redo", EDIT, "Does the change again that undo took back.",
            emptyList(), server = false,
            examples = listOf("facetool redo"),
            answer = "{\"done\": bool, \"entry\": {\"n\", \"time\", \"command\", \"kind\"}?, \"text\": text}"
        ),
        CommandSpec(
            "history", EDIT, "The changes of the project, one line each. The star marks the last change that is applied.",
            emptyList(), server = false,
            examples = listOf("facetool history"),
            answer = "{\"position\": number, \"entries\": [{\"n\", \"time\", \"command\", \"kind\", \"resources\": [changed names]}], \"text\": text}"
        )
    )

    private fun pictures(): List<CommandSpec> = listOf(
        CommandSpec(
            "render", PICTURE,
            "Draws the engine face for the scene into a PNG. --batch draws many pictures in one call, from the one kept face: " +
                "the file is a JSON list, each entry an object of scene keys (as --set; nested keys also as \"options.moon\") with its picture name as \"out\" when wanted: " +
                "[{\"sgv\": 250, \"direction\": \"↑\", \"out\": \"high.png\"}, {\"wake\": 2.5}]. The keys go over the scene of the command. " +
                "Each picture is the same as a single render with that scene. --out is then the folder (default .facetool/out/render/batch/), the names are 000.png, 001.png, ... or the out of the entry.",
            picture() + listOf(
                opt("wake", NUMBER, "A moment of the wake: seconds since it began."),
                opt("only", STRING, "Draw only these layers or views: indexes and view names with commas, for example 3,5,sgv."),
                opt("hide", STRING, "Do not draw these layers or views."),
                opt("annotate", FLAG, "Draw the box and the number of each layer on top."),
                opt("grid", FLAG, "Draw the 400 grid and the round edge."),
                opt("zoom", STRING, "Crop to x,y,w,h in design units."),
                opt("batch", FILE, "A JSON file with a list of scenes: one picture each, in one call.")
            ),
            server = true,
            examples = listOf("facetool render", "facetool render --wake 2.5 --set sgv=180 --out /tmp/face.png", "facetool render --only 3,5 --annotate --grid", "facetool render --batch scenes.json --out /tmp/pictures"),
            answer = "$PICTURE_ANSWER; with --batch: {\"dir\": folder, \"files\": [{\"file\": path of the PNG, \"keys\": the scene keys of the entry, \"seconds\": number}], \"seconds\": the whole time, \"aapsCommit\": text, \"warnings\": [text], \"text\": text}"
        ),
        CommandSpec(
            "wake", PICTURE, "The wake as frames. --sheet gives one contact sheet with the time under each frame (the best input for an AI).",
            picture() + listOf(
                opt("fps", INT, "Frames per second.", default = "8"),
                opt("from", NUMBER, "The first moment in seconds.", default = "0"),
                opt("to", NUMBER, "The last moment in seconds. Default: the wake length of the design."),
                opt("sheet", FLAG, "One contact sheet PNG (the default when nothing else is asked)."),
                opt("frames", FILE, "A folder for the single PNG frames."),
                opt("no-sheet", FLAG, "Only the single frames, no contact sheet. Then up to 1800 frames fit, else 240."),
                opt("gif", FILE, "Write an animation to this GIF file."),
                opt("only", STRING, "Draw only these layers or views, as render."),
                opt("hide", STRING, "Do not draw these layers or views, as render.")
            ),
            server = true,
            examples = listOf("facetool wake --sheet", "facetool wake --fps 12 --from 0 --to 3 --gif /tmp/wake.gif", "facetool wake --frames /tmp/frames", "facetool wake --fps 24 --frames /tmp/frames --no-sheet"),
            answer = "{\"file\": path of the sheet?, \"frames\": [paths], \"frameMs\": number, \"times\": [seconds], \"seconds\": how long it took, \"gif\": path?, \"scene\": {...}, \"text\": text}"
        ),
        CommandSpec(
            "options", PICTURE, "One picture per choice of each option, as a sheet, and per colour slot.",
            picture(), server = true,
            examples = listOf("facetool options"),
            answer = "{\"file\": path of the sheet, \"options\": [{\"name\", \"choices\": [{\"index\", \"title\", \"file\"}]}], \"text\": text}"
        ),
        CommandSpec(
            "looks", PICTURE, "Which looks are active in the scene, and a sheet of the looks with a scene for each that turns it on, when the editor can find one.",
            picture(), server = true,
            examples = listOf("facetool looks", "facetool looks --set weather=rain"),
            answer = "{\"active\": [indexes], \"file\": path of the sheet, \"looks\": [{\"index\", \"when\", \"active\": bool, \"scene\": {...}?, \"file\"?}], \"text\": text}"
        ),
        CommandSpec(
            "pick", PICTURE, "Which layers and views draw the pixel at x, y (in 400 design units), top first.",
            picture() + listOf(pos("x", NUMBER, "x in design units."), pos("y", NUMBER, "y in design units.")),
            server = true,
            examples = listOf("facetool pick 200 120"),
            answer = "{\"x\", \"y\", \"hits\": [{\"layer\": index? , \"view\": name?, \"path\", \"summary\"}], \"text\": text}"
        ),
        CommandSpec(
            "snapshot", PICTURE, "Pictures at a fixed set of moments (rest, 3 wake moments, night), for a before and after diff.",
            listOf(opt("name", STRING, "The name of the set of pictures.", default = "default"), opt("scene", FILE, "A scene file (JSON)."), opt("set", STRING, "One scene key as key=value. May repeat.", repeat = true), opt("out", FILE, "The folder for the pictures. Default: .facetool/out/snapshot/<name>/.")),
            server = true,
            examples = listOf("facetool snapshot", "facetool snapshot --name before"),
            answer = "{\"dir\": folder, \"files\": [{\"moment\", \"file\"}], \"text\": text}"
        ),
        CommandSpec(
            "scene", PICTURE, "Shows the scene of the project. --set and --scene change it, --use loads a named scene or a preset, --save keeps it under a name, --reset goes back to the defaults.",
            listOf(
                opt("scene", FILE, "A scene file (JSON): its keys go over the scene of the project."),
                opt("set", STRING, "One scene key as key=value. May repeat.", repeat = true),
                opt("out", FILE, "Write the scene to this JSON file."),
                opt("save", STRING, "Keep the scene under this name in .facetool/scenes/."),
                opt("use", STRING, "Load the named scene or preset as the scene of the project."),
                opt("reset", FLAG, "Make the scene of the project the defaults again."),
                opt("presets", FLAG, "List the ready scenes: ${Scenes.PRESETS.keys.joinToString(", ")}."),
                opt("list", FLAG, "List the named scenes of the project.")
            ),
            server = true,
            examples = listOf("facetool scene", "facetool scene --set sgv=250 --set direction=↑ --save high-now", "facetool scene --use night", "facetool scene --presets"),
            answer = "{\"scene\": {...}, \"names\": [text]?, \"presets\": {name: {...}}?, \"text\": text}"
        )
    )

    private fun check(): List<CommandSpec> = listOf(
        CommandSpec(
            "lint", CHECK, "The engine lint and the editor's checks, then the WFF lint of 4 and 2. One line per finding: severity, path, text. Exit code 1 when there are errors.",
            listOf(opt("scene", FILE, "A scene file (JSON)."), opt("set", STRING, "One scene key as key=value. May repeat.", repeat = true), opt("no-wff", FLAG, "Leave out the WFF lint.")),
            server = true,
            examples = listOf("facetool lint", "facetool lint --json"),
            answer = "{\"findings\": [{\"severity\": \"error|warning|info\", \"source\": \"engine|editor|wff4|wff2|player\", \"path\": text, \"text\": text}], \"errors\": count, \"warnings\": count, \"text\": text}"
        ),
        CommandSpec(
            "diff", CHECK,
            "The differences of two designs: the JSON by path (list entries matched by content, a moved entry is one line), the resources by hash with size and picture size. " +
                "a and b: a zip, a folder, history:<n> (the project after history entry n; 0 is the design at open) or git:<rev> (the project folder in a git commit).",
            listOf(
                pos("a", SOURCE, "The first design."), pos("b", SOURCE, "The second design."),
                opt("render", FLAG, "Add pictures side by side and the share of changed pixels (needs the server).", server = true),
                opt("scene", FILE, "A scene file (JSON), for --render."), opt("set", STRING, "One scene key as key=value, for --render. May repeat.", repeat = true), opt("out", FILE, "Where to write the picture of --render.")
            ),
            server = false, project = false,
            examples = listOf("facetool diff history:0 .", "facetool diff ~/Projects/aaps-watchfaces/cwf-designs/D2.zip . --render", "facetool diff git:HEAD ."),
            answer = "{\"same\": bool, \"json\": [{\"op\": \"change|add|remove|move\", \"path\", \"pathAfter\"?, \"before\"?, \"after\"?}], \"resources\": [{\"op\", \"name\", \"before\"?: {\"bytes\", \"hash\", \"width\"?, \"height\"?}, \"after\"?}], \"file\"?: picture of --render, \"changedPixels\"?: share, \"text\": text}"
        )
    )

    private fun wff(): List<CommandSpec> = listOf(
        CommandSpec(
            "wff plan", WFF, "The planner's plan and the converter's lint: one line per layer and view (live, baked, frames, data, left out) and the totals (slots, frames, memory).",
            listOf(version()), server = true,
            examples = listOf("facetool wff plan", "facetool wff plan --version 2 --json"),
            answer = "{\"version\": 4|2, \"parts\": [{\"path\", \"kind\": \"live|baked|frames|data|out\", \"text\"}], \"totals\": {\"slots\", \"frames\", \"memoryBytes\"}, \"lint\": [text], \"text\": text}"
        ),
        CommandSpec(
            "wff build", WFF, "Makes the face folder (watchface.xml, pictures, fonts, data.zip) without Pack. Cached; the other WFF commands call it when the cache is old.",
            listOf(version()), server = true,
            examples = listOf("facetool wff build", "facetool wff build --version 2"),
            answer = "{\"version\": 4|2, \"dir\": the face folder, \"cached\": bool, \"seconds\": number, \"text\": text}"
        ),
        CommandSpec(
            "wff render", WFF, "Draws the WFF face with the player, for the scene: time, ambient, tilt, heartRate, visibleSec, tapSec, sgvBefore, transition.",
            picture() + version(), server = true,
            examples = listOf("facetool wff render", "facetool wff render --version 2 --set ambient=true", "facetool wff render --set visibleSec=2.5 --set 'tilt=[10,0]'"),
            answer = PICTURE_ANSWER
        ),
        CommandSpec(
            "wff wake", WFF, "The player's frames after the screen comes on, as a sheet, frames or a GIF.",
            picture() + listOf(
                version(), opt("seconds", NUMBER, "How many seconds.", default = "10"), opt("fps", INT, "Frames per second.", default = "10"),
                opt("watch-fps", INT, "Show only the frames a watch with this frame rate shows."),
                opt("sheet", FLAG, "One contact sheet PNG (the default when nothing else is asked)."), opt("frames", FILE, "A folder for the single PNG frames."),
                opt("no-sheet", FLAG, "Only the single frames, no contact sheet. Then up to 1800 frames fit, else 240."), opt("gif", FILE, "Write an animation to this GIF file.")
            ),
            server = true,
            examples = listOf("facetool wff wake", "facetool wff wake --seconds 5 --fps 10 --gif /tmp/wff.gif", "facetool wff wake --seconds 15 --fps 24 --frames /tmp/frames --no-sheet"),
            answer = "{\"file\": path of the sheet?, \"frames\": [paths], \"frameMs\": number, \"seconds\": how long it took, \"gif\": path?, \"text\": text}"
        ),
        CommandSpec(
            "wff parts", WFF, "Each part alone: the static pictures, each loop's frames as a sheet, the live parts, the data picture.",
            picture() + version(), server = true,
            examples = listOf("facetool wff parts"),
            answer = "{\"dir\": folder, \"parts\": [{\"name\", \"kind\", \"file\"}], \"text\": text}"
        ),
        CommandSpec(
            "wff compare", WFF, "Engine and WFF (4 and 2) side by side for the scene, with the changed regions marked and the share of changed pixels.",
            picture(), server = true,
            examples = listOf("facetool wff compare", "facetool wff compare --set sgv=250"),
            answer = "{\"file\": path of the picture, \"versions\": [{\"version\", \"changedPixels\": share, \"regions\": [{\"x\", \"y\", \"w\", \"h\"}]}], \"text\": text}"
        ),
        CommandSpec(
            "wff xml", WFF, "Prints watchface.xml, to read what the converter wrote.",
            listOf(version()), server = true,
            examples = listOf("facetool wff xml", "facetool wff xml --version 2"),
            answer = "{\"version\": 4|2, \"file\": path of watchface.xml, \"text\": the XML}"
        ),
        CommandSpec(
            "wff apk", WFF, "Packs the face into an APK with Pack and runs the validator. Only to check the converter, never for wearers.",
            listOf(version(), opt("client", STRING, "The app id of the wear app that pushes the face.", default = "info.nightscout.androidaps"), opt("zip", FLAG, "Also make the face zip for all wear apps and both versions.")),
            server = true,
            examples = listOf("facetool wff apk", "facetool wff apk --client info.nightscout.androidaps --zip"),
            answer = "{\"apk\": path, \"validator\": path, \"token\": path, \"lint\": path, \"zip\": path?, \"text\": text}"
        ),
        CommandSpec(
            "wff emulator", WFF, "Installs the face on a running Wear OS emulator and takes its picture. Refuses a real watch.",
            listOf(version(), opt("device", STRING, "The adb id of the emulator. Default: the one that runs."), opt("wear-apk", FILE, "The wear app APK to install first."), opt("wake", FLAG, "Also record the wake.")),
            server = true,
            examples = listOf("facetool wff emulator", "facetool wff emulator --device emulator-5554 --wake"),
            answer = "{\"emulator\": path of emulator.png, \"compare\": path of compare.png, \"text\": text}"
        )
    )

    private fun help(): List<CommandSpec> = listOf(
        CommandSpec(
            "help", "Help", "Lists the commands, or explains one with examples. --json gives the whole command table, --markdown a reference for the docs.",
            listOf(pos("command", STRING, "The command to explain.", required = false, repeat = true), opt("markdown", FLAG, "The reference of all commands as Markdown.")),
            server = false, project = false,
            examples = listOf("facetool help", "facetool help layer add", "facetool help --json", "facetool help --markdown"),
            answer = "{\"globals\": [argument], \"commands\": [{\"name\", \"group\", \"help\", \"usage\", \"args\": [argument], \"server\", \"project\", \"examples\", \"answer\"}], \"text\": text}"
        )
    )
}
