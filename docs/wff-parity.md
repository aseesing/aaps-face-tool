# The parity suite of CWF and WFF

The Watch Face Format (WFF) faces that the converter makes must look like our own faces (CWF, the
layer engine of the AAPS wear app). The parity suite checks this one feature at a time. Each test is
a small design with only one feature of the engine: a shape, a font, a blend, a copy of a layer, a
look, the clock, a complication style.

A run has two steps that overlap:

1. **The tests** (JVM, many side by side). Each test converts its design into a WFF face
   (`WffApkBuilder`, as `docs/wff-apk.md`), checks that the face passes the Watch Face Push
   validator, and checks the lint. A feature that WFF cannot show must be said to be left out
   (`omit`), so a designer knows it. With the emulator, the test then writes `parity.job` (what to
   compare) and puts its folder in the queue `build/wff-parity/queue.txt`.
2. **The emulator** (one driver, `tools/wff-parity-emulator.sh`). It starts with the tests and shows
   the queued faces one after the other while the tests still run. Per face it pushes the design and
   the values into the wear app (only when they changed), installs the face, sets the clock of the
   scene, and takes the picture as soon as the screen stops changing (about 6 seconds a face; a face
   with a data picture waits at least 7, for the wear app to draw it; a test of the wake about 20).
   A face with hands or seconds never stops changing: it is shown at the time of the scene again
   just before its picture. A face that took much longer than it should (a busy emulator) is shown
   once more. A face whose files, scene, wear app and clock did not change since its last
   picture keeps that picture, so a run again only shows what changed. When the tests are done, the
   task `wffParityCompare` compares every picture with ours, region by region, and writes
   `build/wff-parity/summary.html`.

A face that is only one picture that our engine drew (all parts baked into picture 0) is not queued:
the emulator would only show that picture again ("emulator: not needed"). One test
(`ParitySampleTest.restShapes`) still queues such a face: it proves that the watch draws a baked
picture one to one. (A face of 400 units with pictures of 454 px was drawn soft; the converter now
writes the face in the size of its pictures.)

The tests are in `src/test/kotlin/app/aaps/watchfaces/wffparity/`, one class per group of features.
`WffParityBase` does the work; a test only builds its design with `ParityDesign` and says what to
expect. `ParityEmulatorCheck` is the compare step.

## When to run it

The suite takes minutes, so it is not part of `testDebugUnitTest`. Run it before you commit a change
of the layer engine, the WFF converter, the WFF complications of the wear app, `:wear:cwfrender`,
the manual of the layer engine, or a design zip that will be a WFF face. While you work on one
feature, run only its group or its test. The AAPS repository has a skill for this:
`.claude/skills/wff-parity/SKILL.md`.

## How to run it

Once: build Pack, `sh tools/build-pack.sh`.

Without the emulator (converts, validates, checks the lint):

```
./gradlew wffParityTest
```

With the emulator: start a Wear OS 6 emulator (API 36) and build the debug wear app of the AAPS
checkout that `aapsDir` points to (`./gradlew :wear:assembleFullDebug` there). The run installs it on
the emulator when it changed, and stops when it is older than its sources. Then:

```
./gradlew wffParityTest -PwffParityEmulator
```

Only some groups or tests:

```
./gradlew wffParityTest -PwffParityEmulator -PwffParityOnly=ShapesText,ClassicFace.secondView
```

(a group is the start of the class names without `Parity`, so `ClassicFace` takes the three ClassicFace classes; `--tests "..."` works too). Other settings:
`-PwffParityWearApk=<apk>` another wear app; `-PwffParityFresh` takes every
picture again; `WFF_WAIT` the most seconds a face may take to settle (default 20).

Every test uses the same moment (17 November 2026, 10:08, Europe/Amsterdam) and the same values
(`SnapshotEngine`; iob, cob and the basal rate also as numbers), and the driver sets the emulator to
that moment and time zone. The watch battery is 64 % in our engine and on the emulator: the scene
writes it to `scene/watch_battery.txt`, and the driver sets it with `dumpsys battery set level`.
Afterwards the emulator follows the network time and its own battery again. Only one driver uses
an emulator at a time (the lock `~/.cache/aaps-wff/<device>.lock`, also used by
`tools/wff-emulator.sh`). The scripts refuse a real watch.

**Two emulators are twice as fast.** The run uses every Wear OS emulator that runs: it starts one
driver per emulator, and the drivers share the queue. A second instance of the same emulator can
run beside the first:

```
~/Library/Android/sdk/emulator/emulator -avd Wear_OS_6 -read-only -no-snapshot-save &
```

(Windows and Linux: the `emulator` program of your Android SDK.) The wear app is installed on each
emulator by the run. A face keeps its picture whichever emulator took it.

## The result

Per test, in `build/wff-parity/<class>/<test>/`:

| File | What |
|---|---|
| `result.txt` | the lint, the changed share of each region, and why the test failed |
| `parity.job`, `emulator.key` | what the emulator step compares, and the state of its last picture |
| `diff.png` | left our engine, in the middle the emulator, right the changes (red: changed) |
| `reference.png`, `emulator.png` | the two pictures |
| `lint.txt`, `face/`, `face.apk`, `validator.txt` | what the converter made |
| `wake_*.png` | for a test of a wake: pictures during the wake on the emulator |

`build/wff-parity/summary.html` shows every compared face of the run, the differing ones first, and
`build/wff-parity/emulator-<device>.log` what each driver did.

A pixel counts as changed when its colour differs by more than 40 (of 255) in one channel. The edge
of the round screen and the charging mark of the emulator are not compared.

A difference has one of three causes. Find which before you change anything:

- The converter writes a WFF part that means something else: fix the converter (AAPS).
- WFF cannot do it: the converter must bake the part or leave it out and say so in the lint. The
  test then checks the lint.
- The emulator cannot show it (always-on, real sensors): say so in the test.

Do not raise the limit of a region to make a test pass without a comment that says why.
