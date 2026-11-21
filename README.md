# aaps-face-tool

Makes Watch Face Format (WFF) face APKs from the design zips of the AAPS Custom watch face, and
checks that each face looks like our own engine draws it.

Watches that started with Wear OS 5 or later, and all watches on Wear OS 6, only run WFF faces. A
design zip (a `CustomWatchface.json` with pictures and fonts) is made for the code-based Custom watch
face of the AAPS wear app. This tool turns it into a WFF face: static art baked to pictures by the
watch's own engine, live parts the watch draws itself (clock, date, hands, glucose, its age), and one
data picture that the AAPS wear app draws on the watch. Per design it writes one face zip for the AAPS
phone app: the faces in WFF 4 (Wear OS 6) and WFF 2 (Wear OS 5) for each wear app, and the parts of
the data picture (`docs/wff-apk.md`).

## How it is built

- It uses the AAPS source code as an included Gradle build (a composite build). Set `aapsDir` in
  `local.properties` to an AAPS checkout with the WFF complications (branch `local/wear-wff-slim`).
  `aaps-commit.txt` names the AAPS commit this repo was last tested with; the build warns when the
  checkout is on another one.
- AAPS never depends on this repo. AAPS keeps what the watch needs: the layer engine with the WFF
  names, the WFF complications, and the planner that splits out the data picture.
- The design zips themselves live in the designs repository (`aaps-watchfaces`). Set `designsDir` in
  `local.properties` to make faces of its zips.

`local.properties` (not checked in) for example:

```
sdk.dir=/Users/you/Library/Android/sdk
aapsDir=/Users/you/Projects/AndroidAPS
designsDir=/Users/you/Projects/aaps-watchfaces
```

## What is where

| Folder | What |
|---|---|
| `src/main/kotlin/app/aaps/facetool/wff` | the converter: `watchface.xml`, pictures, wake loops, slots, texts, resources; and the harness: baking with our engine (`WffBaker`), Pack and the validator (`WffApkBuilder`) |
| `src/test/kotlin/app/aaps/facetool/wff` | its tests, and faces of the design zips (`WffApkTest`) |
| `src/test/kotlin/app/aaps/facetool/golden`, `src/test/golden` | the golden hash lists of the face folders: a change of the harness must not change them |
| `src/test/kotlin/app/aaps/facetool/wffparity` | the parity suite: one small design per engine feature, compared with the WFF face |
| `src/main/kotlin/app/aaps/facetool/snapshot` | draws a design with our engine at a frozen clock |
| `src/main/kotlin/app/aaps/facetool/player` | the WFF player: draws a converted face on the JVM, for the editor and the parity suite |
| `editor-model/` | the face editor's model: project folders, the JSON form, edits, history, diff, the command table (plain Kotlin) |
| `editor-cli/` | `facetool`, the command line of the face editor |
| `editor-server/` | the render server of the face editor: the AAPS engine under Robolectric behind HTTP on 127.0.0.1, and the browser page |
| `tools/` | Pack build, emulator scripts, `facetool` |
| `docs/` | `wff-converter-design.md` (the design), `wff-apk.md` (make a face), `wff-parity.md` (the suite), `wff-motion-model.md` (motion on WFF), `face-editor.md` (how to use the editor `facetool`), `face-editor-design.md` (its design) |

## Commands

```
./gradlew testDebugUnitTest                         converter tests
./gradlew testDebugUnitTest -PwffOut=build/wff      the face zip of every design zip (docs/wff-apk.md)
./gradlew wffParityTest                             the parity suite on the JVM
./gradlew wffGoldenTest                             the face folders of the design zips against their golden lists
./gradlew wffParityTest -PwffParityEmulator          and on a Wear OS 6 emulator (docs/wff-parity.md)
```

Pack must be built once first: `sh tools/build-pack.sh`.

## The face editor

`facetool` opens a design zip as a folder, changes it, and shows it as the AAPS engine draws it and
as a WFF face (`docs/face-editor.md`):

```
./gradlew :editor-cli:installDist :editor-server:editorClasspath     build it
tools/facetool open <zip> --dir <folder>                             a project folder of a design
tools/facetool render --project <folder>                             the face at rest, as a PNG
tools/facetool wake --sheet --project <folder>                       the wake as one contact sheet
tools/facetool lint --project <folder>                               errors and warnings, also of WFF
tools/facetool wff compare --project <folder>                        the engine next to WFF 4 and 2
./gradlew :editor-model:test :editor-cli:test :editor-server:testDebugUnitTest     its tests
```

Faces made from other people's designs are only for checking the converter. Do not share them.
