# Design: the face editor (`facetool`)

Status: design only, 2026-11-21. Nothing here is built. The owner answered the main choices in the
design session (section 1). A separate build session builds it from this document, step by step
(section 14).

The editor is a tool to make and change AAPS watch face designs (the design zips of the Custom watch
face, with layers, templates, options, looks, text layers and the wake animation). It shows the face
as the AAPS engine draws it, and as the WFF converter of this repository turns it into a Watch Face
Format face. It is made for AI agents first: every action is a command with text or JSON in and out,
and every picture is a PNG file. A small browser page gives a person the same actions.

Read first: `docs/wff-converter-design.md` (section 13 says what the converter is today),
`docs/wff-motion-model.md` (the `wff` key and the motion of section 4.9), `docs/wff-apk.md` (Pack,
the face zip, the emulator) and `docs/wff-parity.md` (the parity suite).

Contents

1. Decisions
2. Goals and non-goals
3. What exists today
4. The renderer
5. Architecture
6. The project folder
7. Scenes: the values a preview shows
8. The CLI
9. The HTTP API
10. The GUI
11. The WFF preview: the player
12. Parity tests: keeping the preview true
13. APK export and the emulator
14. Build plan
15. Changes outside this repository
16. Risks and open points

---

## 1. Decisions

Asked in the design session on 2026-11-21. The owner picked every recommended option.

| # | Question | Answer |
|---|---|---|
| 1 | Renderer of the preview | **The real Kotlin engine.** One long-running JVM runs the AAPS engine under Robolectric native graphics, the same code as the phone preview (`CwfPreviewRenderer`). No second engine. |
| 2 | The minimal GUI | **A browser page** served by the render server: plain HTML and JS, no npm, no build step. It calls the same JSON API as the CLI and shows the CLI command of each action. |
| 3 | The WFF preview | **A WFF player on the JVM**, checked against the emulator. It draws the converter's `watchface.xml` and pictures at any time, tilt or value. The parity suite compares it with the emulator. |
| 4 | How a design is held while it is edited | **An unpacked folder**: the design JSON in a fixed readable format and the resource files. Zips only at `open` and `save`. |
| 5 | Where the code lives | **Subprojects of `aaps-face-tool`**, and the harness (`SnapshotEngine`, `WffBaker`, `WffApkBuilder` and their helpers) moves as it is from `src/test` to code that the tests and the editor share. No change of converter logic. |
| 6 | Designs made by the generators of `aaps-watchfaces` | **Warn, allow edits.** The editor knows these designs and says on `open` and `save` that the generator is their source. |
| 7 | When the render server runs | **Auto start, idle stop.** The first command that needs it starts it in the background; it stops after 2 hours without a request. |
| 8 | Name of the CLI | **`facetool`** |

Owner decisions from before, not to reopen: the tool never merges two zips; the phone never builds
WFF APKs; APKs only verify the converter; the motion model choices (section 6 of the motion doc)
are decided.

---

## 2. Goals and non-goals

### Goals

1. Open a design zip, change it, save it as a zip that the AAPS phone app loads.
2. Show the face as the AAPS engine draws it on the watch: the rest picture, every moment of the
   wake animation, each option choice, each colour, the looks (by the values, the time, the day and
   night), the wearer's complication slots.
3. Show what the WFF converter makes of the design, per WFF version (4 and 2): the plan and the lint,
   the static pictures, the live parts, the frames, the data picture, and the motion (clock motion,
   moving sheets and covers of the motion doc, section 4.9) at any time, tilt, heart rate, glucose
   value, always-on or not.
4. Every action is a CLI command. The GUI only calls those commands. An AI agent never needs the GUI.
5. Fast feedback: a rest picture in well under a second once the server runs, a contact sheet of the
   wake in a few seconds.
6. Readable output for an AI: JSON on request, PNG files with known paths, lint as text and JSON,
   diffs of two designs by JSON path.
7. Export a WFF APK with Pack and show it on an emulator, only to check the converter.

### Non-goals

- Merging two design zips. There is no command that reads design parts from a second zip.
- Building WFF APKs on the phone, or for wearers. APKs from the editor are for the emulator only.
- Installing anything on a real watch or phone. The emulator commands refuse a real device.
- A second engine. The editor never draws an engine face with its own code.
- Changing the engine or the converter. The editor uses them as they are (section 15 lists the
  few engine changes that would help, each for the owner to decide).
- Editing the Kotlin generators of `aaps-watchfaces`. The editor works on zips in watch form.
- The shorthand of the generators (`DesignShorthand`: `rain`, `moon: true`, `orbit`, ...). The
  editor reads and writes the watch form only, as it is stored in zips.
- A full visual editor with dragging and drawing. The GUI is small (section 10).

---

## 3. What exists today

| Part | Where | What it gives the editor |
|---|---|---|
| The layer engine | AAPS `wear/src/main/.../watchfaces/` (`CustomWatchface`, `animation/LayerAnimator`, `WakeAnimationJson`, `Templates`, `Formula`, `design/Looks`, `options/DesignOptions`, `daynight/`) | The truth of how a design looks. Android code: Canvas, Views, Drawables. |
| The phone preview renderer | AAPS module `:wear:cwfrender`, `phonepreview/CwfPreviewRenderer` | Draws a design into a `Bitmap` at a time `nowMs`, with the wake (`wake()`, `isAnimating`), the watch settings (`applyWatchSettings`: options and colours), and the phone's messages (`apply(EventData)`). Always draws the active screen, not always-on (section 15). |
| The planner | AAPS `wear/.../watchfaces/wff/WffPlanner`, `WffPlan`, `WffDesignParts`, `WffLooks` (branch `local/wear-wff-slim`) | The plan of a design: live, baked, data picture, left out; the lint text. |
| The converter | this repo, `src/main/kotlin/app/aaps/facetool/wff/` (`WffFaceWriter`, `WffAssets`, `WffLoops`, `WffPictures`, `WffText`, `WffSlotLayout`, `WffShowDays`, `WffResources`, `WffFaceZip`, `WffExpressionPrinter`) | `watchface.xml` and the face folder. |
| The harness | this repo, `src/test/kotlin/app/aaps/facetool/` (`snapshot/SnapshotEngine`, `SnapshotClock`, `SnapshotData`; `wff/WffBaker`, `WffApkBuilder`, `PaintFontMeasure`, `WffEval`) | Runs the engine under Robolectric with a frozen clock and fixed values; bakes pictures and frames; builds, packs and validates a face; works out WFF expressions. |
| The parity suite | this repo, `src/test/kotlin/app/aaps/facetool/wffparity/`, `tools/wff-parity-emulator.sh` | One small design per feature, compared with the emulator, region by region. |
| Pack and the emulator scripts | this repo, `tools/` | `build-pack.sh`, `wff-emulator.sh`, `wff-wake.sh`. |
| The designs | `~/Projects/aaps-watchfaces/cwf-designs/` (12 zips) and the generators in its `src/test/kotlin` | Real designs to test with. The repo also has its own `SnapshotEngine` and before/after picture diffs. |
| The TS designer | `~/Projects/aaps-watchfaces/designer/` (untracked, about 13,000 lines) | Not used. See 4.2. |

The AAPS checkout is an included build (`aapsDir`), pinned by `aaps-commit.txt` (today
`380ea15181`, branch `local/wear-wff-slim`). The editor uses the same checkout and the same pin.

---

## 4. The renderer

### 4.1 The choice: the real engine

The preview draws through `CwfPreviewRenderer`, under Robolectric with `GraphicsMode.NATIVE`, in one
JVM that keeps running (the render server). This is how the converter already bakes its pictures
(`WffBaker` over `SnapshotEngine`), and how the phone app draws its design preview.

Why:

- **Exact by construction.** The preview is the engine's own picture. There is nothing to keep in
  step: a new engine commit is in the preview after the server restarts.
- **One truth for the WFF preview too.** The converter bakes with the same engine, and the data
  picture is drawn by the same engine on the watch. The player (section 11) draws those pictures as
  they are.
- **It exists and is tested.** `SnapshotEngine` and the parity suite run it every day.

What it costs:

- **Start-up.** A JVM with Robolectric, the AAPS resources and native graphics needs some seconds
  (to be measured in step 1). The server starts once and stays up (decision 7).
- **Memory.** A test JVM of this repo uses 1 to 3 GB. One server is fine.
- **Robolectric outside a normal test run.** Robolectric is made for JUnit. The server is started as
  one JUnit test that does not end (4.3). Step 1 of the build proves this before anything else.

### 4.2 Why not the TS designer

`~/Projects/aaps-watchfaces/designer` was studied on 2026-11-21:

- It has no CLI (`src/cli/cli.ts`, the `cwf` bin, is missing), no GUI and no build script
  (`build.mjs` is missing). It does not type-check (7 errors, one is the missing `AnimLayerSpec.ts`).
- It is a careful line-by-line port of the static face (style pass, text metrics, the chart, day and
  night, the moon) from AAPS commit `77586f50bd`. `local/personal` is now 132 commits further, with
  about 120 changed files in `wear/`, and about a third of the ported files are deleted or rewritten.
- It has no wake animation (no `LayerAnimator`, no reader of `animation`), no templates, no options,
  no generic looks, no text layers. It knows the old shorthand that `DesignShorthand` now rewrites
  away, so it would draw today's zips wrongly.
- Its 150 tests (147 pass) cover the chart, day and night, colours and helpers, not the renderer.

A second engine would drift from the first with every engine commit, and nothing would show it.
The editor does not use the TS code. Ideas worth taking from it: keep every file of a zip that the
editor does not know (licences), a side file for tool settings, and later the picture and font
optimizer (`optimize/`, `font/subset.ts`) as a separate command.

### 4.3 Robolectric as a server

The server is a JUnit 4 test class `EditorServerMain` (Robolectric runner, `GraphicsMode.NATIVE`,
`sdk = 35` as the parity tests) whose one test method starts the HTTP server and waits until it is
told to stop. It is not run by Gradle each time:

1. A Gradle task `editorClasspath` writes the test runtime class path of the server module and the
   Robolectric settings (the merged resources config that `isIncludeAndroidResources` makes) into
   `editor-server/build/editor/classpath.txt`. It runs when the code changes.
2. The launcher starts `java @classpath.txt org.junit.runner.JUnitCore
   app.aaps.facetool.editor.server.EditorServerMain` with the system properties the tests get today
   (`aaps.dir`, heap 3 GB). No Gradle at run time.
3. If plain `JUnitCore` does not work with the Android test config, the fallback is a Gradle `Test`
   task `editorServe` that runs only this class. It is slower to start and gives the same result.
   On the Mac every Gradle build goes through `~/.claude/bin/gradle-locked.sh`, and a server that
   runs inside Gradle would hold that lock for hours and block every other build of the checkout.
   This is one more reason to prefer the launch without Gradle: only `editorClasspath` runs through
   the lock.

Step 1 chose way 2 (`JUnitCore`): it works, and it starts faster than the Gradle task (section 16).
The server must run on the Java of the tests (JDK 21): Robolectric 4.17 cannot read the classes of a
much newer Java (the Mac's default `java` is 28). `editorClasspath` therefore also writes the path of
that Java into `build/editor/java.txt`, and the launcher uses it.

Rendering runs on one thread, the Robolectric main thread, which takes jobs from a queue. HTTP
threads only put jobs into the queue and wait for the answer. The clock of the engine is the
`SnapshotClock`: frozen, moved by each job to the time the job asks for.

Two things the spike of step 1 showed:

- **The clock of Robolectric only goes forward.** A job that asks for an earlier time than the last
  one starts the clock again (`ShadowPausedSystemClock.reset()`) and then sets it. The pictures are
  the same as in a new JVM (measured: the same pixels before and after the clock went back).
- **A new face per picture is slow** (2.3 s, section 16), because feeding the values waits for the
  data store of the watch. The server keeps one face (`CwfPreviewRenderer`) per project. For each
  picture it frees the part that draws (`release()`), writes the watch settings at their defaults
  again, and shows the design: the wake starts again and the picture is the same as from a new face
  (measured on 6 designs, rest and wake, one design after another in the same face). It feeds the
  values again only when the scene's values or time changed.

---

## 5. Architecture

```
  AI agent / shell                   browser
        |                               |
   facetool (CLI, plain JVM)        GUI page (HTML + JS)
        |  local commands:              |
        |  open, save, show, set,       |
        |  diff of JSON, undo, ...      |
        |                               |
        +---- HTTP JSON, localhost -----+
                       |
        render server (JVM, Robolectric native graphics)
        +----------------------------------------------------------+
        | command table (the same one the CLI has)                 |
        | project folders <-> CwfData                              |
        | engine preview: SnapshotEngine -> CwfPreviewRenderer     |
        | lint: engine readers + checks of the editor              |
        | WFF: WffPlanner -> harness convert -> face folder (cache) |
        | WFF player: watchface.xml + pictures + data picture      |
        | APK: Pack + validator (tools/build-pack.sh)              |
        +----------------------------------------------------------+
                       |
        AAPS checkout (included build, aaps-commit.txt)
```

### 5.1 Gradle projects

The root project of `aaps-face-tool` stays the converter (an Android library). Three subprojects
are added:

| Project | Kind | Holds |
|---|---|---|
| `:editor-model` | plain Kotlin JVM (`org.jetbrains.kotlin.jvm`), no Android, no AAPS | The project folder, the JSON form (6.2), JSON paths, edit operations, history (undo and redo), the JSON and resource diff, zip pack and unpack (`java.util.zip`), the command table (names, arguments, help), the config. |
| `:editor-cli` | plain Kotlin JVM application (`application` plugin, `installDist`) | `facetool`: argument parsing, local commands through `:editor-model`, server commands over HTTP (JDK `HttpClient`), starting the server. Writes GIFs from frames with `javax.imageio` when asked. |
| `:editor-server` | Android library, like the root | `EditorServerMain`, the HTTP server (JDK `com.sun.net.httpserver`, no new library), the render queue, engine preview, lint, WFF convert, the player, the GUI files (`src/main/resources/gui/`). Depends on `:editor-model`, the root project and the AAPS modules the root uses. |

The harness moves from `src/test` of the root to `src/main` of the root (decision 5), so that
`:editor-server` can use it. It keeps its packages (`app.aaps.facetool.snapshot`,
`app.aaps.facetool.wff`) and its code. Two small changes are needed and allowed, because they change
no output: `SnapshotEngine` takes an `AAPSLogger` instead of making a Mockito mock (the tests pass
their mock), and the validator jar moves from `testImplementation` to `implementation`. A check
proves the move changes nothing: the face folders of all 12 designs and all parity faces have the
same bytes before and after (step 2).

### 5.2 One command table

Every command is one entry in a table in `:editor-model`: its name, its arguments with types and
defaults, its help line, whether it needs the server, and the JSON shape of its answer. From this one
table come: the CLI parser, `facetool help`, `facetool help --json`, the HTTP routes, the GUI's
"command" line, and the reference in `docs/face-editor.md`. A test checks that the GUI calls only
names from the table (12.9).

### 5.3 Local and server commands

Commands that only read or change files run in the CLI process, so they are quick (a JVM start,
about half a second) and work without the server: `open`, `save`, `info`, `show`, `list`, `set`,
`unset`, `apply`, `layer ...`, `res ...`, `undo`, `redo`, `history`, `diff` without `--render`.
Commands that need the engine go to the server: `render`, `wake`, `pick`, `lint`, `keys`,
`manual`, `wff ...`, `diff --render`. The GUI sends every command to the server; the server runs
local commands with the same `:editor-model` code.

### 5.4 Server life

- `facetool serve` starts it in the foreground (for a person who wants to see its log).
- Any server command starts it in the background when it does not answer, waits until it is ready
  (it prints the time), then sends the command. `--no-start` turns this off.
- It stops after 2 hours without a request, on `facetool stop`, or when its code is older than the
  build (it checks a build stamp at each request and says "restart needed").
- One server per checkout of `aaps-face-tool`. Its port and process id are in
  `~/.cache/aaps-face-editor/<hash of the checkout path>/server.json`. Port: free port on
  `127.0.0.1` only, never on other addresses.
- `facetool status` shows: running or not, port, the AAPS commit it runs, whether that commit is the
  one in `aaps-commit.txt`, uptime, the last requests.
- Every answer says which AAPS commit drew it. When the checkout is not on the pinned commit, every
  answer carries a warning (the preview may differ from the watch).

---

## 6. The project folder

### 6.1 Layout

`facetool open Aurora_Town.zip` makes a folder with exactly the files of the zip, and one hidden
folder for the editor:

```
Aurora_Town/
  CustomWatchface.json        the design, in the JSON form of 6.2
  CustomWatchface.jpg         the preview picture of the zip, kept as it is
  Aurora.png ... AuroraCut.ttf  the resources, as they are in the zip (flat, by key)
  .facetool/
    project.json              source zip, its hash, the time of open, "generated" flag, the scene
    history/                  one entry per change (6.4)
    out/                      renders and WFF output of this project (section 8, 11)
```

- Default place: `~/Projects/aaps-watchfaces/editing/<zip name>/`. The build adds `editing/` to the
  `.gitignore` of `aaps-watchfaces` (one line). `--dir` gives another place.
- `open` refuses a folder that holds another design. It never copies design parts from a second zip.
- Files of the zip the editor does not know (licences, notes) are kept and saved again.
- An AI agent may edit `CustomWatchface.json` and the resources with its own file tools. The editor
  reads the folder fresh at each command and does not keep a copy of its own. The GUI watches the
  folder and shows changes at once.
- A lock file `.facetool/lock` stops two commands from writing the folder at the same moment. A
  long lock (over 30 s) is reported, never broken without `--force`.

### 6.2 The JSON form

The zips of the generators hold JSON as Android's `JSONObject.toString(4)` writes it: 4 spaces, keys
in the order they were added, `/` written as `\/`. The editor writes exactly that form, so a design
from a generator and the same design saved by the editor give the same bytes, and `git diff` shows
only real changes.

- `:editor-model` reads and writes JSON with `kotlinx.serialization.json` (it keeps the key order
  and keeps numbers as they are written: `0.10` stays `0.10`). The plain JVM `org.json` must not be
  used there: it does not keep the key order.
- The writer copies the escapes of Android's `org.json` (`\/`, `\u` forms). A test reads all 12
  zips and writes them again: the bytes must be the same (12.2). Where they differ, the writer is
  fixed, not the test.
- A zip with compact JSON (not from a generator) is written in the same 4-space form. That is the one
  change `save` may make without an edit.

### 6.3 JSON paths

Commands name a place in the design with a path:

```
metadata.name
animation.durationMs
animation.layers[24]            a layer, by its index in paint order (layers have no names)
animation.layers[24].x
animation.templates.blink.layers[0]
looks[1].patch
sgv.fontColor                   a classic view
```

`list layers` gives each layer's index and a short summary (its source: `image Aurora`, `shape
circle x120`, `text clock()`, `use rain`, `view sgv`), so an agent can find a layer. The lint and the
planner use the same numbers (`L24`).

### 6.4 History

Every change by a command (not by an outside file edit) adds an entry to `.facetool/history/`: the
command line, the time, and the JSON before (whole file; it is small) plus the hashes of changed
resources (the old resource bytes are kept by hash). `undo` and `redo` walk it. An outside edit is
noticed at the next command (the hash differs) and gets an entry "changed outside the editor" first,
so undo still works.

### 6.5 Save

`facetool save [--out file.zip]` packs the folder into a zip:

- default: back to the zip it came from, after keeping the old one as `.facetool/history/<n>.zip`;
- checks before it writes: the JSON reads as a design in the engine (server, when running; else a
  JSON check only, and a note that the engine check was skipped), the zip size is under the limit of
  the watch (about 320 KB: a larger zip never reaches the watch and gives no error), every resource
  the JSON names exists;
- for a design made by a generator (6.6), it prints a warning and saves anyway.

### 6.6 Designs made by generators

`facetool` knows the designs that the Kotlin generators in `aaps-watchfaces` make: a list in the
editor config, `generated.json` in `:editor-model`. Today that is all 12 zips of `cwf-designs`:
`Aurora_Town`, `Aurora_Town_mgdl_AAPS4`, `Aurora_Town_mmol_AAPS4`, `Cyberpunk_Tech`,
`Cyberpunk_Weather`, `Cyberpunk_Yellow`, `D2`, `Grand_Complication_Day`,
`Grand_Complication_Day_AAPS4`, `Grand_Complication_Night`, `Grand_Complication_Night_AAPS4`,
`Wilds_Weather`. A design is matched by its `metadata.filename`, so a copy under another zip name
is still known.
`open` and `save` of such a design say: "this design is made by a generator in aaps-watchfaces; a
new build of the designs overwrites this zip. Change the generator to keep a change." Edits work
(decision 6).

---

## 7. Scenes: the values a preview shows

A scene is everything outside the design that changes the picture. It is one JSON object. The
project keeps its current scene in `.facetool/project.json`; commands take `--scene file.json` or
single keys (`--set sgv=180`).

| Key | Default | Meaning |
|---|---|---|
| `time` | `2026-11-17T10:08:00` | The local time (the parity moment). |
| `zone` | `Europe/Amsterdam` | |
| `size` | `450` | Pixels of the picture (the face is square). |
| `sgv`, `delta`, `avgDelta`, `direction`, `units` | 121 mg/dl, +1.8, +1.4, `→`, mmol | The glucose. |
| `readingAgeMin` | 2 | Minutes since the reading. |
| `iob`, `iobBolus`, `iobBasal`, `cob`, `basalRate`, `bgi`, `reservoir`, `tempTarget` | as `SnapshotEngine` today | |
| `phoneBattery`, `watchBattery`, `rigBattery` | 78, 64, `--` | |
| `heartRate`, `steps` | 72, 6482 | |
| `weather`, `temperature` | `clear`, 7.5 | |
| `latitude`, `longitude` | 52.1, 5.1 | The place (sky, moon). |
| `decimalSign` | `,` | |
| `graph` | `wave` | The shape of the graph history (`wave`, `flat`, `high`, `low`, or a list of values). |
| `options` | the design's defaults | Choice per option name, for example `{"moon": 1}`. |
| `colours` | the design's own | `colour1`, `colour2` (the watch settings "Main colour", "Second colour"). |
| `watch` | the defaults of a new watch | Other watch settings by name (show seconds, show date, wake length, ...). |
| `wake` | `null` | Seconds since the wake began; `null` is the rest picture. |
| `ambient` | `false` | Always-on (engine: see section 15; WFF player: yes). |
| `tilt` | `[0, 0]` | `[ACCELEROMETER_ANGLE_X]` and `_Y` in degrees, for `gyro` (player only). |
| `visibleSec` | 5 | WFF player: seconds since the screen came on (frames on `ON_VISIBLE`). |
| `tapSec` | `null` | WFF player: seconds since the last tap. |
| `sgvBefore` | `null` | WFF player: the glucose before the last reading, to show an `animation` that glides to the new value. |
| `transition` | `null` | WFF player, WFF 4: 0 to 1, how far the change out of always-on is (`Variant` with `duration`). |

`SnapshotData` and `SnapshotEngine.messages` today fix most values in code. The build makes them
fields of `SnapshotData` with today's values as defaults (step 5), so the parity suite gives the same
pictures. `facetool scene` shows, saves (`--save name`) and loads named scenes (`.facetool/scenes/`),
and `facetool scene --presets` lists ready ones: `default`, `high`, `low`, `no-data`, `old-reading`,
`night`, `christmas`, `low-battery`.

---

## 8. The CLI

### 8.1 General rules

- `facetool <command> [args]`. Inside a project folder (or below it) the project is found by
  `.facetool/`; else `--project <dir>`.
- `--json` makes the answer one JSON object on stdout; without it the answer is short text. Errors
  go to stderr, in JSON too with `--json`.
- Every picture is written to a file and the answer gives its path. Default:
  `.facetool/out/<command>/<name>.png`; `--out` gives another path.
- Exit codes: 0 done; 1 done, but the lint has errors; 2 wrong use (unknown command or argument);
  3 the design does not read; 4 the server failed or did not start.
- `facetool help` lists the commands; `facetool help <command>` explains one with examples;
  `facetool help --json` gives the whole command table.
- An AI agent's normal loop: change the JSON (with `set`, `apply`, or its own file edit), then
  `facetool render` or `facetool wake --sheet`, look at the PNG, `facetool lint`, repeat.

### 8.2 Commands

Project (local):

| Command | What it does |
|---|---|
| `open <zip> [--dir d]` | Unpacks the zip into a project folder (6.1). |
| `new <name> [--size 400]` | A new empty design: metadata, a background colour, no layers. |
| `save [--out zip]` | Packs the folder into a zip with the checks of 6.5. |
| `info` | Name, author, sizes (zip, JSON, each resource), counts of layers, views, templates, options, looks, the wake length, whether a generator makes it, and whether the folder changed since `open`. |
| `status` | The server (5.4). |
| `serve`, `stop` | Start in the foreground, stop the server. |

Read (local):

| Command | What it does |
|---|---|
| `show [path]` | The JSON at a path (6.3), or the whole design. |
| `list layers\|views\|templates\|options\|looks\|resources\|fonts` | One line each, with index and summary. |
| `expand [path]` (server) | The layers as the engine reads them after templates, `let` and own functions are expanded, as JSON. Shows what a `{"use": ...}` really makes. |
| `keys [layer\|view\|metadata\|values\|functions]` (server) | The names the engine knows, from its own enums (`LayerProp`, `JsonKeys`, `ViewKeys`, `CwfMetadataKey`), the value names (`v_...`), the formula functions. |
| `manual [topic]` (server) | The matching chapter of the engine manual `docs/watchface-animation.html` of the AAPS checkout, as plain text. `manual --list` shows the topics. So an agent reads the manual of the same commit that draws. |

Edit (local; each adds a history entry):

| Command | What it does |
|---|---|
| `set <path> <json>` | Sets a value. `<json>` is JSON (`5`, `"text"`, `{...}`); `--text` takes it as a string. |
| `unset <path>` | Removes a key or a list entry. |
| `apply <patch.json>` | A list of edits in one step, all or none: `[{"op": "set", "path": ..., "value": ...}, {"op": "unset", ...}, {"op": "insert", ...}, {"op": "move", ...}]`. Reads stdin with `-`. |
| `layer add <json> [--at n]` | Adds a layer; default at the end (on top). |
| `layer move <from> <to>` | Moves a layer in the paint order. |
| `layer copy <n> [--at m]` | Copies a layer. |
| `layer remove <n>` | Removes a layer. |
| `res add <file> [--name key]` | Adds a picture or a font file. Takes single files only, never a zip. |
| `res replace <key> <file>`, `res remove <key>`, `res rename <old> <new>` | `rename` also changes the JSON that names it. |
| `undo`, `redo`, `history` | 6.4. |

Picture (server):

| Command | What it does |
|---|---|
| `render [scene keys] [--out f.png]` | The engine face for the scene. `--wake 2.5` a moment of the wake. `--only 3,5` or `--hide 7` draws only some layers or views (as `WffDesignParts` does for the converter: the others `gone` or `visible` 0). `--annotate` draws each layer's box and number on top. `--grid` draws the 400 grid and the round edge. `--zoom x,y,w,h` crops. |
| `wake [--fps 8] [--from s] [--to s]` | The wake as frames: `--sheet` one contact sheet PNG with the time under each frame (the best input for an AI), `--frames dir` the single PNGs, `--gif f.gif` an animation. |
| `options` | One picture per choice of each option (a sheet), and per colour slot. |
| `looks` | Which looks are active in the scene, and a sheet of the looks with a scene for each that turns it on, when the editor can find one (`when` on values, time or day). |
| `pick <x> <y>` | Which layers and views draw the pixel at x, y (in 400 design units), top first. Works by drawing each one alone (cached per design hash). |
| `snapshot [--set name]` | Pictures at a fixed set of moments (rest, 3 wake moments, night, ambient when possible), for a before and after diff. |

Check (server):

| Command | What it does |
|---|---|
| `lint [--json]` | The engine lint and the editor's checks (8.3), then the WFF lint of 4 and 2 (from the planner and the converter). One line per finding: severity, path, text. |
| `diff <a> <b> [--render]` (local, `--render` server) | `a` and `b`: a zip, a folder, `history:<n>`, or `git:<rev>` (the project folder in git). JSON diff by path; layers matched by index, with moves found (same content at another index); resources by hash with size and picture size. `--render` adds pictures side by side and the share of changed pixels. |

WFF (server; section 11 and 13):

| Command | What it does |
|---|---|
| `wff plan [--version 4\|2] [--json]` | The planner's plan and the converter's lint, per version: one line per layer and view (live, baked, frames, data, left out) and the totals (slots, frames, memory). |
| `wff build [--version 4\|2]` | Makes the face folder (`watchface.xml`, pictures, fonts, `data.zip`) without Pack. Cached by the hash of the design, the scene facts that baking uses, and the commits of AAPS and this repo. Other WFF commands call it when the cache is old. |
| `wff render [scene keys]` | The WFF face by the player, for the scene: time, `ambient`, `tilt`, `heartRate`, `visibleSec`, `tapSec`, `sgvBefore`, `transition`. |
| `wff wake [--seconds 10] [--fps 10]` | The player's frames after the screen comes on, as a sheet, frames or a GIF. |
| `wff parts` | Each part alone: the static pictures, each loop's frames as a sheet, the live parts, the data picture. |
| `wff compare` | Engine and WFF (4 and 2) side by side for the scene, with the changed regions marked and the share of changed pixels. |
| `wff xml` | Prints `watchface.xml` (for an agent that wants to read what the converter wrote). |
| `wff apk [--client app id]` | Pack and the validator (section 13). |
| `wff emulator [--device id] [--wake]` | Installs on a Wear OS emulator and takes its picture (section 13). |

### 8.3 What `lint` checks

The engine side, by the engine's own readers (so the message is the engine's):

- the JSON reads as a design (`ZipWatchfaceFormat`), the `animation` block reads
  (`WakeAnimationJson`), templates expand (`Templates`), every formula parses (`Formula`);
- unknown keys in a layer, a view, a template entry (they are template parameters there) and the
  metadata, with the nearest known key ("did you mean `rotation`?");
- resources: named but missing, present but not used (a warning; licence files are not counted);
- limits of the engine (40 layers, 1500 copies, 16 values) and the zip size limit;
- `wff` keys: the forms and the formula rules of the motion doc 4.2 to 4.4 (allowed names per block);
- by drawing: a layer or text that lies outside the round screen, a text cut by its box, a layer
  that never shows in the scene and the wake (all three as warnings, with the scene named).

The WFF side: the lint of the planner and the converter, as `lint.txt` has it today, per version,
plus the player's own lines (11.5).

`lint --json` gives `[{"severity": "error|warning|info", "source": "engine|editor|wff4|wff2|player",
"path": "animation.layers[24]", "text": "..."}]`.

---

## 9. The HTTP API

- `POST /api/run` with `{"command": "render", "args": {...}, "project": "/path"}` runs one command of
  the table and gives its JSON answer, the same as `facetool <command> --json`.
- `GET /api/commands`: the command table.
- `GET /api/file?path=...`: a file under a project's `.facetool/out/` (the GUI shows pictures this
  way). No other paths.
- `GET /api/events?project=...`: server-sent events: `changed` (the folder changed on disk),
  `rendered` (a picture is ready), `lint` (new lint), `log` (a command ran, with its CLI line).
- Only `127.0.0.1`. A random token in `server.json` must be in each request (header
  `X-Facetool-Token`), so another local web page cannot drive the server. The GUI page gets it in
  its URL from `facetool gui`.

---

## 10. The GUI

`facetool gui` starts the server when needed and opens `http://127.0.0.1:<port>/?token=...` in the
browser. One page, plain HTML, CSS and JS in `:editor-server/src/main/resources/gui/`, no framework.
It is small on purpose: every button is one command, and the page shows that command's CLI line in
the command log, so a person can copy it into a script or give it to an agent.

```
+---------------------------------------------------------------------------------------+
| Aurora Town   [Save] [Undo] [Redo]   server: AAPS 380ea15181 (pinned)    ! generated  |
+----------------------+-------------------------------------+--------------------------+
| Layers and views     |  [Engine] [WFF 4] [WFF 2] [Side by  |  Inspector               |
|  L0  shape circle x45|   side] [Diff] [Parts]              |  animation.layers[24]    |
|  L1  image MoonHalo  |                                     |  { "shape": "circle",    |
|  ...                 |        +-----------------+          |    "count": 120, ... }   |
|  L24 shape circle x120|       |                 |          |  [Apply] [Expanded]      |
|  views: sgv, chart.. |        |   the face      |          |                          |
|  (eye: hide in       |        |                 |          |  Scene                   |
|   preview only)      |        +-----------------+          |  time [10:08] date [..]  |
|                      |  wake |----o-----------| 2.4 s [>]  |  sgv [121] age [2]       |
|                      |  [annotate] [grid] [pick]           |  options moon [1 v] ...  |
|                      |                                     |  colours, ambient, tilt  |
|                      |                                     |  heart rate, tap, glide  |
+----------------------+-------------------------------------+--------------------------+
| Lint (engine | WFF 4 | WFF 2 | player)          | Command log                          |
|  ! L9 sway dropped: 3 option choices x 24 ...  | facetool set animation.layers[24]... |
+---------------------------------------------------------------------------------------+
```

Screens and what they call:

| Area | Does | Commands |
|---|---|---|
| Top bar | Save, undo, redo; server state and AAPS commit; the "generated" warning | `save`, `undo`, `redo`, `status` |
| Layers and views | The paint order with summaries; select; the eye hides a layer in the preview only | `list`, `render --hide` |
| Preview: Engine | The engine face for the scene; click to pick | `render`, `pick` |
| Preview: WFF 4, WFF 2 | The player's face for the scene | `wff render` |
| Side by side, Diff | Engine and WFF, or a design and its saved zip or a history entry | `wff compare`, `diff --render` |
| Parts | The WFF parts one by one | `wff parts` |
| Wake timeline | Scrub the wake (engine) or the seconds since visible (WFF); play shows frames from the server as they come (cached per scene) | `render --wake`, `wff render --visibleSec` |
| Inspector | The JSON of the selection; edit and apply; "Expanded" shows the engine's expanded form, read only | `show`, `set`, `expand` |
| Scene | The scene keys of section 7 | the scene keys of every render command |
| Lint | The lint, filtered by source; click a line to select its layer | `lint` |
| Command log | Every command the page ran, as a CLI line | (none) |

When the folder changes on disk (an agent edited it), the page renders again and marks the changed
layers in the list. The page never holds a design of its own: it always shows the folder.

---

## 11. The WFF preview: the player

### 11.1 What it draws

The player draws a converted face the way the WFF runtime would, from what the converter wrote:

1. `wff build` makes the face folder with the harness (the code of `WffApkBuilder.build` up to the
   folder, before Pack): `res/raw/watchface.xml`, the baked pictures and frames, the fonts, and
   `data.zip`.
2. The player reads `watchface.xml` and draws the elements in document order on an
   `android.graphics.Canvas` (in the server, under Robolectric, like the engine), at the face size.
3. Sources come from the scene: the clock (`[HOUR_0_23]`, `[MINUTE]`, `[SECOND_MILLISECOND]`,
   `[SECONDS_IN_DAY]`, `[MILLISECOND]`, `[DAY]`, `[MONTH]`, `[DAY_OF_WEEK]`, ...), the watch
   (`[BATTERY_PERCENT]`, `[HEART_RATE]`, `[STEP_COUNT]`, `[ACCELEROMETER_ANGLE_X]` and `_Y`),
   `[CONFIGURATION.*]` (the design's defaults; WFF has no AAPS options).
4. Complication slots:
   - glucose slot (10): `RANGED_VALUE` from the scene: value `sgv`, text formatted as the provider
     does (`WffGlucoseComplication`), title the delta, the arrow picture as the monochromatic image;
   - age slot (11): the minutes `readingAgeMin` and the text the provider gives ("5m");
   - data slot (20): the data picture, drawn by the engine from `data.zip` with the scene. This is
     the same engine and the same design the watch draws, so it is exact;
   - the wearer's slots (1 to 5): a plain sample picture per slot type (text, ranged value, icon)
     with the scene's heart rate, steps, carbs and insulin. Marked in the lint as a sample.
5. Time on the watch: the scene's `time` is the wall clock; `visibleSec` is how long the screen has
   been on (for `PartAnimatedImage` with `ON_VISIBLE`, `delayPlay`, `loopCount`); `tapSec` for `TAP`
   (a tap starts a loop again from frame 0, as on the emulator); `ambient` and `transition` for
   `Variant`.
6. Expressions are worked out by `WffEval` (moved from the tests to the harness), which already has
   the meanings measured on the emulator: `%` keeps the sign, `x / 0` is 0, `fract` cuts towards 0,
   `round` half up, compares give 1 or 0.

### 11.2 Elements, in order of need

Level 1, what `WffFaceWriter` writes today (the build session takes the exact list from the writer
and from the `watchface.xml` of all 12 designs and all parity faces):

- `WatchFace`, `Scene`, `Group` (`x`, `y`, `width`, `height`, `pivotX`, `pivotY`, `angle`, `alpha`),
  `PartImage`, `Image`, `PartText`, `Text`, `Font` (`family`, `size`, `color`, `letterSpacing`),
  `Template` and `Parameter`, `PartDraw` with `Rectangle`, `RoundRectangle`, `Ellipse`, `Arc`,
  `Line`, `Fill`, `Stroke`;
- `DigitalClock`, `TimeText`, `AnalogClock`, `HourHand`, `MinuteHand`, `SecondHand`;
- `ComplicationSlot`, `DefaultProviderPolicy`, `BoundingBox`, `Complication` with its parts;
- `Condition`, `Expressions`, `Expression`, `Compare`, `Default`;
- `PartAnimatedImage`, `AnimationController`, `SequenceImages`, `Image` frames;
- `Variant mode="AMBIENT"` (value only);
- `blendMode` `PLUS` and `SCREEN` on parts (WFF 3 and up);
- `Launch` (drawn as nothing; listed by `pick`).

Level 2, the motion model (section 4 and 4.9 of the motion doc), as the converter learns it:

- `Transform` (`target`, `value`, `mode` TO; `BY` with a fixed value only, as the emulator showed)
  on parts and groups, with the targets of the motion doc table 2;
- `Animation` inside a `Transform`: the move to a new value over `duration` with the
  `interpolation` curves, shown with `sgvBefore`;
- `Variant` with `duration`, `startOffset`, `interpolation` (WFF 4), shown with `transition`;
- `Gyro`, shown with `tilt`;
- `Reference` and `[REFERENCE.name]` (WFF 4);
- `renderMode` `MASK` and `SOURCE` in a `Group`;
- the parts of moving sheets and covers (turned `Group`, `PartImage` with a `Transform` on `y` by
  `fract(T * s / H)`, the cover picture after the sheet): these are level 1 and 2 elements, so they
  need nothing more than the list above.

### 11.3 Version rules

The player gets the version the face was written for (4 or 2) and refuses what that version lacks,
the same way the watch does: no `blendMode` on WFF 2 (drawn without blend, lint line), no `Variant`
transition before 4, no `Reference` before 4, whole font sizes only on WFF 2 (the Wear OS 5 runtime
draws a default size for a size with decimals: the player does the same and the lint says so).
These rules come from section 11.1 and 13.3 of the converter doc and section 2 of the motion doc.

### 11.4 Where the player is not the watch

- Text: the player draws with Android's text code under Robolectric. The WFF runtime places text a
  little differently (converter doc 11.1: ascent and descent, whole pixels, `TimeText` spacing). The
  converter already corrects for the known cases; the parity suite measures the rest (12.5).
- Frame rate: the player shows exact frames at any time; a watch drops frames (about 10 a second on
  the emulator). The player can show this with `--watch-fps 10`.
- Always-on: the player draws the always-on look; the emulator cannot show it (its display goes off),
  so this part is checked only by reading the XML, never by a picture compare.
- The wearer's slots show sample pictures, not the real providers.

### 11.5 Visibly absent

An element or attribute the player does not know is not skipped silently. The player draws a red
hatched box at its place (or over the face when it has no place) and adds a lint line `player:
cannot show <element> at <xml path>`. A test fails when any face of the test set has such a line
(12.6).

### 11.6 Speed

`wff build` bakes with the engine, the same work as making a face today: a few seconds for a simple
design, more for a design with many frames (Aurora Town's runs of frames). The cache (8.2) makes each
later `wff render` only the player's work, well under a second. The GUI shows "building the WFF
face" while it waits. The harness may get options that make a quicker build for the preview (for
example fewer frames), but the default output must stay byte for byte the same (12.1).

---

## 12. Parity tests: keeping the preview true

The editor is useful only when its pictures are what the watch shows. These tests keep it so.

| # | Test | Checks | Runs in |
|---|---|---|---|
| 12.1 | Harness move | The face folders of all 12 designs and all parity faces are byte for byte the same before and after the harness moves (step 2), and after any harness option is added. A list of file hashes is the golden file. | `testDebugUnitTest` of the root |
| 12.2 | JSON round trip | `open` then `save` of each of the 12 zips without an edit: the JSON bytes are the same, every resource is the same, and the engine draws the same pixels (rest and 3 wake moments). | `:editor-model` tests (bytes), `:editor-server` tests (pixels) |
| 12.3 | Engine preview identity | `render` through the server gives the same PNG bytes as `SnapshotEngine` with the same scene, for all 12 designs: rest, 3 wake moments, each option choice, a second colour. This guards the editor's own setup (settings, values, clock), since the engine is the same. | `:editor-server` tests |
| 12.4 | Edits and diff | Each edit operation and `apply` on small designs; undo and redo give the same bytes back; `diff` of known pairs gives a golden text. | `:editor-model` tests |
| 12.5 | Player against the engine | For parity faces that are only baked pictures (all parts in `static_0`), the player's picture and `reference.png` differ in 0 % of the pixels. For all parity faces, the player and `reference.png` are compared by region with the limits of the parity suite. | `:editor-server` tests |
| 12.6 | Player coverage | Every element and attribute in every `watchface.xml` of the test set (12 designs and all parity faces, WFF 4 and 2) is known to the player: no `player: cannot show` line. When the converter writes a new element, this test fails until the player draws it. | `:editor-server` tests |
| 12.7 | Player against the emulator | The parity suite with the emulator also writes `player.png` per face (and `player_wake_*.png` at the times of `wake_*.png`). `ParityEmulatorCheck` compares the player with the emulator, region by region, with the same limits as engine against emulator. `summary.html` gets a third column. A face where the player differs from the emulator is a player bug or a known limit with a comment (11.4). | `wffParityTest -PwffParityEmulator` |
| 12.8 | Lint goldens | `lint --json` of each of the 12 designs as a golden file. A change of the engine or the converter that changes the lint shows as a diff. | `:editor-server` tests |
| 12.9 | One command table | Every route of the API and every command the GUI page sends is in the command table; every command has help and at least one test; `facetool help --json` matches the table. | `:editor-model`, `:editor-server` tests |
| 12.10 | `wff` keys change nothing in the engine | A design with and without its `wff` keys (except `only`) draws the same engine frames (motion doc 8.1). The editor runs this as part of `lint` when a design has `wff` keys. | `:editor-server` tests |

When to run: 12.1 to 12.6 and 12.8 to 12.10 with every change of the editor (they need no emulator).
12.7 with the parity suite, before a commit that changes the player, the converter or the engine
(the same rule as `docs/wff-parity.md`).

Rules when a test differs: find the cause first (the converter doc's three causes: the converter
means something else, WFF cannot do it, the emulator cannot show it, and here a fourth: the player
draws it wrongly). Never raise a limit without a comment that says why.

---

## 13. APK export and the emulator

Only to check the converter, never for wearers (owner decision).

- `facetool wff apk [--client info.nightscout.androidaps] [--zip]` runs the existing path of
  `WffApkBuilder`: the face folder, Pack (`pack-cli` from `tools/build-pack.sh`, in
  `~/.cache/aaps-wff`), the Watch Face Push validator, the token. Output in `.facetool/out/wff/`:
  `face.apk`, `validator.txt`, `token.txt`, `lint.txt`; with `--zip` the face zip of
  `docs/wff-apk.md` for all wear apps and both versions. When Pack is not built, the command says
  how to build it and stops. It never downloads anything itself.
- `facetool wff emulator [--device id] [--wear-apk file] [--wake]` runs `tools/wff-emulator.sh`
  (and `tools/wff-wake.sh` with `--wake`): installs the face on a running Wear OS emulator, takes
  `emulator.png` and `compare.png`. The scripts already refuse a real watch; the command checks it
  too (`ro.kernel.qemu`). It uses the emulator lock of the parity suite.
- In the GUI the button is called "Check on emulator". It is hidden when no emulator runs.
- Face APKs made from other people's designs are only for this check; the README rule holds: do not
  share them.

---

## 14. Build plan

Small steps. Each step ends with its tests green and a commit (the owner's rule: commit each tested
step). Never push. Gradle only through the lock script, in the background:
`sh ~/.claude/bin/gradle-locked.sh <this repo> <log file> <tasks>`. Stage named files only.
Do not change the engine or the converter; section 15 lists what needs the owner.

### Step 0: baseline

- Work in a worktree of this repo on a branch from `editor-design`.
- `aapsDir` points to an AAPS checkout on the commit of `aaps-commit.txt` (`local/wear-wff-slim`).
- Run `testDebugUnitTest` and `wffParityTest` (without the emulator). Write down the times.
- Done when: both are green.
- Done on 2026-11-21: `testDebugUnitTest` green in 23 s (most of it from the build cache),
  `wffParityTest --rerun` green in 3 min 44 s.

### Step 1: spike, Robolectric as a server

- A throwaway `EditorServerMain` in the root's `src/test` that starts a `com.sun.net.httpserver` on
  `127.0.0.1`, renders Aurora Town's rest picture with `SnapshotEngine` on a request, and answers the
  PNG path. Start it once with `JUnitCore` from a class path file (4.3), once as a Gradle `Test`
  task.
- Measure: cold start to the first answer; a rest picture at 450 px; one wake frame; 100 wake
  frames; memory. Write the numbers into section 16 of this doc.
- Gate: when the cold start is over 60 s or a rest picture over 1 s on the Mac, stop and report to
  the owner before step 2.
- Done when: the numbers are in the doc and the way of starting is chosen.

### Step 2: move the harness to `src/main`

- Move `snapshot/*` and `wff/WffBaker`, `WffApkBuilder`, `PaintFontMeasure`, `WffEval` from
  `src/test` to `src/main`, same packages. The two changes of 5.1 (logger, validator dependency).
  Split `WffApkBuilder.build` into `convert` (to the face folder) and `pack` (Pack and validator),
  with `build` calling both, so the editor can stop after the folder.
- Before the move, write the golden hash list of 12.1; after the move it must match.
- Done when: 12.1 green, all tests green.

### Step 3: `:editor-model`, the project folder

- The Gradle subproject; `open`, `save` (without the engine check), `info`, the JSON form (6.2),
  paths (6.3), `generated.json` (6.6), the lock file.
- Done when: 12.2 (bytes) green for all 12 zips.

### Step 4: `:editor-cli`, the local commands

- The command table (5.2), the parser, `help` and `help --json`, `--json` answers, exit codes.
- `show`, `list`, `set`, `unset`, `apply`, `layer ...`, `res ...`, `undo`, `redo`, `history`,
  `diff` (JSON and resources).
- `installDist` gives `editor-cli/build/install/facetool/bin/facetool`. A short `tools/facetool`
  script calls it, so the path is short.
- Done when: 12.4 green, and the parts of 12.9 that need no server.

### Step 5: `:editor-server`, the engine preview

- `EditorServerMain` (from the spike), the HTTP API (9), the render queue, server life (5.4), the
  token. `SnapshotData` gets the scene fields of section 7 with today's values as defaults, and the
  parity suite stays the same (12.1).
- `render` with the scene, `--wake`, `--only`, `--hide`, `--annotate`, `--grid`, `--zoom`; `scene`.
- The CLI starts the server when needed.
- Done when: 12.3 green; `facetool render` in a project folder writes a PNG.

### Step 6: the wake and the other pictures

- `wake --sheet/--frames/--gif`, `options`, `looks`, `pick`, `snapshot`, `diff --render`.
- Done when: tests for each, with golden PNG hashes on two designs.

### Step 7: lint

- The checks of 8.3 that need no WFF; `lint --json`; `save` uses the engine check when the server
  runs; `keys`, `manual`, `expand`.
- Done when: 12.8 green for the engine part; 12.10 green.

### Step 8: GUI, engine part

- The page of section 10 without the WFF tabs: layers, preview, wake timeline, inspector, scene,
  lint, command log; live reload on folder changes (`WatchService` and server-sent events).
- Done when: 12.9 green; the owner tries it (the build session asks).

### Step 9: WFF build, plan and lint

- `wff build` with the cache, `wff plan`, `wff xml`, the WFF lint in `lint` per version.
- Done when: the WFF part of 12.8 green.

### Step 10: the player, level 1

- The elements of 11.2 level 1, the slots of 11.1, the version rules of 11.3, the red box of 11.5.
- `wff render`, `wff wake`, `wff parts`, `wff compare`.
- Done when: 12.5 and 12.6 green.

### Step 11: the player in the parity suite

- `player.png` per face, the compare in `ParityEmulatorCheck`, the third column of `summary.html`.
- Run the suite on the emulator.
- Done when: 12.7 runs; every face where the player differs from the emulator is fixed or has a
  comment. Report the list to the owner.

### Step 12: GUI, WFF part

- The tabs WFF 4, WFF 2, Side by side, Diff, Parts; the scene keys of the player (visible seconds,
  tap, ambient, transition, tilt, heart rate, glucose before).
- Done when: 12.9 green; the owner tries it.

### Step 13: APK and emulator commands

- `wff apk`, `wff emulator` (section 13); the GUI button.
- Done when: a face of Aurora Town is shown on the Wear OS 6 emulator through `facetool`, and
  `compare.png` matches what `tools/wff-emulator.sh` gives by hand.

### Step 14: the player, level 2

- Only together with the converter: when the converter writes a new element of the motion model,
  the player learns it in the same change, and its parity test comes with it (12.6 fails until then).
- Order of the motion doc: `Transform` and clock motion, moving sheets and covers, `Animation`,
  `Variant` transitions, `Gyro`, `Reference`, masks.
- Done when: per element, 12.5 to 12.7 green.

### Step 15: docs and skill

- `docs/face-editor.md`: how to use it, for a person and for an agent, with the command reference
  generated from the command table (`facetool help --markdown`).
- `.claude/skills/face-editor/SKILL.md`: the loop of 8.1 for an agent, the rules (never merge zips,
  APKs only on the emulator, the generated designs).
- README: a short section and the commands.
- Done when: an agent session that has only the skill makes a small new design (a background, a
  clock text layer, a glucose text, snow) and checks it on WFF 4 without help. The owner judges.

---

## 15. Changes outside this repository

None is needed to start. Each needs the owner's yes, in its own session:

| Where | Change | Why | Until then |
|---|---|---|---|
| AAPS `:wear:cwfrender`, `CwfPreviewRenderer` | A way to draw the always-on frame (`compose(..., ambient = true)` exists inside, the renderer always passes `false`) | Engine preview of always-on | The engine preview has no always-on; the scene key `ambient` works only in the player, and the GUI says so |
| AAPS engine | Skip a layer with `"wff": {"only": true}` (motion doc 8.1, already decided) | `only` layers must not show in the engine preview | The editor's `render` hides `only` layers itself (as `--hide`), and says so |
| `aaps-watchfaces` | `editing/` in `.gitignore` | The default place of project folders (6.1) | `--dir` elsewhere |
| AAPS `wear`, `ComplicationDataRepository` | A way for `CwfPreviewRenderer` to turn off the 500 ms wait before a write (`batchWindowMs` is `internal`) | A picture with new scene values in 0.3 s instead of 2.3 s (section 16) | The server keeps a face and feeds it only when the scene changed |

The editor never needs AAPS to depend on it. It needs no change of the converter: the harness move
(step 2) is a move of test helpers, and the split of `WffApkBuilder.build` changes no output.

---

## 16. Risks and open points

- **Robolectric outside a test run** (4.3). The spike (step 1) decides. If neither `JUnitCore` nor a
  Gradle `Test` task gives a usable server, the fallback is one CLI call per picture through Gradle,
  which is slow (a minute) and would need a new decision.
- **Speed** of a render and of `wff build`. To measure in step 1 (numbers go here):

  Measured in step 1 on the Mac (2026-11-21, Aurora Town, 450 px, AAPS `380ea15181`, JDK 21):

  | Measure | Value |
  |---|---|
  | cold start of the server, `JUnitCore` from the class path file | 4.0 s until it listens, 7.7 s until the first picture is answered |
  | cold start as a Gradle `Test` task (`editorServe`, warm daemon) | 9 s until the first picture is answered |
  | rest picture, 450 px, kept face (the normal case: the design changed, the scene did not) | 0.10 to 0.30 s (Aurora Town 0.25 s, Cyberpunk Tech 0.12 s, Grand Complication Night 0.11 s) |
  | rest picture, 450 px, new face or new scene values or time | 2.3 to 2.4 s, of which 2.0 s is waiting (see below) |
  | one wake frame, kept face | 0.10 to 0.28 s for the first frame asked for; 9 ms for each next frame of the same wake |
  | 100 wake frames of one wake | 0.9 s (Aurora Town), 0.17 s (Cyberpunk Tech) |
  | memory | 130 MB of Java heap in use after 60 pictures, 1.0 GB resident for the process |
  | `wff build` of Aurora Town | to measure in step 9 |

  Why a new scene costs 2 s: the engine keeps its values in the data store of the watch
  (`ComplicationDataRepository`), and that store waits 500 ms after each change to write changes
  together (`batchWindowMs`). `SnapshotEngine.feed` sends four such changes, one after the other. This
  is real waiting, not work. The server therefore keeps one face per project and feeds it only when
  the values or the time of the scene change (4.3). The gate of step 1 (a rest picture under 1 s) is
  met by the kept face, not by a new face. A change in AAPS could remove the wait (section 15).

- **The pinned AAPS commit.** The preview is true only for the commit in `aaps-commit.txt`. The
  owner's watch may run another branch (`local/personal-wff` today). Every answer names the commit
  it was drawn with, and warns when it is not the pinned one.
- **The player and the real runtime** differ in text and frame rate (11.4). The parity suite keeps
  the difference visible; it can never be zero for live text.
- **Always-on cannot be checked on the emulator.** Neither the engine preview (section 15) nor the
  player can be compared with a picture of always-on. Only a real watch shows it, on the owner's
  request.
- **Two writers on one folder.** An agent and the GUI, or two agents. The lock file prevents broken
  writes; it does not prevent one undoing the other's change. The history shows who did what.
- **The motion model is not built in the converter yet.** The player's level 2 waits for it. The
  editor shows the WFF face as the converter makes it today, with the lint.
- **The TS designer** stays untracked in `aaps-watchfaces/designer`. It is not used and not deleted
  here; that is the owner's call.
