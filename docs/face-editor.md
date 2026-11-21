# The face editor: `facetool`

`facetool` is a tool to make and change designs of the AAPS Custom watch face (the design zips with
layers, templates, options, looks, text layers and the wake animation). It shows a design as the AAPS
engine draws it, and as the converter of this repository turns it into a Watch Face Format (WFF)
face. Every action is a command with text or JSON out, and every picture is a PNG file, so an AI
agent can use it without a screen. A small browser page gives a person the same commands.

The design of the tool is `docs/face-editor-design.md`. This page tells how to use it.

Status (2026-11-21): the commands below are built and have tests, but `wff emulator` (it answers
"not built yet": use `tools/wff-emulator.sh` as `docs/wff-apk.md` says). The owner has not tried the
tool yet.

## Set up, once

1. `local.properties` as in the README (`aapsDir`, `designsDir`).
2. Build the command line and the class path of the render server:

   ```
   ./gradlew :editor-cli:installDist :editor-server:editorClasspath
   ```

   (On the owner's Mac every Gradle build goes through `~/.claude/bin/gradle-locked.sh`.)
3. Run `tools/facetool help`. Put `tools` on your `PATH`, or call it by its path.

Run the two Gradle tasks again after you change the code of the editor, or after the AAPS checkout
moved to another commit. A running server then says "restart needed": `facetool stop`, and the next
command starts the new one.

## How it works

- A design is edited as a **project folder**: the files of the zip, unpacked, and a hidden folder
  `.facetool` (the history, the scene, the pictures). `facetool open <zip>` makes it,
  `facetool save` packs it into a zip again. Inside a project folder every command finds the
  project by itself; elsewhere give `--project <dir>`.
- Commands that only read or change files run at once. Commands that draw need the **render
  server**: one Java process that runs the real AAPS engine. The first such command starts it (about
  5 s); it stops by itself after 2 hours without a request. It listens only on `127.0.0.1`.
- A **scene** is everything outside the design that changes the picture: the time, the glucose, the
  weather, the choices of the options, the colours. A project keeps its scene; `--set key=value`
  changes one key for one command. `facetool scene --presets` lists ready scenes.
- You may edit `CustomWatchface.json` and the resource files with any tool. The editor reads the
  folder new at each command, and `undo` also takes back a change from outside.

## The loop for an agent

```
facetool open ~/Projects/aaps-watchfaces/cwf-designs/D2.zip --dir /tmp/D2
cd /tmp/D2
facetool list layers                       # the layers with their index
facetool show 'animation.layers[3]'        # one layer as JSON
facetool set 'animation.layers[3].x' '"200 + 10 * sin(t)"'
facetool render                            # .facetool/out/render/render.png: look at it
facetool wake --sheet                      # .facetool/out/wake/wake.png: the wake as one sheet
facetool lint                              # the engine's errors, the editor's checks, the WFF lint
facetool wff compare                       # the engine next to the WFF 4 and WFF 2 faces
facetool save --out /tmp/D2-new.zip
```

Good to know:

- `facetool render --annotate --grid` draws the box and the number of each layer and view, and the
  grid of the 400 design units. `facetool pick 200 120` says which layers and views draw a point.
- `facetool keys layer` and `facetool manual <topic>` give the keys and the manual of the engine of
  the AAPS commit that draws. `facetool expand` shows the plain layers a `use` of a template makes.
- A path counts the entries of `animation.layers` in the JSON. The engine and the WFF lint count the
  plain layers after the templates are expanded (`L24`). `facetool expand` shows both.
- `--json` gives one JSON object. Exit codes: 0 done; 1 done, but a check has errors; 2 wrong use;
  3 the design does not read; 4 the server failed.
- The first picture after a change of the scene takes about 2.5 s (the engine's data store waits),
  each next picture of the same scene 0.1 to 0.3 s.

## What the pictures are, and what they are not

- The engine pictures are drawn by the engine itself, the code of the watch. At rest they are the
  picture of the watch for the scene.
- **A wake frame of a design with text layers is not right yet**: the preview code of AAPS draws a
  text layer in the wake with the default font and units (design section 15). The commands say so.
- The engine preview has no always-on picture.
- The WFF pictures are drawn by the **player**: our reading of the WFF runtime. The parity suite
  compares it with the emulator. Text can lie a little differently on a watch, and the wearer's
  slots show sample values.
- A picture is true for the AAPS commit in `aaps-commit.txt`. Every answer names the commit that
  drew it, and warns when it is another one.

## Rules

- The tool never merges two zips. `res add` takes single picture and font files.
- All 12 zips of `aaps-watchfaces/cwf-designs` are made by generators there. The editor says so at
  `open` and `save`: a new build of the designs overwrites the zip. Change the generator to keep a
  change.
- A zip over about 320 KB never reaches the watch. `save` stops there (`--force` writes anyway).
- Face APKs are only to check the converter on an emulator. Never install one on a real watch from
  here, and do not share faces made from other people's designs.

## The browser page

`facetool gui` in a project folder opens the page. It shows the layers and views (the eye hides one
in the preview only), the engine picture with the wake slider, annotate, grid and pick, the JSON of
the selection with Apply, the scene, the lint and a log of every command as its command line. When
an agent changes the folder, the page shows the change and marks the changed layers.

## Command reference

Made by `facetool help --markdown` from the command table. Do not edit by hand.

Every command takes:

- `--json`: Print the answer as one JSON object. Errors go to stderr as JSON too.
- `--project <file>`: The project folder. Default: the folder with .facetool at or above the working folder.
- `--force`: Break a lock older than 30 s; with save: write the zip also when a check has errors.
- `--no-start`: Do not start the server when it does not run (exit code 4 then).

### Project

#### `open`

`facetool open <zip> [--dir <file>]`

Unpacks a design zip into a project folder: the files of the zip, and .facetool for the editor.

Runs: local, without the server

| Argument | Type | Meaning |
|---|---|---|
| `<zip>` | file | The design zip. |
| `--dir <file>` | file | The project folder to make. Default: <editing folder>/<zip name>/. |

Answer with `--json`: `{"dir": project folder, "files": count, "alreadyOpen": bool, "generated": bool, "warning": text?, "text": text}`

```
facetool open ~/Projects/aaps-watchfaces/cwf-designs/Aurora_Town.zip
facetool open D2.zip --dir /tmp/d2
```

#### `new`

`facetool new <name> [--size <int>] [--dir <file>]`

Makes a new empty design in a new project folder: metadata, a background colour, no layers.

Runs: local, without the server

| Argument | Type | Meaning |
|---|---|---|
| `<name>` | string | The name of the design. |
| `--size <int>` | int | The size of the design grid. Default: 400. |
| `--dir <file>` | file | The project folder to make. Default: <editing folder>/<name>/. |

Answer with `--json`: `{"dir": project folder, "text": text}`

```
facetool new "My Face"
facetool new Test --dir /tmp/test
```

#### `save`

`facetool save [--out <file>]`

Packs the folder into a zip. Checks first: the JSON reads (in the engine when the server runs), the zip is under the size limit of the watch, every resource the JSON names is there. An error stops the save (exit code 1); --force writes anyway. The old zip is kept in .facetool/history/<n>.zip.

Runs: local; in the server when one runs

| Argument | Type | Meaning |
|---|---|---|
| `--out <file>` | file | The zip to write. Default: the zip the project came from. |

Answer with `--json`: `{"zip": path, "written": bool, "bytes": zip size, "limit": bytes, "engineCheck": "done"|"skipped", "generated": bool, "findings": [{"severity", "text", "path"?}], "text": text}`

```
facetool save
facetool save --out /tmp/Aurora_Town_test.zip
```

#### `info`

`facetool info`

The facts of the project: name, author, sizes, counts of layers, views, templates, options and looks, the wake length, whether a generator makes it, whether the folder changed since open.

Runs: local, without the server

Answer with `--json`: `{"name", "author", "filename", "dir", "sourceZip", "zipBytes", "zipLimit", "jsonBytes", "resources": [{"name", "bytes"}], "counts": {"layers", "views", "templates", "options", "looks"}, "wakeMs", "generated": bool, "changedSinceOpen": bool, "text": text}`

```
facetool info
facetool info --json
```

#### `status`

`facetool status`

The server: running or not, port, the AAPS commit it runs and whether that is the pinned one, uptime, the last requests.

Runs: in the CLI (it is about the server itself)

Answer with `--json`: `{"running": bool, "port", "pid", "checkout", "aapsCommit", "pinned": bool, "uptimeSec", "requests": [text], "text": text}`

```
facetool status
```

#### `serve`

`facetool serve`

Starts the server in the foreground, to see its log. Ctrl-C stops it.

Runs: in the CLI (it is about the server itself)

Answer with `--json`: `(no answer: the log of the server)`

```
facetool serve
```

#### `stop`

`facetool stop`

Stops the server.

Runs: in the CLI (it is about the server itself)

Answer with `--json`: `{"stopped": bool, "text": text}`

```
facetool stop
```

#### `gui`

`facetool gui [--print]`

Starts the server when needed and opens the editor page in the browser.

Runs: in the CLI (it is about the server itself)

| Argument | Type | Meaning |
|---|---|---|
| `--print` | flag | Only print the address of the page. |

Answer with `--json`: `{"url": address with the token, "text": text}`

```
facetool gui
facetool gui --print
```

### Read

#### `show`

`facetool show [<path>]`

The JSON at a path, or the whole design. Paths: metadata.name, animation.layers[24].x, looks[1].patch, animation.functions["bg()"]. Put a path with brackets in quotes: the shell reads brackets itself.

Runs: local, without the server

| Argument | Type | Meaning |
|---|---|---|
| `<path>` | string | The JSON path. Default: the whole design. |

Answer with `--json`: `{"path": text, "value": the JSON value, "text": the value in the 4 space form}`

```
facetool show metadata.name
facetool show 'animation.layers[24]'
facetool show
```

#### `list`

`facetool list <kind>`

One line per entry, with its index or name and a summary. Layers show their source: image Aurora, shape circle x120, text clock(), use rain, view sgv.

Runs: local, without the server

| Argument | Type | Meaning |
|---|---|---|
| `<kind>` | string | What to list. |

Answer with `--json`: `{"kind": text, "items": [{"index" or "name", "path"?, "summary", ...}], "text": text}`

```
facetool list layers
facetool list resources --json
```

#### `expand`

`facetool expand [<path>]`

The layers as the engine reads them after templates, let and own functions are expanded. Shows what a {"use": ...} really makes.

Runs: in the server (started when needed)

| Argument | Type | Meaning |
|---|---|---|
| `<path>` | string | A layer path. Default: all layers. |

Answer with `--json`: `{"layers": [the expanded layers], "text": text}`

```
facetool expand
facetool expand 'animation.layers[0]'
```

#### `keys`

`facetool keys [<kind>]`

The names the engine knows, from its own enums: layer keys, view keys, metadata keys, the value names (v_...), the formula functions.

Runs: in the server (started when needed)

| Argument | Type | Meaning |
|---|---|---|
| `<kind>` | string | Which names. Default: all. |

Answer with `--json`: `{"keys": {kind: [{"name", "help"?}]}, "text": text}`

```
facetool keys layer
facetool keys values --json
```

#### `manual`

`facetool manual [<topic>] [--list]`

A chapter of the engine manual of the AAPS checkout (docs/watchface-animation.html), as plain text. So an agent reads the manual of the commit that draws.

Runs: in the server (started when needed)

| Argument | Type | Meaning |
|---|---|---|
| `<topic>` | string | The topic. |
| `--list` | flag | Show the topics. |

Answer with `--json`: `{"topics": [text]?, "topic": text?, "text": the chapter}`

```
facetool manual --list
facetool manual templates
```

### Edit

#### `set`

`facetool set <path> <value> [--text]`

Sets a value in the design JSON. The value is JSON (5, "text", {...}); --text takes it as a text. A new key goes to the end of its object. In a list, the size of the list as index adds the value.

Runs: local, without the server

| Argument | Type | Meaning |
|---|---|---|
| `<path>` | string | The JSON path. |
| `<value>` | json | The value, as JSON. |
| `--text` | flag | Take the value as a text, not as JSON. |

Answer with `--json`: `{"changed": true|false, "history": number of the history entry, "text": text}`

```
facetool set animation.durationMs 12000
facetool set sgv.fontColor --text "#FF0000"
facetool set 'animation.layers[3].rect' '{"left": 0, "top": 0, "width": 400, "height": 400}'
```

#### `unset`

`facetool unset <path>`

Removes a key or a list entry.

Runs: local, without the server

| Argument | Type | Meaning |
|---|---|---|
| `<path>` | string | The JSON path. |

Answer with `--json`: `{"changed": true|false, "history": number of the history entry, "text": text}`

```
facetool unset 'animation.layers[3].blend'
```

#### `apply`

`facetool apply <patch>`

A list of edits in one step, all or none: [{"op": "set", "path": ..., "value": ...}, {"op": "unset", "path": ...}, {"op": "insert", "path": ..., "value": ...}, {"op": "move", "from": ..., "to": ...}]. insert puts a value before a list entry, or adds a new key. Each edit sees the result of the edits before it.

Runs: local, without the server

| Argument | Type | Meaning |
|---|---|---|
| `<patch>` | file | The patch file (JSON). - reads standard input. |

Answer with `--json`: `{"changed": true|false, "history": number of the history entry, "text": text}`

```
facetool apply patch.json
facetool apply -
```

#### `layer add`

`facetool layer add <json> [--at <int>]`

Adds a layer; default at the end (on top).

Runs: local, without the server

| Argument | Type | Meaning |
|---|---|---|
| `<json>` | json | The layer, as a JSON object. |
| `--at <int>` | int | The index the new layer gets. Default: the end. |

Answer with `--json`: `{"changed": true|false, "history": number of the history entry, "text": text}`

```
facetool layer add '{"shape": "circle", "x": 200, "y": 200, "radius": 20, "color": "#FFFFFF"}'
facetool layer add '{"use": "rain"}' --at 0
```

#### `layer move`

`facetool layer move <from> <to>`

Moves a layer in the paint order.

Runs: local, without the server

| Argument | Type | Meaning |
|---|---|---|
| `<from>` | int | The index of the layer. |
| `<to>` | int | The index it gets. |

Answer with `--json`: `{"changed": true|false, "history": number of the history entry, "text": text}`

```
facetool layer move 24 3
```

#### `layer copy`

`facetool layer copy <n> [--at <int>]`

Copies a layer; default right after the layer.

Runs: local, without the server

| Argument | Type | Meaning |
|---|---|---|
| `<n>` | int | The index of the layer. |
| `--at <int>` | int | The index the copy gets. Default: n + 1. |

Answer with `--json`: `{"changed": true|false, "history": number of the history entry, "text": text}`

```
facetool layer copy 24
facetool layer copy 24 --at 0
```

#### `layer remove`

`facetool layer remove <n>`

Removes a layer.

Runs: local, without the server

| Argument | Type | Meaning |
|---|---|---|
| `<n>` | int | The index of the layer. |

Answer with `--json`: `{"changed": true|false, "history": number of the history entry, "text": text}`

```
facetool layer remove 24
```

#### `res add`

`facetool res add <file> [--name <string>]`

Adds a picture (png, jpg, svg) or a font file (ttf, otf). Takes single files only, never a zip.

Runs: local, without the server

| Argument | Type | Meaning |
|---|---|---|
| `<file>` | file | The file to add. |
| `--name <string>` | string | The key of the resource: the file name without extension. Default: the name of the file. |

Answer with `--json`: `{"changed": true|false, "history": number of the history entry, "text": text}`

```
facetool res add ~/Pictures/Star.png
facetool res add /tmp/font.ttf --name FaceFont
```

#### `res replace`

`facetool res replace <key> <file>`

Replaces the bytes of a resource with another file.

Runs: local, without the server

| Argument | Type | Meaning |
|---|---|---|
| `<key>` | string | The key or file name of the resource. |
| `<file>` | file | The new file. |

Answer with `--json`: `{"changed": true|false, "history": number of the history entry, "text": text}`

```
facetool res replace Background /tmp/NewBackground.jpg
```

#### `res remove`

`facetool res remove <key>`

Removes a resource file. The JSON is not changed: save reports a name without a file.

Runs: local, without the server

| Argument | Type | Meaning |
|---|---|---|
| `<key>` | string | The key or file name of the resource. |

Answer with `--json`: `{"changed": true|false, "history": number of the history entry, "text": text}`

```
facetool res remove Santa
```

#### `res rename`

`facetool res rename <old> <new>`

Renames a resource and changes the JSON that names it (image, image1..., invalidImage, background, mask, font, fontTitle). Other texts equal to the old name are listed, not changed.

Runs: local, without the server

| Argument | Type | Meaning |
|---|---|---|
| `<old>` | string | The key of the resource. |
| `<new>` | string | The new key. |

Answer with `--json`: `{"changed": bool, "history": number, "uses": count of changed JSON places, "otherTexts": [paths], "text": text}`

```
facetool res rename Santa Sleigh
```

#### `undo`

`facetool undo`

Takes the last change back, also a change from outside the editor.

Runs: local, without the server

Answer with `--json`: `{"done": bool, "entry": {"n", "time", "command", "kind"}?, "text": text}`

```
facetool undo
```

#### `redo`

`facetool redo`

Does the change again that undo took back.

Runs: local, without the server

Answer with `--json`: `{"done": bool, "entry": {"n", "time", "command", "kind"}?, "text": text}`

```
facetool redo
```

#### `history`

`facetool history`

The changes of the project, one line each. The star marks the last change that is applied.

Runs: local, without the server

Answer with `--json`: `{"position": number, "entries": [{"n", "time", "command", "kind", "resources": [changed names]}], "text": text}`

```
facetool history
```

### Picture

#### `render`

`facetool render [--scene <file>] [--set <string>] [--out <file>] [--wake <number>] [--only <string>] [--hide <string>] [--annotate] [--grid] [--zoom <string>]`

Draws the engine face for the scene into a PNG.

Runs: in the server (started when needed)

| Argument | Type | Meaning |
|---|---|---|
| `--scene <file>` | file | A scene file (JSON): its keys go over the scene of the project. |
| `--set <string>` | string | One scene key as key=value, for example sgv=180 or options.moon=1. May repeat. |
| `--out <file>` | file | Where to write the picture. Default: .facetool/out/<command>/. |
| `--wake <number>` | number | A moment of the wake: seconds since it began. |
| `--only <string>` | string | Draw only these layers or views: indexes and view names with commas, for example 3,5,sgv. |
| `--hide <string>` | string | Do not draw these layers or views. |
| `--annotate` | flag | Draw the box and the number of each layer on top. |
| `--grid` | flag | Draw the 400 grid and the round edge. |
| `--zoom <string>` | string | Crop to x,y,w,h in design units. |

Answer with `--json`: `{"file": path of the PNG, "scene": {...}, "aapsCommit": text, "warnings": [text], "text": text}`

```
facetool render
facetool render --wake 2.5 --set sgv=180 --out /tmp/face.png
facetool render --only 3,5 --annotate --grid
```

#### `wake`

`facetool wake [--scene <file>] [--set <string>] [--out <file>] [--fps <int>] [--from <number>] [--to <number>] [--sheet] [--frames <file>] [--gif <file>]`

The wake as frames. --sheet gives one contact sheet with the time under each frame (the best input for an AI).

Runs: in the server (started when needed)

| Argument | Type | Meaning |
|---|---|---|
| `--scene <file>` | file | A scene file (JSON): its keys go over the scene of the project. |
| `--set <string>` | string | One scene key as key=value, for example sgv=180 or options.moon=1. May repeat. |
| `--out <file>` | file | Where to write the picture. Default: .facetool/out/<command>/. |
| `--fps <int>` | int | Frames per second. Default: 8. |
| `--from <number>` | number | The first moment in seconds. Default: 0. |
| `--to <number>` | number | The last moment in seconds. Default: the wake length of the design. |
| `--sheet` | flag | One contact sheet PNG (the default when nothing else is asked). |
| `--frames <file>` | file | A folder for the single PNG frames. |
| `--gif <file>` | file | Write an animation to this GIF file. |

Answer with `--json`: `{"file": path of the sheet?, "frames": [paths], "frameMs": number, "gif": path?, "scene": {...}, "text": text}`

```
facetool wake --sheet
facetool wake --fps 12 --from 0 --to 3 --gif /tmp/wake.gif
facetool wake --frames /tmp/frames
```

#### `options`

`facetool options [--scene <file>] [--set <string>] [--out <file>]`

One picture per choice of each option, as a sheet, and per colour slot.

Runs: in the server (started when needed)

| Argument | Type | Meaning |
|---|---|---|
| `--scene <file>` | file | A scene file (JSON): its keys go over the scene of the project. |
| `--set <string>` | string | One scene key as key=value, for example sgv=180 or options.moon=1. May repeat. |
| `--out <file>` | file | Where to write the picture. Default: .facetool/out/<command>/. |

Answer with `--json`: `{"file": path of the sheet, "options": [{"name", "choices": [{"index", "title", "file"}]}], "text": text}`

```
facetool options
```

#### `looks`

`facetool looks [--scene <file>] [--set <string>] [--out <file>]`

Which looks are active in the scene, and a sheet of the looks with a scene for each that turns it on, when the editor can find one.

Runs: in the server (started when needed)

| Argument | Type | Meaning |
|---|---|---|
| `--scene <file>` | file | A scene file (JSON): its keys go over the scene of the project. |
| `--set <string>` | string | One scene key as key=value, for example sgv=180 or options.moon=1. May repeat. |
| `--out <file>` | file | Where to write the picture. Default: .facetool/out/<command>/. |

Answer with `--json`: `{"active": [indexes], "file": path of the sheet, "looks": [{"index", "when", "active": bool, "scene": {...}?, "file"?}], "text": text}`

```
facetool looks
facetool looks --set weather=rain
```

#### `pick`

`facetool pick <x> <y> [--scene <file>] [--set <string>] [--out <file>]`

Which layers and views draw the pixel at x, y (in 400 design units), top first.

Runs: in the server (started when needed)

| Argument | Type | Meaning |
|---|---|---|
| `<x>` | number | x in design units. |
| `<y>` | number | y in design units. |
| `--scene <file>` | file | A scene file (JSON): its keys go over the scene of the project. |
| `--set <string>` | string | One scene key as key=value, for example sgv=180 or options.moon=1. May repeat. |
| `--out <file>` | file | Where to write the picture. Default: .facetool/out/<command>/. |

Answer with `--json`: `{"x", "y", "hits": [{"layer": index? , "view": name?, "path", "summary"}], "text": text}`

```
facetool pick 200 120
```

#### `snapshot`

`facetool snapshot [--name <string>] [--scene <file>] [--set <string>] [--out <file>]`

Pictures at a fixed set of moments (rest, 3 wake moments, night), for a before and after diff.

Runs: in the server (started when needed)

| Argument | Type | Meaning |
|---|---|---|
| `--name <string>` | string | The name of the set of pictures. Default: default. |
| `--scene <file>` | file | A scene file (JSON). |
| `--set <string>` | string | One scene key as key=value. May repeat. |
| `--out <file>` | file | The folder for the pictures. Default: .facetool/out/snapshot/<name>/. |

Answer with `--json`: `{"dir": folder, "files": [{"moment", "file"}], "text": text}`

```
facetool snapshot
facetool snapshot --name before
```

#### `scene`

`facetool scene [--scene <file>] [--set <string>] [--out <file>] [--save <string>] [--use <string>] [--reset] [--presets] [--list]`

Shows the scene of the project. --set and --scene change it, --use loads a named scene or a preset, --save keeps it under a name, --reset goes back to the defaults.

Runs: in the server (started when needed)

| Argument | Type | Meaning |
|---|---|---|
| `--scene <file>` | file | A scene file (JSON): its keys go over the scene of the project. |
| `--set <string>` | string | One scene key as key=value. May repeat. |
| `--out <file>` | file | Write the scene to this JSON file. |
| `--save <string>` | string | Keep the scene under this name in .facetool/scenes/. |
| `--use <string>` | string | Load the named scene or preset as the scene of the project. |
| `--reset` | flag | Make the scene of the project the defaults again. |
| `--presets` | flag | List the ready scenes: default, high, low, no-data, old-reading, night, christmas, low-battery. |
| `--list` | flag | List the named scenes of the project. |

Answer with `--json`: `{"scene": {...}, "names": [text]?, "presets": {name: {...}}?, "text": text}`

```
facetool scene
facetool scene --set sgv=250 --set direction=↑ --save high-now
facetool scene --use night
facetool scene --presets
```

### Check

#### `lint`

`facetool lint [--scene <file>] [--set <string>] [--no-wff]`

The engine lint and the editor's checks, then the WFF lint of 4 and 2. One line per finding: severity, path, text. Exit code 1 when there are errors.

Runs: in the server (started when needed)

| Argument | Type | Meaning |
|---|---|---|
| `--scene <file>` | file | A scene file (JSON). |
| `--set <string>` | string | One scene key as key=value. May repeat. |
| `--no-wff` | flag | Leave out the WFF lint. |

Answer with `--json`: `{"findings": [{"severity": "error|warning|info", "source": "engine|editor|wff4|wff2|player", "path": text, "text": text}], "errors": count, "warnings": count, "text": text}`

```
facetool lint
facetool lint --json
```

#### `diff`

`facetool diff <a> <b> [--render] [--scene <file>] [--set <string>] [--out <file>]`

The differences of two designs: the JSON by path (list entries matched by content, a moved entry is one line), the resources by hash with size and picture size. a and b: a zip, a folder, history:<n> (the project after history entry n; 0 is the design at open) or git:<rev> (the project folder in a git commit).

Runs: local; in the server with --render

| Argument | Type | Meaning |
|---|---|---|
| `<a>` | source | The first design. |
| `<b>` | source | The second design. |
| `--render` | flag | Add pictures side by side and the share of changed pixels (needs the server). |
| `--scene <file>` | file | A scene file (JSON), for --render. |
| `--set <string>` | string | One scene key as key=value, for --render. May repeat. |
| `--out <file>` | file | Where to write the picture of --render. |

Answer with `--json`: `{"same": bool, "json": [{"op": "change|add|remove|move", "path", "pathAfter"?, "before"?, "after"?}], "resources": [{"op", "name", "before"?: {"bytes", "hash", "width"?, "height"?}, "after"?}], "file"?: picture of --render, "changedPixels"?: share, "text": text}`

```
facetool diff history:0 .
facetool diff ~/Projects/aaps-watchfaces/cwf-designs/D2.zip . --render
facetool diff git:HEAD .
```

### WFF

#### `wff plan`

`facetool wff plan [--version 4|2]`

The planner's plan and the converter's lint: one line per layer and view (live, baked, frames, data, left out) and the totals (slots, frames, memory).

Runs: in the server (started when needed)

| Argument | Type | Meaning |
|---|---|---|
| `--version 4\|2` | string | The WFF version. Default: 4. |

Answer with `--json`: `{"version": 4|2, "parts": [{"path", "kind": "live|baked|frames|data|out", "text"}], "totals": {"slots", "frames", "memoryBytes"}, "lint": [text], "text": text}`

```
facetool wff plan
facetool wff plan --version 2 --json
```

#### `wff build`

`facetool wff build [--version 4|2]`

Makes the face folder (watchface.xml, pictures, fonts, data.zip) without Pack. Cached; the other WFF commands call it when the cache is old.

Runs: in the server (started when needed)

| Argument | Type | Meaning |
|---|---|---|
| `--version 4\|2` | string | The WFF version. Default: 4. |

Answer with `--json`: `{"version": 4|2, "dir": the face folder, "cached": bool, "seconds": number, "text": text}`

```
facetool wff build
facetool wff build --version 2
```

#### `wff render`

`facetool wff render [--scene <file>] [--set <string>] [--out <file>] [--version 4|2]`

Draws the WFF face with the player, for the scene: time, ambient, tilt, heartRate, visibleSec, tapSec, sgvBefore, transition.

Runs: in the server (started when needed)

| Argument | Type | Meaning |
|---|---|---|
| `--scene <file>` | file | A scene file (JSON): its keys go over the scene of the project. |
| `--set <string>` | string | One scene key as key=value, for example sgv=180 or options.moon=1. May repeat. |
| `--out <file>` | file | Where to write the picture. Default: .facetool/out/<command>/. |
| `--version 4\|2` | string | The WFF version. Default: 4. |

Answer with `--json`: `{"file": path of the PNG, "scene": {...}, "aapsCommit": text, "warnings": [text], "text": text}`

```
facetool wff render
facetool wff render --version 2 --set ambient=true
facetool wff render --set visibleSec=2.5 --set 'tilt=[10,0]'
```

#### `wff wake`

`facetool wff wake [--scene <file>] [--set <string>] [--out <file>] [--version 4|2] [--seconds <number>] [--fps <int>] [--watch-fps <int>] [--sheet] [--frames <file>] [--gif <file>]`

The player's frames after the screen comes on, as a sheet, frames or a GIF.

Runs: in the server (started when needed)

| Argument | Type | Meaning |
|---|---|---|
| `--scene <file>` | file | A scene file (JSON): its keys go over the scene of the project. |
| `--set <string>` | string | One scene key as key=value, for example sgv=180 or options.moon=1. May repeat. |
| `--out <file>` | file | Where to write the picture. Default: .facetool/out/<command>/. |
| `--version 4\|2` | string | The WFF version. Default: 4. |
| `--seconds <number>` | number | How many seconds. Default: 10. |
| `--fps <int>` | int | Frames per second. Default: 10. |
| `--watch-fps <int>` | int | Show only the frames a watch with this frame rate shows. |
| `--sheet` | flag | One contact sheet PNG (the default when nothing else is asked). |
| `--frames <file>` | file | A folder for the single PNG frames. |
| `--gif <file>` | file | Write an animation to this GIF file. |

Answer with `--json`: `{"file": path of the sheet?, "frames": [paths], "frameMs": number, "gif": path?, "text": text}`

```
facetool wff wake
facetool wff wake --seconds 5 --fps 10 --gif /tmp/wff.gif
```

#### `wff parts`

`facetool wff parts [--scene <file>] [--set <string>] [--out <file>] [--version 4|2]`

Each part alone: the static pictures, each loop's frames as a sheet, the live parts, the data picture.

Runs: in the server (started when needed)

| Argument | Type | Meaning |
|---|---|---|
| `--scene <file>` | file | A scene file (JSON): its keys go over the scene of the project. |
| `--set <string>` | string | One scene key as key=value, for example sgv=180 or options.moon=1. May repeat. |
| `--out <file>` | file | Where to write the picture. Default: .facetool/out/<command>/. |
| `--version 4\|2` | string | The WFF version. Default: 4. |

Answer with `--json`: `{"dir": folder, "parts": [{"name", "kind", "file"}], "text": text}`

```
facetool wff parts
```

#### `wff compare`

`facetool wff compare [--scene <file>] [--set <string>] [--out <file>]`

Engine and WFF (4 and 2) side by side for the scene, with the changed regions marked and the share of changed pixels.

Runs: in the server (started when needed)

| Argument | Type | Meaning |
|---|---|---|
| `--scene <file>` | file | A scene file (JSON): its keys go over the scene of the project. |
| `--set <string>` | string | One scene key as key=value, for example sgv=180 or options.moon=1. May repeat. |
| `--out <file>` | file | Where to write the picture. Default: .facetool/out/<command>/. |

Answer with `--json`: `{"file": path of the picture, "versions": [{"version", "changedPixels": share, "regions": [{"x", "y", "w", "h"}]}], "text": text}`

```
facetool wff compare
facetool wff compare --set sgv=250
```

#### `wff xml`

`facetool wff xml [--version 4|2]`

Prints watchface.xml, to read what the converter wrote.

Runs: in the server (started when needed)

| Argument | Type | Meaning |
|---|---|---|
| `--version 4\|2` | string | The WFF version. Default: 4. |

Answer with `--json`: `{"version": 4|2, "file": path of watchface.xml, "text": the XML}`

```
facetool wff xml
facetool wff xml --version 2
```

#### `wff apk`

`facetool wff apk [--version 4|2] [--client <string>] [--zip]`

Packs the face into an APK with Pack and runs the validator. Only to check the converter, never for wearers.

Runs: in the server (started when needed)

| Argument | Type | Meaning |
|---|---|---|
| `--version 4\|2` | string | The WFF version. Default: 4. |
| `--client <string>` | string | The app id of the wear app that pushes the face. Default: info.nightscout.androidaps. |
| `--zip` | flag | Also make the face zip for all wear apps and both versions. |

Answer with `--json`: `{"apk": path, "validator": path, "token": path, "lint": path, "zip": path?, "text": text}`

```
facetool wff apk
facetool wff apk --client info.nightscout.androidaps --zip
```

#### `wff emulator`

`facetool wff emulator [--version 4|2] [--device <string>] [--wear-apk <file>] [--wake]`

Installs the face on a running Wear OS emulator and takes its picture. Refuses a real watch.

Runs: in the server (started when needed)

| Argument | Type | Meaning |
|---|---|---|
| `--version 4\|2` | string | The WFF version. Default: 4. |
| `--device <string>` | string | The adb id of the emulator. Default: the one that runs. |
| `--wear-apk <file>` | file | The wear app APK to install first. |
| `--wake` | flag | Also record the wake. |

Answer with `--json`: `{"emulator": path of emulator.png, "compare": path of compare.png, "text": text}`

```
facetool wff emulator
facetool wff emulator --device emulator-5554 --wake
```

### Help

#### `help`

`facetool help [<command>...] [--markdown]`

Lists the commands, or explains one with examples. --json gives the whole command table, --markdown a reference for the docs.

Runs: local, without the server

| Argument | Type | Meaning |
|---|---|---|
| `<command>...` | string | The command to explain. |
| `--markdown` | flag | The reference of all commands as Markdown. |

Answer with `--json`: `{"globals": [argument], "commands": [{"name", "group", "help", "usage", "args": [argument], "server", "project", "examples", "answer"}], "text": text}`

```
facetool help
facetool help layer add
facetool help --json
facetool help --markdown
```
