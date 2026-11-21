---
name: face-editor
description: Make or change a design of the AAPS Custom watch face (CWF design zip) with the editor facetool, and check it as the AAPS engine draws it and as a Watch Face Format (WFF 4, WFF 2) face. Use when asked to create or design a new AAPS watch face, to change a design zip (layers, templates, options, looks, text layers, wake animation), to see how a design looks at rest, in its wake or in always-on, or to check what the WFF converter makes of it.
---

# Making a watch face design with `facetool`

All paths are in this repository (`aaps-face-tool`). The editor is `tools/facetool`. You work in a
project folder outside this repository, so call the editor by its full path: the folder of this skill
file is `<repo>/.claude/skills/face-editor`, the editor is `<repo>/tools/facetool`.

Read first, once: `docs/making-a-design.html` (the loop, a worked example, the rules). It is HTML
with a long style block: read only its text with `sed -n '/<body>/,$p' docs/making-a-design.html`.
The files of its example are in `docs/making-a-design-example/`. The command reference is `docs/face-editor.md`, or `tools/facetool help <command>`.
The manual of the design format (every key, formula, template, and what WFF can do) is in the AAPS
checkout that `aapsDir` of `local.properties` names. Read it with `tools/facetool manual --list` and
`tools/facetool manual <id>`: that works anywhere and is the manual of the commit that draws.

## Before the first command

- `local.properties` must name `aapsDir` (the AAPS checkout) and `designsDir` (the designs repository
  `aaps-watchfaces`). See `README.md`.
- If `tools/facetool` says "facetool is not built" (no `editor-cli/build/install`), build once:
  `./gradlew :editor-cli:installDist :editor-server:editorClasspath`. Build again after the editor's
  code or the AAPS checkout changed. Where the machine has a lock script for Gradle
  (`~/.claude/bin/gradle-locked.sh`), run the build through it, in the background.
- An answer that says "restart needed": run `tools/facetool stop`; the next command starts the new server.

## The loop

Work inside the project folder, or give `--project <folder>` to every command. Every file in the
project folder goes into the zip: keep patch files, scene files and notes outside it.

A layer shows only while the wake plays, unless it has `"rest": 0`: give that to everything that must
always show (art, clock, glucose). A text layer is placed by a point: `x` by its `align`, `y` is the
base line.

1. Start: `facetool new "<name>" --dir <folder>` or `facetool open <zip> --dir <folder>`. Without
   `--dir` the folder is `~/Projects/aaps-face-editing/<name>`.
2. Read: `facetool info`, `facetool list layers`, `facetool show '<path>'`, `facetool expand`,
   `facetool keys layer`, `facetool manual <id>` (for example `source-text`, `templates`, `options`,
   `looks`, `formulas`, `values`, `reference`).
3. Change, in few steps: write all edits of one idea into a patch file and run
   `facetool apply patch.json` (a JSON list of `{"op": "set", "path": ..., "value": ...}`; all or none).
   Single edits: `facetool set <path> <json>`, `facetool layer add '<json>'`, `facetool res add <file>`.
   `facetool undo` takes a change back.
4. Look, in few calls: `facetool render --annotate --grid` (where each layer draws), then
   `facetool render --batch scenes.json --out <folder>` for many scenes in one call (a JSON list such as
   `[{"out": "rest.png"}, {"sgv": 250, "out": "high.png"}, {"wake": 2}, {"options.stars": 2}, {"ambient": true}]`),
   `facetool wake --sheet` (the wake as one picture), `facetool options`, `facetool looks`.
   Open each PNG the answer names and look at it. `--json` gives the paths as `file` or `files`.
5. Check: `facetool lint` ("No findings." is the good case; fix every error, read every warning), then `facetool wff plan` (live, baked,
   data picture, left out) and `facetool wff compare` (the engine next to WFF 4 and WFF 2).
   `facetool manual wff-faces` explains the plan, the key `wff` and the limits per WFF version.
6. Save: `facetool save --out <zip>` (a new project has no zip yet), later `facetool save`. Save also
   draws the preview picture (`CustomWatchface.png`/`.jpg`, the picture in the phone's list) from the face
   at rest; `--keep-preview` keeps a picture made by hand.
7. Export, only when asked: `facetool wff apk --zip` makes the WFF face zip (needs Pack, `docs/wff-apk.md`).

The owner may have the browser page open on the same folder (`facetool gui`). It follows the folder:
each change shows there at once, with a mark on the changed layers. The page has no AI of its own.
Say in your answer what you changed.

## Rules

- Keep the zip under about 320 KB: a larger one never reaches the watch, without an error. `save` checks it.
- Every picture and font the JSON names must be in the folder (`res add`). The app has no templates
  of its own: a design brings each template it uses.
- Never merge two design zips. There is no command for it, and do not do it by hand.
- The zips of `aaps-watchfaces/cwf-designs` are made by generators there: a change in such a zip is
  lost at the next build of the designs. Say so to the owner; change the generator to keep it.
- Ask the owner before you write anything into the `aaps-watchfaces` repository. A new design stays
  in its project folder until the owner says where it goes.
- Do not change a project folder you were not given.
- Never install a face on a real watch or phone. Face APKs are only for a Wear OS emulator. Do not
  share faces made from other people's designs.
- The `wff` keys of a design are not read by the converter yet (`facetool manual wff-keys`): do not
  promise an effect of them.
- The pictures are true for the AAPS commit of `aaps-commit.txt`; an answer warns when the checkout is
  on another commit. The WFF pictures come from the editor's own player, not from a watch.
- Do not call a design "working" from pictures alone. The owner tries it on a watch.
