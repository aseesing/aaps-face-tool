---
name: face-editor
description: Make or change a design of the AAPS Custom watch face with facetool, and check it as the engine draws it and as a WFF face. Use when asked to create a watch face design, to change a design zip, to see how a design looks at rest or in its wake, or to check what the WFF converter makes of it.
---

# The face editor (`facetool`)

`tools/facetool` of this repository. The full guide and the command reference are in
`docs/face-editor.md`; `facetool help` and `facetool help <command>` say the same.

## Before the first command

Build once, and again after the code of the editor or the AAPS checkout changed (on the owner's Mac
through `~/.claude/bin/gradle-locked.sh`, in the background):

```
./gradlew :editor-cli:installDist :editor-server:editorClasspath
```

## The loop

1. `facetool open <zip> --dir <folder>` or `facetool new <name> --dir <folder>`. Work in that folder,
   or give `--project <folder>` to every command.
2. Read: `facetool info`, `facetool list layers`, `facetool show <path>`, `facetool keys layer`,
   `facetool manual --list` and `facetool manual <topic>` (the manual of the engine that draws).
3. Change: `facetool set <path> <json>`, `facetool layer add <json>`, `facetool apply <patch.json>`,
   `facetool res add <file>`, or edit `CustomWatchface.json` yourself. `facetool undo` takes a change back.
4. Look: `facetool render` (the picture at rest), `facetool render --annotate --grid` (where each
   layer draws), `facetool wake --sheet` (the wake as one picture), `facetool options`,
   `facetool looks`. Read the PNG the answer names. For many scenes at once write a JSON list of
   scenes and run `facetool render --batch scenes.json --out <folder>`: one call, one picture each.
5. Check: `facetool lint`. Fix every error. Read the warnings.
6. WFF: `facetool wff plan` (what is live, baked, in the data picture, left out),
   `facetool wff compare` (the engine next to WFF 4 and WFF 2), `facetool wff xml`.
7. `facetool save --out <zip>`.

The owner may have the browser page of the editor open on the same folder (`facetool gui`). It
follows the folder: each change you make shows there at once, with a mark on the changed layers.
You do not need to do anything for that, and the page has no AI of its own. Say in your answer what
you changed, so the owner knows what the marks mean.

Use `--json` when you read the answer in code. Quote paths with brackets: `'animation.layers[3]'`.

## Rules

- Never merge two design zips. There is no command for it, and do not do it by hand.
- Never install a face on a real watch or phone. Face APKs are only for a Wear OS emulator.
- The 12 zips of `aaps-watchfaces/cwf-designs` are made by generators there: a change in the zip is
  lost at the next build of the designs. Say so to the owner; change the generator to keep it.
- Keep a zip under 320 KB: a larger one never reaches the watch, without an error.
- The pictures are true for the AAPS commit of `aaps-commit.txt`. An answer warns when the checkout
  is on another commit.
- Do not call a design "working" from pictures alone. The owner tries it on a watch.
